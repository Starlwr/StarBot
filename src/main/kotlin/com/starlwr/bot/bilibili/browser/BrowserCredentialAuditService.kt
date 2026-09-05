package com.starlwr.bot.bilibili.browser

import com.alibaba.fastjson2.JSON
import com.starlwr.bot.bilibili.credential.BilibiliCredentialFileStore
import com.starlwr.bot.bilibili.credential.BilibiliCredentialService
import com.starlwr.bot.bilibili.model.Cookies
import com.starlwr.bot.core.plugin.StarBotComponent
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

private data class CredentialDifference(
    val field: String,
    val scope: String,
    val jvmValue: String,
    val browserValue: String,
)

internal fun browserCredentialValuesEquivalent(field: String, jvmValue: String, browserValue: String): Boolean =
    if (field == "buvid4") {
        percentDecodeCookieValue(jvmValue) == percentDecodeCookieValue(browserValue)
    } else jvmValue == browserValue

private fun percentDecodeCookieValue(value: String): String = runCatching {
    // URLDecoder implements form semantics and turns a literal '+' into a space.
    // Cookie values need percent decoding while preserving literal plus signs.
    URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
}.getOrDefault(value)

/**
 * Observes the controlled browser without allowing passive Cookie writes to downgrade the JVM Credential.
 * The 90 second audit is diagnostic; validated bidirectional promotion additionally uses a short fallback poll.
 */
@StarBotComponent
class BrowserCredentialAuditService(
    private val properties: BilibiliBrowserProperties,
    private val runtime: BilibiliBrowserRuntime,
    private val credentialStore: BilibiliCredentialFileStore,
    private val credentialService: BilibiliCredentialService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = Any()
    private var lastWarningFingerprint = ""
    private var lastCandidateFingerprint = ""
    private var lastCandidateAttemptAt = 0L
    private var invalidModeWarning = ""

    @Scheduled(
        fixedDelayString = "#{\${starbot.bilibili.browser.credential-audit-interval-seconds:90} * 1000}",
        initialDelayString = "#{\${starbot.bilibili.browser.credential-audit-interval-seconds:90} * 1000}",
    )
    fun audit() = synchronized(lock) {
        if (!properties.enabled || runtime.runtimeInfo() == null) return
        val browser = runCatching { runtime.observeCredentialSnapshot() }.getOrElse { error ->
            log.warn("Bilibili 浏览器 Credential 审计失败；JVM Credential 不受影响: {}", error.toString())
            log.debug("Browser Credential audit detail", error)
            return
        } ?: return
        val envelope = credentialStore.snapshot() ?: return
        val jvmSid = envelope.cookies.firstOrNull {
            it.name.equals("sid", true) && it.transportScope == "jvm" && !it.isExpired()
        }?.value ?: envelope.account.extraCookies.orEmpty()["sid"].orEmpty()
        val differences = differences(envelope.account, browser, jvmSid)
        val fingerprint = differenceFingerprint(differences)
        if (differences.isEmpty()) {
            if (lastWarningFingerprint.isNotEmpty()) log.info("Bilibili 浏览器 Credential 漂移已消失")
            lastWarningFingerprint = ""
            persistAudit("ALIGNED", "")
        } else {
            if (fingerprint != lastWarningFingerprint) {
                log.warn("Bilibili 浏览器 Credential 审计发现漂移（仅告警，不覆盖 JVM）:\n{}", summaryTable(differences))
                log.debug("Bilibili 浏览器 Credential 漂移完整值:\n{}", rawTable(differences))
                lastWarningFingerprint = fingerprint
            }
            persistAudit("DRIFT", fingerprint)
        }
        if (syncMode() == "validated-bidirectional") tryPromote(browser, envelope.account)
        else if (browser.refreshTokenStorage.isNotBlank() || hasCoreAuthDrift(differences)) runtime.refreshCanonicalIdentity()
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    fun monitorBidirectionalCandidate() = synchronized(lock) {
        if (!properties.enabled || syncMode() != "validated-bidirectional" || runtime.runtimeInfo() == null) return
        val current = credentialStore.snapshot()?.account ?: return
        val first = runCatching { runtime.observeCredentialSnapshot() }.getOrNull() ?: return
        tryPromote(first, current)
    }

    private fun tryPromote(first: BrowserCredentialSnapshot, current: Cookies) {
        val fingerprint = candidateFingerprint(first)
        if (!hasCandidateAuthChange(first, current)) return
        val now = System.currentTimeMillis()
        if (fingerprint == lastCandidateFingerprint && now - lastCandidateAttemptAt < 90_000) return
        lastCandidateFingerprint = fingerprint
        lastCandidateAttemptAt = now
        val cookieGenerationChanged = first.cookie("SESSDATA")?.value.orEmpty() != current.sessData ||
            first.cookie("bili_jct")?.value.orEmpty() != current.biliJct
        val refreshTokenChanged = first.refreshTokenStorage != current.acTimeValue
        if (cookieGenerationChanged != refreshTokenChanged) {
            log.warn("拒绝部分更新的浏览器 Credential 候选: cookieGenerationChanged={}, refreshTokenChanged={}",
                cookieGenerationChanged, refreshTokenChanged)
            return
        }
        val second = runCatching { runtime.observeCredentialSnapshot() }.getOrNull() ?: return
        if (candidateFingerprint(second) != fingerprint) {
            log.warn("拒绝不稳定的浏览器 Credential 候选: 两次快照不一致")
            return
        }
        val ambiguous = CORE_AUTH_FIELDS.firstOrNull { field ->
            second.variants(field).map { it.value }.filter { it.isNotBlank() }.distinct().size > 1
        }
        if (ambiguous != null) {
            log.warn("拒绝含冲突 Cookie 变体的浏览器 Credential 候选: field={}", ambiguous)
            return
        }
        val uid = second.cookie("DedeUserID")?.value.orEmpty()
        if (uid.isBlank() || uid != current.dedeUserId) {
            log.warn("拒绝浏览器 Credential 候选: UID 不一致, jvmUid={}, browserUid={}", current.dedeUserId, uid)
            return
        }
        val sess = second.cookie("SESSDATA") ?: return rejectIncomplete("SESSDATA")
        val csrf = second.cookie("bili_jct")?.value.orEmpty()
        if (csrf.isBlank()) return rejectIncomplete("bili_jct")
        if (second.refreshTokenStorage.isBlank()) return rejectIncomplete("localStorage.ac_time_value")
        val candidate = copy(current).apply {
            sessData = sess.value
            biliJct = csrf
            dedeUserId = uid
            acTimeValue = second.refreshTokenStorage
            second.cookie("sid")?.value?.takeIf { it.isNotBlank() }?.let { extraCookies["sid"] = it }
        }
        val promoted = runCatching {
            credentialService.promoteBrowserCandidate(candidate, current.dedeUserId, sess.expiresAtEpochSeconds)
        }.getOrElse { error ->
            log.warn("浏览器 Credential 候选校验失败，未修改 JVM Credential: {}", error.toString())
            log.debug("Browser Credential candidate validation detail", error)
            false
        }
        if (promoted) runtime.refreshCanonicalIdentity()
    }

    private fun rejectIncomplete(field: String) {
        log.warn("拒绝不完整的浏览器 Credential 候选: missing={}", field)
    }

    private fun differences(jvm: Cookies, browser: BrowserCredentialSnapshot, jvmSid: String): List<CredentialDifference> {
        val values = linkedMapOf(
            "SESSDATA" to (jvm.sessData to browser.cookie("SESSDATA")?.value.orEmpty()),
            "bili_jct" to (jvm.biliJct to browser.cookie("bili_jct")?.value.orEmpty()),
            "DedeUserID" to (jvm.dedeUserId to browser.cookie("DedeUserID")?.value.orEmpty()),
            "buvid3" to (jvm.buvid3 to browser.cookie("buvid3")?.value.orEmpty()),
            "buvid4" to (jvm.buvid4 to browser.cookie("buvid4")?.value.orEmpty()),
            "bili_ticket" to (jvm.biliTicket to browser.cookie("bili_ticket")?.value.orEmpty()),
            "bili_ticket_expires" to ((jvm.biliTicketExpires ?: 0).toString() to browser.cookie("bili_ticket_expires")?.value.orEmpty()),
            "sid" to (jvmSid to browser.cookie("sid")?.value.orEmpty()),
        )
        if (syncMode() == "validated-bidirectional") {
            values["localStorage.ac_time_value"] = jvm.acTimeValue to browser.refreshTokenStorage
        } else if (browser.refreshTokenStorage.isNotBlank()) {
            values["localStorage.ac_time_value"] = "<withheld-by-policy>" to browser.refreshTokenStorage
        }
        val result = values.mapNotNull { (field, pair) ->
            val equivalent = browserCredentialValuesEquivalent(field, pair.first, pair.second)
            if (equivalent) null else CredentialDifference(field, scope(field), pair.first, pair.second)
        }.toMutableList()
        CORE_AUDIT_FIELDS.forEach { field ->
            val canonical = values[field]?.first ?: return@forEach
            val selected = browser.cookie(field)
            browser.variants(field).filter { it !== selected }.forEach { variant ->
                if (!browserCredentialValuesEquivalent(field, canonical, variant.value)) {
                    result += CredentialDifference(
                        "$field@${variant.domain}${variant.path}", "cookie-variant", canonical, variant.value,
                    )
                }
            }
        }
        return result
    }

    private fun scope(field: String): String = when (field) {
        "bili_ticket", "bili_ticket_expires" -> "jvm-primary/browser-observed"
        "sid" -> "transport-session"
        "localStorage.ac_time_value" -> "credential-secret"
        else -> "credential"
    }

    private fun hasCoreAuthDrift(items: List<CredentialDifference>): Boolean =
        items.any { it.field.substringBefore('@') in CORE_AUDIT_FIELDS }

    private fun hasCandidateAuthChange(browser: BrowserCredentialSnapshot, current: Cookies): Boolean =
        browser.cookie("SESSDATA")?.value.orEmpty() != current.sessData ||
            browser.cookie("bili_jct")?.value.orEmpty() != current.biliJct ||
            browser.refreshTokenStorage != current.acTimeValue

    private fun summaryTable(items: List<CredentialDifference>): String = buildString {
        appendLine("field | scope | JVM | browser")
        appendLine("--- | --- | --- | ---")
        items.forEach { appendLine("${it.field} | ${it.scope} | ${summary(it.jvmValue)} | ${summary(it.browserValue)}") }
    }.trimEnd()

    private fun rawTable(items: List<CredentialDifference>): String = buildString {
        appendLine("field | JVM | browser")
        appendLine("--- | --- | ---")
        items.forEach { appendLine("${it.field} | ${it.jvmValue} | ${it.browserValue}") }
    }.trimEnd()

    private fun summary(value: String): String = when {
        value.isEmpty() -> "<empty>"
        value.length <= 4 -> "len=${value.length}, value=<masked>, sha=${hash(value)}"
        value.length <= 12 -> "len=${value.length}, value=${value.take(2)}…${value.takeLast(2)}, sha=${hash(value)}"
        else -> "len=${value.length}, value=${value.take(4)}…${value.takeLast(4)}, sha=${hash(value)}"
    }

    private fun differenceFingerprint(items: List<CredentialDifference>): String = hash(
        items.joinToString("\n") { "${it.field}\u0000${it.jvmValue}\u0000${it.browserValue}" }
    )

    private fun candidateFingerprint(snapshot: BrowserCredentialSnapshot): String = hash(
        listOf("SESSDATA", "bili_jct", "DedeUserID", "sid").joinToString("\n") { field ->
            snapshot.variants(field).sortedBy { "${it.domain}\u0000${it.path}" }.joinToString("|") {
                "$field@${it.domain}${it.path}=${it.value}"
            }
        } + "\nac_time_value=${snapshot.refreshTokenStorage}"
    )

    private fun persistAudit(status: String, fingerprint: String) {
        val current = credentialStore.snapshot()?.browser ?: return
        if (current.lastCredentialAuditStatus == status && current.lastCredentialAuditFingerprint == fingerprint) return
        credentialStore.update(critical = true) { envelope ->
            envelope.browser.lastCredentialAuditAtEpochMillis = System.currentTimeMillis()
            envelope.browser.lastCredentialAuditStatus = status
            envelope.browser.lastCredentialAuditFingerprint = fingerprint
        }
    }

    private fun copy(source: Cookies): Cookies = JSON.parseObject(JSON.toJSONString(source), Cookies::class.java)
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }
    private fun syncMode(): String {
        val configured = properties.credentialSyncMode.trim().lowercase(Locale.ROOT)
        if (configured in SUPPORTED_SYNC_MODES) return configured
        if (invalidModeWarning != configured) {
            log.warn(
                "未知的浏览器 Credential 同步模式 '{}', 已安全回退到 jvm-authoritative",
                properties.credentialSyncMode,
            )
            invalidModeWarning = configured
        }
        return "jvm-authoritative"
    }

    private companion object {
        val SUPPORTED_SYNC_MODES = setOf("jvm-authoritative", "validated-bidirectional")
        val CORE_AUTH_FIELDS = setOf("SESSDATA", "bili_jct", "DedeUserID")
        val CORE_AUDIT_FIELDS = setOf("SESSDATA", "bili_jct", "DedeUserID", "buvid3", "buvid4")
    }
}
