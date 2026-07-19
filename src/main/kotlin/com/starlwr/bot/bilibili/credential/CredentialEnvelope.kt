package com.starlwr.bot.bilibili.credential

import com.alibaba.fastjson2.annotation.JSONField
import com.starlwr.bot.bilibili.model.Cookies
import java.net.URI
import java.time.Instant
import java.util.Locale

/**
 * Canonical, forward-migratable persistence document for a Bilibili identity.
 * BrowserProfile is a cache; everything StarBot needs to rebuild it lives here.
 */
data class CredentialEnvelope(
    var schemaVersion: Int = CURRENT_SCHEMA,
    var identityRevision: Long = 0,
    var account: Cookies = Cookies(),
    var cookies: MutableList<StoredCookie> = mutableListOf(),
    var configuredProfile: ClientProfileState = ClientProfileState(),
    var effectiveJvmProfile: ClientProfileState = ClientProfileState(transport = "jvm"),
    var effectiveBrowserProfile: ClientProfileState = ClientProfileState(transport = "browser"),
    var webStorage: MutableMap<String, MutableMap<String, String>> = linkedMapOf(),
    var browser: BrowserPersistenceState = BrowserPersistenceState(),
    var identityProbe: IdentityProbePersistence = IdentityProbePersistence(),
    var pendingRefresh: PendingCredentialRefresh? = null,
    var updatedAtEpochMillis: Long = 0,
) {
    companion object {
        const val CURRENT_SCHEMA = 5
        val SESSION_ONLY_COOKIE_NAMES = setOf("b_lsid")
        val BROWSER_STORAGE_ALLOWLIST = setOf(
            "liveWatchTracker", "liveWatchHbCounter"
        )
    }

    fun sanitizeForPersistence() {
        schemaVersion = CURRENT_SCHEMA
        cookies.removeIf { it.name.lowercase(Locale.ROOT) in SESSION_ONLY_COOKIE_NAMES }
        webStorage.values.forEach { storage ->
            storage.keys.removeIf { key ->
                key !in BROWSER_STORAGE_ALLOWLIST &&
                    !key.startsWith("secure_collect_last_report_time_") &&
                    !key.startsWith("secure_collect_report_interval_")
            }
        }
        webStorage.entries.removeIf { it.value.isEmpty() }
        account.extraCookies?.keys?.removeIf { it.lowercase(Locale.ROOT) in SESSION_ONLY_COOKIE_NAMES }
        updatedAtEpochMillis = System.currentTimeMillis()
    }
}

data class StoredCookie(
    var name: String = "",
    var value: String = "",
    var domain: String = ".bilibili.com",
    var path: String = "/",
    var hostOnly: Boolean = false,
    var secure: Boolean = true,
    var httpOnly: Boolean = false,
    var sameSite: String? = null,
    var expiresAtEpochSeconds: Long? = null,
    var transportScope: String = "shared",
    var source: String = "credential",
) {
    fun isExpired(nowEpochSeconds: Long = Instant.now().epochSecond): Boolean =
        expiresAtEpochSeconds?.let { it <= nowEpochSeconds } ?: false

    fun matches(
        uri: URI,
        transport: String,
        nowEpochSeconds: Long = Instant.now().epochSecond,
    ): Boolean {
        if (isExpired(nowEpochSeconds) || value.isBlank()) return false
        if (transportScope != "shared" && transportScope != transport) return false
        if (secure && !uri.scheme.equals("https", true) && !uri.scheme.equals("wss", true)) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        val normalizedDomain = domain.trimStart('.').lowercase(Locale.ROOT)
        if (hostOnly && host != normalizedDomain) return false
        if (!hostOnly && host != normalizedDomain && !host.endsWith(".$normalizedDomain")) return false
        val requestPath = uri.path?.ifEmpty { "/" } ?: "/"
        return requestPath.startsWith(path.ifEmpty { "/" })
    }

    fun key(): String = listOf(name.lowercase(Locale.ROOT), domain.lowercase(Locale.ROOT), path, transportScope).joinToString("\u0000")
}

data class ClientProfileState(
    var transport: String = "configured",
    var uaType: String = "Generic",
    var userAgent: String = "",
    var browserProduct: String = "",
    var browserVersion: String = "",
    var platform: String = "",
    var acceptLanguage: String = "zh-CN,zh;q=0.8,en;q=0.7",
    var clientHints: MutableMap<String, String> = linkedMapOf(),
    var proxyProfileId: String = "direct",
    var observedAtEpochMillis: Long = 0,
)

data class BrowserPersistenceState(
    var profileDirectory: String = "./config/BrowserProfile",
    var installedVersion: String = "",
    var installedRevision: String = "",
    var executablePath: String = "",
    var platform: String = "",
    var cookieHash: String = "",
    var lastSynchronizedAtEpochMillis: Long = 0,
    var lastCredentialAuditAtEpochMillis: Long = 0,
    var lastCredentialAuditStatus: String = "UNKNOWN",
    var lastCredentialAuditFingerprint: String = "",
)

/** Crash-recoverable Web Credential refresh transaction. */
data class PendingCredentialRefresh(
    var transactionId: String = "",
    var phase: String = "COOKIE_REFRESHED",
    var oldRefreshToken: String = "",
    var candidate: Cookies = Cookies(),
    var responseCookies: MutableList<StoredCookie> = mutableListOf(),
    var startedAtEpochMillis: Long = 0,
    var lastAttemptAtEpochMillis: Long = 0,
    var lastError: String = "",
)

data class IdentityProbePersistence(
    var status: String = "UNKNOWN",
    var checkedAtEpochMillis: Long = 0,
    var jvmAddress: String = "",
    var browserAddress: String = "",
    var country: String = "",
    var province: String = "",
    var isp: String = "",
    var reason: String = "",
)

/** Alternate names are consumed by fastjson when importing external flat files. */
data class CredentialFileHeader(
    @param:JSONField(alternateNames = ["schema_version"])
    var schemaVersion: Int = 0,
)
