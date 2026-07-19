package com.starlwr.bot.bilibili.credential

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import com.alibaba.fastjson2.JSONWriter
import com.starlwr.bot.bilibili.model.Cookies
import com.starlwr.bot.bilibili.browser.BilibiliBrowserProperties
import com.starlwr.bot.core.plugin.StarBotComponent
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID

@StarBotComponent
class BilibiliCredentialFileStore(private val properties: BilibiliCredentialProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = Any()
    @Volatile private var current: CredentialEnvelope? = null

    fun path(): Path = Path.of(properties.credentialFile).toAbsolutePath().normalize()

    fun load(): CredentialEnvelope? = synchronized(lock) {
        val primary = path()
        val legacy = Path.of(properties.legacyCookieFile).toAbsolutePath().normalize()
        val source = when {
            Files.isRegularFile(primary) -> primary
            Files.isRegularFile(legacy) -> legacy
            else -> return null
        }
        val text = Files.readString(source, StandardCharsets.UTF_8)
        val root = JSON.parseObject(text) ?: return null
        val sourceSchema = root.getIntValue("schemaVersion", root.getIntValue("schema_version", 0))
        val envelope = if (sourceSchema >= 2) {
            JSON.parseObject(text, CredentialEnvelope::class.java)
        } else {
            migrateFlat(text, root)
        } ?: return null
        normalize(envelope)
        current = envelope
        if (source != primary || sourceSchema < CredentialEnvelope.CURRENT_SCHEMA) {
            backupBeforeMigration(source, sourceSchema)
            saveEnvelope(envelope, incrementRevision = false)
        }
        envelope.copyDeep()
    }

    fun snapshot(): CredentialEnvelope? = synchronized(lock) { current?.copyDeep() }

    fun loadCookies(): Cookies? = (snapshot() ?: load())?.account?.copyCredential()

    fun saveCookies(cookies: Cookies) = update(critical = true) { envelope ->
        envelope.account = cookies.copyCredential()
        mergeKnownCookies(envelope, cookies)
    }

    fun saveCookiesWithMetadata(cookies: Cookies, responseCookies: Collection<StoredCookie>) = synchronized(lock) {
        commitCookies(cookies, responseCookies, clearPendingRefresh = false)
    }

    fun saveValidatedExpiryRepair(cookies: Cookies): Boolean = synchronized(lock) {
        val envelope = current ?: load() ?: return false
        val expiry = cookies.expiresAtEpochSeconds?.takeIf { it > 0 } ?: return false
        val matching = envelope.cookies.filter {
            it.name.equals("SESSDATA", true) && it.transportScope == "shared" && it.value == cookies.sessData
        }
        if (matching.isNotEmpty() && matching.all { it.expiresAtEpochSeconds == expiry }) return false
        envelope.account = cookies.copyCredential()
        mergeKnownCookies(envelope, envelope.account)
        val repaired = envelope.cookies.filter {
            it.name.equals("SESSDATA", true) && it.transportScope == "shared" && it.value == cookies.sessData
        }
        repaired.forEach {
            it.expiresAtEpochSeconds = expiry
            it.source = "validated-legacy-expiry-repair"
        }
        envelope.identityRevision++
        normalize(envelope)
        current = envelope
        saveEnvelope(envelope, incrementRevision = false)
        true
    }

    fun update(critical: Boolean = true, mutation: (CredentialEnvelope) -> Unit): CredentialEnvelope = synchronized(lock) {
        val envelope = current ?: load() ?: CredentialEnvelope()
        mutation(envelope)
        envelope.identityRevision++
        normalize(envelope)
        current = envelope
        // All current callers mutate credentials or identity. Keep write-through semantics;
        // the coordinator may add debounce for high-rate web-storage patches later.
        if (critical) saveEnvelope(envelope, incrementRevision = false)
        envelope.copyDeep()
    }

    fun cookiesFor(uri: java.net.URI, transport: String): List<StoredCookie> = synchronized(lock) {
        val envelope = current ?: load() ?: return emptyList()
        envelope.cookies.filter { it.matches(uri, transport) }.map { it.copy() }
    }

    fun mergeCookies(
        values: Collection<StoredCookie>,
        critical: Boolean = true,
        projectAccount: Boolean = true,
    ): Boolean = synchronized(lock) {
        if (values.isEmpty()) return false
        val envelope = current ?: load() ?: CredentialEnvelope()
        val indexed = envelope.cookies.associateByTo(linkedMapOf()) { it.key() }
        var changed = false
        values.filterNot {
            val name = it.name.lowercase(Locale.ROOT)
            name in CredentialEnvelope.SESSION_ONLY_COOKIE_NAMES || name == "ac_time_value"
        }.forEach { incoming ->
            val old = indexed[incoming.key()]
            if (old != incoming) {
                changed = true
                if (incoming.isExpired()) indexed.remove(incoming.key()) else indexed[incoming.key()] = incoming
            }
        }
        if (!changed) return false
        envelope.cookies = indexed.values.toMutableList()
        if (projectAccount) projectKnownCookies(envelope)
        envelope.identityRevision++
        normalize(envelope)
        current = envelope
        if (critical) saveEnvelope(envelope, incrementRevision = false)
        true
    }

    fun pendingRefresh(): PendingCredentialRefresh? = synchronized(lock) {
        val envelope = current ?: load() ?: return null
        envelope.pendingRefresh?.let {
            JSON.parseObject(JSON.toJSONString(it), PendingCredentialRefresh::class.java)
        }
    }

    fun stagePendingRefresh(pending: PendingCredentialRefresh) = update(critical = true) { envelope ->
        envelope.pendingRefresh = pending
    }

    fun updatePendingRefresh(mutation: (PendingCredentialRefresh) -> Unit) = update(critical = true) { envelope ->
        envelope.pendingRefresh?.let(mutation)
    }

    fun completePendingRefresh(candidate: Cookies, responseCookies: Collection<StoredCookie>) = synchronized(lock) {
        commitCookies(candidate, responseCookies, clearPendingRefresh = true)
    }

    private fun commitCookies(
        candidate: Cookies,
        responseCookies: Collection<StoredCookie>,
        clearPendingRefresh: Boolean,
    ) {
        val envelope = current ?: load() ?: CredentialEnvelope()
        envelope.account = candidate.copyCredential()
        val indexed = envelope.cookies.associateByTo(linkedMapOf()) { it.key() }
        responseCookies.filterNot { it.name.equals("ac_time_value", true) }.forEach { cookie ->
            if (cookie.isExpired()) indexed.remove(cookie.key()) else indexed[cookie.key()] = cookie
        }
        envelope.cookies = indexed.values.toMutableList()
        // Response metadata is authoritative for scope and attributes. Project
        // modeled values only after it is present so HostOnly is not widened and
        // Domain cookies are not accidentally narrowed.
        mergeKnownCookies(envelope, envelope.account)
        if (clearPendingRefresh) envelope.pendingRefresh = null
        envelope.identityRevision++
        normalize(envelope)
        current = envelope
        saveEnvelope(envelope, incrementRevision = false)
    }

    fun cookieHash(transport: String = "shared"): String = synchronized(lock) {
        val envelope = current ?: load() ?: return ""
        val canonical = envelope.cookies
            .filter { it.transportScope == "shared" || it.transportScope == transport }
            .sortedBy { it.key() }
            .joinToString("\n") { "${it.key()}=${it.value}" }
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun migrateFlat(text: String, root: JSONObject): CredentialEnvelope {
        val account = JSON.parseObject(text, Cookies::class.java) ?: Cookies()
        normalizeCookies(account)
        val envelope = CredentialEnvelope(account = account)
        mergeKnownCookies(envelope, account)
        account.extraCookies.orEmpty().forEach { (name, value) ->
            if (name.lowercase(Locale.ROOT) !in CredentialEnvelope.SESSION_ONLY_COOKIE_NAMES && value.isNotBlank()) {
                envelope.cookies += StoredCookie(name = name, value = value, source = "legacy-extra")
            }
        }
        envelope.identityRevision = root.getLongValue("identityRevision", 1L).coerceAtLeast(1L)
        return envelope
    }

    private fun normalize(envelope: CredentialEnvelope) {
        normalizeCookies(envelope.account)
        envelope.schemaVersion = CredentialEnvelope.CURRENT_SCHEMA
        envelope.cookies.removeIf {
            val name = it.name.lowercase(Locale.ROOT)
            it.name.isBlank() || name in CredentialEnvelope.SESSION_ONLY_COOKIE_NAMES || name == "ac_time_value"
        }
        envelope.cookies.forEach { cookie ->
            cookie.domain = if (cookie.hostOnly) {
                cookie.domain.trimStart('.')
            } else {
                ".${cookie.domain.trimStart('.')}"
            }
            if (cookie.path.isBlank()) cookie.path = "/"
            when {
                cookie.name.equals("bili_ticket", true) || cookie.name.equals("bili_ticket_expires", true) ->
                    cookie.transportScope = "jvm"
                cookie.name.equals("sid", true) && cookie.transportScope == "shared" ->
                    cookie.transportScope = "jvm"
            }
        }
        envelope.cookies = envelope.cookies.distinctBy { it.key() }.toMutableList()
        envelope.account.extraCookies.orEmpty()["sid"]?.takeIf { it.isNotBlank() }?.let { sid ->
            val existing = envelope.cookies.firstOrNull { it.name.equals("sid", true) && it.transportScope == "jvm" }
            if (existing == null) {
                envelope.cookies += StoredCookie(name = "sid", value = sid, transportScope = "jvm", source = "credential-refresh")
            } else if (existing.value != sid) {
                existing.value = sid
                existing.source = "credential-refresh"
            }
        }
        val resolutionCookie = envelope.cookies.firstOrNull { it.name.equals("browser_resolution", true) }
        if (resolutionCookie == null) {
            envelope.cookies += StoredCookie(
                name = "browser_resolution",
                value = BilibiliBrowserProperties.BROWSER_RESOLUTION,
                source = "starbot-client-profile",
            )
        } else {
            resolutionCookie.value = BilibiliBrowserProperties.BROWSER_RESOLUTION
            resolutionCookie.source = "starbot-client-profile"
        }
        mergeKnownCookies(envelope, envelope.account)
        envelope.sanitizeForPersistence()
    }

    private fun normalizeCookies(cookies: Cookies) {
        cookies.sessData = cookies.sessData.orEmpty()
        cookies.biliJct = cookies.biliJct.orEmpty()
        cookies.buvid3 = cookies.buvid3.orEmpty()
        cookies.buvid4 = cookies.buvid4.orEmpty()
        cookies.dedeUserId = cookies.dedeUserId.orEmpty()
        cookies.acTimeValue = cookies.acTimeValue.orEmpty()
        cookies.bNut = cookies.bNut.orEmpty()
        cookies.biliTicket = cookies.biliTicket.orEmpty()
        cookies.issuedAtEpochSeconds = cookies.issuedAtEpochSeconds ?: 0L
        cookies.expiresAtEpochSeconds = cookies.expiresAtEpochSeconds ?: 0L
        cookies.nextRefreshAtEpochSeconds = cookies.nextRefreshAtEpochSeconds ?: 0L
        cookies.lastValidatedAtEpochSeconds = cookies.lastValidatedAtEpochSeconds ?: 0L
        cookies.validationLeaseExpiresAtEpochSeconds = cookies.validationLeaseExpiresAtEpochSeconds?.takeIf { it > 0 }
            ?: cookies.lastValidatedAtEpochSeconds.takeIf { it > 0 }
                ?.plus(properties.validationLeaseSeconds.coerceIn(60, 300)) ?: 0L
        cookies.serverRefreshCheckedAtEpochSeconds = cookies.serverRefreshCheckedAtEpochSeconds ?: 0L
        cookies.serverRefreshWindowExpiresAtEpochSeconds =
            cookies.serverRefreshWindowExpiresAtEpochSeconds?.takeIf { it > 0 }
                ?: cookies.serverRefreshCheckedAtEpochSeconds.takeIf { it > 0 }
                    ?.plus(properties.refreshWindowLeaseSeconds.coerceIn(60, 3600)) ?: 0L
        cookies.serverRefreshTimestampMillis = cookies.serverRefreshTimestampMillis ?: 0L
        cookies.refreshFailureCount = cookies.refreshFailureCount ?: 0
        cookies.refreshRetryAfterEpochSeconds = cookies.refreshRetryAfterEpochSeconds ?: 0L
        cookies.extraCookies = LinkedHashMap(cookies.extraCookies.orEmpty())
        cookies.extraCookies.keys.removeIf {
            val name = it.lowercase(Locale.ROOT)
            name in CredentialEnvelope.SESSION_ONLY_COOKIE_NAMES || name in MODELED_COOKIE_NAMES
        }
        cookies.extraCookies["browser_resolution"] = BilibiliBrowserProperties.BROWSER_RESOLUTION
    }

    private fun mergeKnownCookies(envelope: CredentialEnvelope, account: Cookies) {
        val values = linkedMapOf(
            "SESSDATA" to account.sessData,
            "bili_jct" to account.biliJct,
            "buvid3" to account.buvid3,
            "buvid4" to account.buvid4,
            "DedeUserID" to account.dedeUserId,
            "b_nut" to account.bNut,
            "bili_ticket" to account.biliTicket,
            "bili_ticket_expires" to account.biliTicketExpires?.takeIf { it > 0 }?.toString(),
        )
        val indexed = envelope.cookies.associateByTo(linkedMapOf()) { it.key() }
        values.filterValues { !it.isNullOrBlank() }.forEach { (name, value) ->
            val scope = if (name in setOf("bili_ticket", "bili_ticket_expires")) "jvm" else "shared"
            val template = StoredCookie(
                name = name,
                value = value.orEmpty(),
                httpOnly = name == "SESSDATA",
                expiresAtEpochSeconds = when (name) {
                    "SESSDATA" -> account.expiresAtEpochSeconds?.takeIf { it > 0 }
                    "bili_ticket", "bili_ticket_expires" -> account.biliTicketExpires?.takeIf { it > 0 }
                    else -> null
                },
                transportScope = scope,
            )
            val existing = indexed[template.key()] ?: indexed.values
                .filter {
                    it.name.equals(template.name, true) &&
                        it.transportScope == template.transportScope &&
                        it.value == template.value
                }
                .maxByOrNull(::cookieMetadataRank)
            if (existing == null) {
                indexed[template.key()] = template
            } else {
                // Preserve attributes learned from the server (SameSite, host-only,
                // exact domain/path and source) while projecting the current value.
                val credentialGenerationChanged = existing.value != template.value
                existing.value = template.value
                existing.httpOnly = existing.httpOnly || template.httpOnly
                if (credentialGenerationChanged) {
                    existing.expiresAtEpochSeconds = template.expiresAtEpochSeconds
                } else if (existing.expiresAtEpochSeconds == null) {
                    template.expiresAtEpochSeconds?.let { existing.expiresAtEpochSeconds = it }
                }
            }
        }
        envelope.cookies = indexed.values.toMutableList()
    }

    private fun projectKnownCookies(envelope: CredentialEnvelope) {
        val active = envelope.cookies.filterNot { it.isExpired() }
        fun value(name: String, vararg scopes: String): String? = active.firstOrNull {
            it.name.equals(name, true) && it.transportScope in scopes
        }?.value
        envelope.account.apply {
            value("SESSDATA", "shared")?.let { sessData = it }
            value("bili_jct", "shared")?.let { biliJct = it }
            value("buvid3", "shared")?.let { buvid3 = it }
            value("buvid4", "shared")?.let { buvid4 = it }
            value("DedeUserID", "shared")?.let { dedeUserId = it }
            value("b_nut", "shared")?.let { bNut = it }
            value("bili_ticket", "jvm", "shared")?.let { biliTicket = it }
            value("bili_ticket_expires", "jvm", "shared")?.toLongOrNull()?.let { biliTicketExpires = it }
        }
    }

    private fun saveEnvelope(envelope: CredentialEnvelope, incrementRevision: Boolean) {
        if (incrementRevision) envelope.identityRevision++
        envelope.sanitizeForPersistence()
        val target = path()
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling("${target.fileName}.tmp-${UUID.randomUUID()}")
        try {
            Files.writeString(temporary, JSON.toJSONString(envelope, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun backupBeforeMigration(source: Path, sourceSchema: Int) {
        if (!Files.isRegularFile(source)) return
        val suffix = if (sourceSchema < 2) "v1" else "schema$sourceSchema"
        val backup = path().resolveSibling("${path().fileName}.$suffix.bak")
        if (!Files.exists(backup)) Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES)
    }

    private fun CredentialEnvelope.copyDeep(): CredentialEnvelope =
        JSON.parseObject(JSON.toJSONString(this), CredentialEnvelope::class.java)

    private fun Cookies.copyCredential(): Cookies = Cookies().also {
        it.sessData = sessData; it.biliJct = biliJct; it.buvid3 = buvid3; it.buvid4 = buvid4
        it.dedeUserId = dedeUserId; it.acTimeValue = acTimeValue; it.bNut = bNut
        it.biliTicket = biliTicket; it.biliTicketExpires = biliTicketExpires
        it.issuedAtEpochSeconds = issuedAtEpochSeconds; it.expiresAtEpochSeconds = expiresAtEpochSeconds
        it.nextRefreshAtEpochSeconds = nextRefreshAtEpochSeconds; it.lastValidatedAtEpochSeconds = lastValidatedAtEpochSeconds
        it.validationLeaseExpiresAtEpochSeconds = validationLeaseExpiresAtEpochSeconds
        it.serverRefreshRequired = serverRefreshRequired
        it.serverRefreshCheckedAtEpochSeconds = serverRefreshCheckedAtEpochSeconds
        it.serverRefreshWindowExpiresAtEpochSeconds = serverRefreshWindowExpiresAtEpochSeconds
        it.serverRefreshTimestampMillis = serverRefreshTimestampMillis
        it.refreshFailureCount = refreshFailureCount; it.refreshRetryAfterEpochSeconds = refreshRetryAfterEpochSeconds
        it.extraCookies = LinkedHashMap(extraCookies.orEmpty())
    }

    private companion object {
        fun cookieMetadataRank(cookie: StoredCookie): Int = when (cookie.source.lowercase(Locale.ROOT)) {
            "qr-login", "credential-refresh", "server-set-cookie" -> 400
            "validated-legacy-expiry-repair" -> 300
            "browser-audit", "browser-observation", "browser" -> 200
            "legacy-extra" -> 100
            else -> 0
        }

        val MODELED_COOKIE_NAMES = setOf(
            "sessdata", "bili_jct", "buvid3", "buvid4", "dedeuserid", "ac_time_value",
            "b_nut", "bili_ticket", "bili_ticket_expires",
        )
    }
}
