package com.starlwr.bot.bilibili.credential

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.bilibili.log.BilibiliNetworkLogger
import com.starlwr.bot.bilibili.http.BilibiliHttpProperties
import com.starlwr.bot.bilibili.http.configuredProxySelector
import com.starlwr.bot.bilibili.model.Cookies
import com.starlwr.bot.core.plugin.StarBotComponent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

@ConfigurationProperties("starbot.bilibili.account")
class BilibiliCredentialProperties {
    var loginOnStartup: Boolean = true
    var credentialFile: String = "./config/bilibili-credential.json"
    var legacyCookieFile: String = "./cookies.json"
    var validateCredential: Boolean = true
    var refreshCredential: Boolean = true
    var validationMode: String = "both"
    var validationLeaseSeconds: Long = 180
    var refreshWindowLeaseSeconds: Long = 180
    var refreshAtLifecycleRatio: Double = 0.25
    var externalCredentialInitialRefresh: Boolean = true
    var maintenanceIntervalMillis: Long = 30_000
    var validationRetryBaseSeconds: Long = 60
    var validationRetryMaxSeconds: Long = 900
    var refreshWindowRetryBaseSeconds: Long = 60
    var refreshWindowRetryMaxSeconds: Long = 900
    var refreshRetryBaseSeconds: Long = 300
    var refreshRetryMaxSeconds: Long = 21_600
    var qrPollMillis: Long = 3_000
    var qrRegenerateOnExpiry: Boolean = true
    var connectTimeoutSeconds: Long = 10
    var requestTimeoutSeconds: Long = 30
    var userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/138 Safari/537.36"
    var uaType: String = "Generic"
    var chromeUserAgent: String? = null
    var browserValidationApiKey: String? = null
}

data class QrCodeSession(val url: String, val key: String)
enum class QrCodeState { WAIT_SCAN, WAIT_CONFIRM, EXPIRED, DONE, ERROR }
data class QrCodePollResult(val state: QrCodeState, val credential: Cookies? = null, val message: String? = null)
internal data class CredentialRefreshWindow(val refresh: Boolean, val timestampMillis: Long)
internal enum class CredentialMaintenanceStage(val label: String) {
    VALIDATION("有效性校验"),
    REFRESH_WINDOW("服务器刷新窗口探针"),
    REFRESH("实际刷新"),
}

internal class CredentialMaintenanceException(
    val stage: CredentialMaintenanceStage,
    val retrySeconds: Long,
    cause: Throwable,
) : RuntimeException("Credential ${stage.label}失败，${retrySeconds} 秒后重试", cause)

internal data class CredentialValidationResult(
    val valid: Boolean,
    val navValid: Boolean,
    val cookieInfoValid: Boolean,
    val refreshWindow: CredentialRefreshWindow?,
    val observedUid: String? = null,
)

/** Full web Credential lifecycle ported from bilibili-api-python. */
@StarBotComponent
@EnableConfigurationProperties(BilibiliCredentialProperties::class)
class BilibiliCredentialService @Autowired constructor(
    private val properties: BilibiliCredentialProperties,
    private val browserIdentity: BilibiliBrowserIdentity,
    private val networkLog: BilibiliNetworkLogger,
    private val fileStore: BilibiliCredentialFileStore,
    private val httpProperties: BilibiliHttpProperties,
) {
    constructor(
        properties: BilibiliCredentialProperties,
        browserIdentity: BilibiliBrowserIdentity,
        networkLog: BilibiliNetworkLogger,
    ) : this(
        properties, browserIdentity, networkLog, BilibiliCredentialFileStore(properties),
        BilibiliHttpProperties(),
    )

    private val log = LoggerFactory.getLogger(javaClass)
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .apply { configuredProxySelector(httpProperties.proxyUri)?.let(::proxy) }
        .build()
    private val lock = Any()

    fun getProperties(): BilibiliCredentialProperties = properties
    fun credentialPath(): Path = fileStore.path()

    fun load(): Cookies? = synchronized(lock) {
        fileStore.load()
        val credential = resumePendingRefresh() ?: fileStore.loadCookies() ?: return null
        normalize(credential)
        if (!credential.hasLoginCredential()) return null
        credential
    }

    fun save(credential: Cookies) = synchronized(lock) {
        normalize(credential)
        fileStore.saveCookies(credential)
    }

    fun checkValid(credential: Cookies): Boolean {
        if (!properties.validateCredential) {
            log.info("跳过 Bilibili Credential 有效性校验: starbot.bilibili.account.validate-credential=false")
            return true
        }
        return validateCredential(credential).valid
    }

    private fun validateCredential(
        credential: Cookies,
        persistResponseCookies: Boolean = true,
    ): CredentialValidationResult {
        val mode = properties.validationMode.trim().lowercase()
        require(mode in setOf("nav", "cookie-info", "cookie_info", "both")) {
            "validation-mode must be nav, cookie-info, or both"
        }
        var observedUid: String? = null
        val navValid = if (mode in setOf("nav", "both")) {
            val json = parseJson(request("GET", NAV_URL, credential, persistResponseCookies = persistResponseCookies))
            val data = json.getJSONObject("data")
            observedUid = data?.get("mid")?.toString()
            json.getIntValue("code", -1) == 0 && data?.getBooleanValue("isLogin") == true
        } else true
        var refreshWindow: CredentialRefreshWindow? = null
        val cookieInfoValid = if (mode in setOf("cookie-info", "cookie_info", "both")) {
            runCatching {
                refreshWindow = parseRefreshWindow(
                    requireSuccess(
                        request("GET", COOKIE_INFO_URL, credential, persistResponseCookies = persistResponseCookies),
                        "validate Credential"
                    )
                )
            }.isSuccess
        } else true
        val valid = navValid && cookieInfoValid
        log.info(
            "Bilibili Credential 有效性校验完成: mode={}, navValid={}, cookieInfoValid={}, valid={}, " +
                "serverRefresh={}, serverTimestamp={}",
            mode, navValid, cookieInfoValid, valid, refreshWindow?.refresh ?: "unknown",
            refreshWindow?.timestampMillis ?: "unknown"
        )
        return CredentialValidationResult(valid, navValid, cookieInfoValid, refreshWindow, observedUid)
    }

    fun validateBrowserCandidate(candidate: Cookies, expectedUid: String): Boolean = synchronized(lock) {
        if (candidate.dedeUserId != expectedUid) return false

        // Browser promotion is a stronger trust boundary than routine maintenance.
        // Always execute both probes even when the operator has configured the
        // normal Credential validator to use only one endpoint.
        val nav = parseJson(request("GET", NAV_URL, candidate, persistResponseCookies = false))
        val navData = nav.getJSONObject("data")
        val observedUid = navData?.get("mid")?.toString()
        val navValid = nav.getIntValue("code", -1) == 0 &&
            navData?.getBooleanValue("isLogin") == true && observedUid == expectedUid
        val cookieInfoValid = runCatching {
            requireSuccess(
                request("GET", COOKIE_INFO_URL, candidate, persistResponseCookies = false),
                "validate browser Credential candidate",
            )
        }.isSuccess
        log.info(
            "浏览器 Credential 候选强校验完成: navValid={}, cookieInfoValid={}, expectedUid={}, observedUid={}",
            navValid, cookieInfoValid, expectedUid, observedUid,
        )
        navValid && cookieInfoValid
    }

    fun promoteBrowserCandidate(candidate: Cookies, expectedUid: String, expiresAtEpochSeconds: Long?): Boolean = synchronized(lock) {
        if (candidate.dedeUserId != expectedUid || candidate.acTimeValue.isBlank()) return false
        if (!validateBrowserCandidate(candidate, expectedUid)) return false
        applyLifecycle(candidate, expiresAtEpochSeconds)
        clearServerRefreshState(candidate)
        save(candidate)
        log.warn("已原子晋升通过校验的浏览器 Credential 候选: uid={}, expiresAt={}",
            expectedUid, describeEpoch(candidate.expiresAtEpochSeconds))
        true
    }

    fun validateAndUpdateLease(credential: Cookies): Boolean = synchronized(lock) {
        if (!properties.validateCredential) {
            log.info("不重置 Credential 校验租约: 有效性校验已禁用")
            return true
        }
        val result = validateCredential(credential)
        if (result.valid) {
            val repairedLegacyExpiry = repairLegacyCredentialExpiry(credential)
            val pendingInitialRefresh = credential.nextRefreshAtEpochSeconds <= 0 &&
                properties.externalCredentialInitialRefresh && credential.acTimeValue.isNotBlank()
            val refreshWindow = result.refreshWindow
            refreshWindow?.let { recordRefreshWindow(credential, it) }
            renewValidationLease(credential)
            clearMaintenanceFailure(credential, CredentialMaintenanceStage.VALIDATION, "有效性校验成功")
            if (refreshWindow != null) {
                clearMaintenanceFailure(
                    credential,
                    CredentialMaintenanceStage.REFRESH_WINDOW,
                    "有效性校验已取得服务器刷新窗口",
                )
                reconcileRefreshFailureWithServerWindow(credential)
            }
            if (!repairedLegacyExpiry || !fileStore.saveValidatedExpiryRepair(credential)) save(credential)
            log.info(
                "Credential 校验租约已重置: validationLeaseExpiresAt={}, credentialExpiresAt={}, " +
                    "nextRefreshAt={}, pendingInitialRefresh={}, serverRefresh={}",
                describeEpoch(credential.validationLeaseExpiresAtEpochSeconds),
                describeEpoch(credential.expiresAtEpochSeconds), describeEpoch(credential.nextRefreshAtEpochSeconds),
                pendingInitialRefresh, describeServerRefresh(credential)
            )
        } else {
            log.info("Credential 校验失败: 保留现有凭据，不重置租约")
        }
        result.valid
    }

    fun checkRefresh(credential: Cookies): Boolean = synchronized(lock) {
        val window = checkRefreshWindow(credential)
        recordRefreshWindow(credential, window)
        save(credential)
        window.refresh
    }

    internal fun checkRefreshWindow(credential: Cookies): CredentialRefreshWindow {
        val data = requireSuccess(request("GET", COOKIE_INFO_URL, credential), "check Credential refresh")
        return parseRefreshWindow(data).also { window ->
            log.info("Bilibili Credential 刷新窗口检查完成: serverRefresh={}, serverTimestamp={}",
                window.refresh, window.timestampMillis)
        }
    }

    internal fun parseRefreshWindow(data: JSONObject): CredentialRefreshWindow {
        val timestamp = data.getLongValue("timestamp")
        require(timestamp > 0) { "Bilibili cookie/info response omitted server timestamp" }
        return CredentialRefreshWindow(data.getBooleanValue("refresh"), timestamp)
    }

    fun refreshIfNeeded(credential: Cookies, force: Boolean = false): Cookies = synchronized(lock) {
        refreshIfNeeded(credential, force, storedRefreshWindow(credential))
    }

    private fun refreshIfNeeded(
        credential: Cookies,
        force: Boolean,
        probedWindow: CredentialRefreshWindow?,
    ): Cookies {
        if (!properties.refreshCredential) {
            log.info("跳过 Bilibili Credential 刷新: starbot.bilibili.account.refresh-credential=false")
            return credential
        }
        if (credential.acTimeValue.isBlank()) {
            log.info("跳过 Bilibili Credential 刷新: 凭据没有 ac_time_value/refresh_token，无法刷新")
            return credential
        }
        val now = System.currentTimeMillis() / 1000
        val imported = credential.nextRefreshAtEpochSeconds <= 0
        val lifecycleDue = credential.nextRefreshAtEpochSeconds in 1..now
        val initialRefresh = imported && properties.externalCredentialInitialRefresh && credential.acTimeValue.isNotBlank()
        val reason = when {
            force -> "调用方强制刷新"
            probedWindow?.refresh == true -> "Bilibili 服务器探针要求刷新"
            lifecycleDue -> "Credential 生命周期已到刷新点 ${describeEpoch(credential.nextRefreshAtEpochSeconds)}"
            initialRefresh -> "外部导入 Credential 尚未建立生命周期，配置要求首次尝试刷新"
            else -> null
        }
        if (reason == null) {
            log.info(
                "跳过 Bilibili Credential 刷新: 本地生命周期尚未到刷新点且服务器未要求刷新, " +
                    "now={}, nextRefreshAt={}, serverRefresh={}, serverRefreshCheckedAt={}",
                describeEpoch(now), describeEpoch(credential.nextRefreshAtEpochSeconds),
                describeServerRefresh(credential), describeEpoch(credential.serverRefreshCheckedAtEpochSeconds)
            )
            return credential
        }
        log.info("准备刷新 Bilibili Credential: reason={}, cachedServerRefresh={}", reason, describeServerRefresh(credential))
        val refreshWindow = probedWindow ?: checkRefreshWindow(credential).also {
            recordRefreshWindow(credential, it)
            save(credential)
        }
        if (!refreshWindow.refresh) {
            // A caller cannot bypass the server refresh gate: current Web only creates
            // the correspond iframe after cookie/info returns refresh=true.
            recordRefreshWindow(credential, refreshWindow)
            save(credential)
            log.info(
                "跳过实际 Credential 刷新: serverRefresh=false; 本地生命周期信号保留但不绕过服务器刷新窗口; " +
                    "serverRefreshWindowExpiresAt={}, nextRefreshAt={}",
                describeEpoch(credential.serverRefreshWindowExpiresAtEpochSeconds),
                describeEpoch(credential.nextRefreshAtEpochSeconds)
            )
            return credential
        }
        log.info("开始刷新 Bilibili Credential: reason={}, force={}, serverTimestamp={}",
            reason, force, refreshWindow.timestampMillis)
        return refresh(credential, refreshWindow.timestampMillis)
    }

    fun maintain(credential: Cookies): Cookies = synchronized(lock) {
        val now = System.currentTimeMillis() / 1000
        if (fileStore.pendingRefresh() != null) {
            if (isMaintenanceBackoffActive(credential, CredentialMaintenanceStage.REFRESH, now)) {
                log.info(
                    "跳过未完成的 Credential 刷新事务恢复: 实际刷新退避中, retryAfter={}",
                    describeEpoch(credential.refreshRetryAfterEpochSeconds),
                )
                return credential
            }
            return try {
                resumePendingRefresh(credential, propagateFailure = true) ?: credential
            } catch (error: Exception) {
                throw recordMaintenanceFailure(
                    credential,
                    CredentialMaintenanceStage.REFRESH,
                    error,
                    now,
                )
            }
        }

        var refreshWindow = storedRefreshWindow(credential, now)
        val validationDue = (credential.validationLeaseExpiresAtEpochSeconds ?: 0L) <= now
        if (validationDue && properties.validateCredential) {
            if (isMaintenanceBackoffActive(credential, CredentialMaintenanceStage.VALIDATION, now)) {
                log.info(
                    "跳过本轮 Credential 有效性校验: 该阶段退避中, retryAfter={}, lastFailure={}",
                    describeEpoch(credential.validationRetryAfterEpochSeconds),
                    credential.validationLastFailureReason,
                )
            } else {
                log.info(
                    "开始 Credential 有效性校验: 校验租约已到期, now={}, validationLeaseExpiresAt={}",
                    describeEpoch(now), describeEpoch(credential.validationLeaseExpiresAtEpochSeconds)
                )
                try {
                    check(validateAndUpdateLease(credential)) { "Bilibili Credential is no longer valid" }
                    refreshWindow = storedRefreshWindow(credential, now)
                } catch (error: Exception) {
                    throw recordMaintenanceFailure(
                        credential,
                        CredentialMaintenanceStage.VALIDATION,
                        error,
                        now,
                    )
                }
            }
        } else if (validationDue) {
            log.info("跳过本轮 Credential 有效性校验: starbot.bilibili.account.validate-credential=false")
        } else {
            log.info(
                "跳过本轮 Credential 有效性校验: 校验租约仍有效, now={}, validationLeaseExpiresAt={}",
                describeEpoch(now), describeEpoch(credential.validationLeaseExpiresAtEpochSeconds)
            )
        }

        if (refreshWindow == null && properties.refreshCredential && credential.acTimeValue.isNotBlank()) {
            if (isMaintenanceBackoffActive(credential, CredentialMaintenanceStage.REFRESH_WINDOW, now)) {
                log.info(
                    "跳过本轮 Credential 服务器刷新窗口探针: 该阶段退避中, retryAfter={}, lastFailure={}",
                    describeEpoch(credential.refreshWindowRetryAfterEpochSeconds),
                    credential.refreshWindowLastFailureReason,
                )
                return credential
            }
            log.info(
                "开始 Credential 服务器刷新窗口探针: now={}, serverRefreshWindowExpiresAt={}",
                describeEpoch(now), describeEpoch(credential.serverRefreshWindowExpiresAtEpochSeconds)
            )
            try {
                val probedWindow = checkRefreshWindow(credential)
                refreshWindow = probedWindow
                recordRefreshWindow(credential, probedWindow)
                clearMaintenanceFailure(
                    credential,
                    CredentialMaintenanceStage.REFRESH_WINDOW,
                    "服务器刷新窗口探针成功",
                )
                reconcileRefreshFailureWithServerWindow(credential)
                save(credential)
            } catch (error: Exception) {
                throw recordMaintenanceFailure(
                    credential,
                    CredentialMaintenanceStage.REFRESH_WINDOW,
                    error,
                    now,
                )
            }
        } else if (!properties.refreshCredential || credential.acTimeValue.isBlank()) {
            log.info("跳过本轮 Credential 服务器刷新窗口探针: refresh-credential=false 或缺少 refresh_token")
        } else {
            log.info(
                "跳过本轮 Credential 服务器刷新窗口探针: 窗口租约仍有效, now={}, " +
                    "serverRefresh={}, serverRefreshCheckedAt={}, serverRefreshWindowExpiresAt={}",
                describeEpoch(now), describeServerRefresh(credential),
                describeEpoch(credential.serverRefreshCheckedAtEpochSeconds),
                describeEpoch(credential.serverRefreshWindowExpiresAtEpochSeconds)
            )
        }
        if (refreshWindow?.refresh == false && reconcileRefreshFailureWithServerWindow(credential)) {
            save(credential)
        }
        if (refreshWindow?.refresh == true &&
            isMaintenanceBackoffActive(credential, CredentialMaintenanceStage.REFRESH, now)
        ) {
            log.info(
                "跳过本轮 Credential 实际刷新: 该阶段退避中, retryAfter={}, serverRefresh=true, lastFailure={}",
                describeEpoch(credential.refreshRetryAfterEpochSeconds), credential.refreshLastFailureReason,
            )
            return credential
        }
        try {
            refreshIfNeeded(credential, false, refreshWindow)
        } catch (error: Exception) {
            throw recordMaintenanceFailure(
                credential,
                CredentialMaintenanceStage.REFRESH,
                error,
                now,
            )
        }
    }

    internal fun recordMaintenanceFailure(
        credential: Cookies,
        stage: CredentialMaintenanceStage,
        error: Throwable,
        now: Long = System.currentTimeMillis() / 1000,
    ): CredentialMaintenanceException = synchronized(lock) {
        val failures = (maintenanceFailureCount(credential, stage) + 1).coerceAtMost(30)
        val multiplier = 1L shl (failures - 1).coerceAtMost(20)
        val (baseSeconds, maxSeconds) = maintenanceRetryPolicy(stage)
        val delay = (baseSeconds.coerceAtLeast(60) * multiplier)
            .coerceAtMost(maxSeconds.coerceAtLeast(60))
        val reason = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(MAX_FAILURE_REASON_LENGTH)
        setMaintenanceFailure(credential, stage, failures, now + delay, now, reason)
        save(credential)
        if (stage == CredentialMaintenanceStage.REFRESH && fileStore.pendingRefresh() != null) {
            fileStore.updatePendingRefresh { pending ->
                if (sameCredentialGeneration(credential, pending.candidate)) {
                    copyMaintenanceState(credential, pending.candidate)
                }
            }
        }
        log.warn(
            "Credential {}失败已进入独立退避: failures={}, delaySeconds={}, retryAfter={}, reason={}",
            stage.label, failures, delay, describeEpoch(maintenanceRetryAfter(credential, stage)), reason,
        )
        CredentialMaintenanceException(stage, delay, error)
    }

    fun refresh(old: Cookies): Cookies = synchronized(lock) {
        val refreshWindow = checkRefreshWindow(old)
        recordRefreshWindow(old, refreshWindow)
        save(old)
        require(refreshWindow.refresh) { "Bilibili does not currently permit Credential refresh" }
        refresh(old, refreshWindow.timestampMillis)
    }

    private fun refresh(old: Cookies, serverTimestampMillis: Long): Cookies {
        require(old.biliJct.isNotBlank()) { "bili_jct is required to refresh Credential" }
        require(old.acTimeValue.isNotBlank()) { "ac_time_value/refresh_token is required to refresh Credential" }
        val form = linkedMapOf(
            "csrf" to old.biliJct,
            "refresh_csrf" to getRefreshCsrf(old, serverTimestampMillis),
            "refresh_token" to old.acTimeValue,
            "source" to "main_web"
        )
        val response = request("POST", COOKIE_REFRESH_URL, old, form, persistResponseCookies = false)
        val data = requireSuccess(response, "refresh Credential")
        val responseCookies = parseSetCookies(response.headers().allValues("set-cookie"))
        val storedCookies = parseStoredCookies(
            response.headers().allValues("set-cookie"), URI.create(COOKIE_REFRESH_URL), "jvm"
        ).toMutableList()
        val rawSessDataExpiry = storedCookies.firstOrNull { it.name.equals("SESSDATA", true) }
            ?.expiresAtEpochSeconds
        val trustedSessDataExpiry = rawSessDataExpiry?.takeIf(::isPlausibleCredentialExpiry)
        if (rawSessDataExpiry != null && trustedSessDataExpiry == null) {
            log.warn(
                "Credential 刷新响应中的 SESSDATA 到期时间未通过可信边界校验，将作为会话 Cookie 保存: expiresAt={}",
                describeEpoch(rawSessDataExpiry),
            )
        }
        storedCookies.filter { it.name.equals("SESSDATA", true) }
            .forEach { it.expiresAtEpochSeconds = trustedSessDataExpiry }
        val refreshed = copyCredential(old).apply {
            sessData = responseCookies["SESSDATA"] ?: error("Credential refresh response omitted SESSDATA")
            biliJct = responseCookies["bili_jct"] ?: error("Credential refresh response omitted bili_jct")
            dedeUserId = responseCookies["DedeUserID"] ?: dedeUserId
            acTimeValue = data.getString("refresh_token") ?: error("Credential refresh response omitted refresh_token")
            responseCookies["buvid3"]?.let { buvid3 = it }
            responseCookies["buvid4"]?.let { buvid4 = it }
            responseCookies["b_nut"]?.let { bNut = it }
            extraCookies.putAll(responseCookies.filterKeys { it !in KNOWN_COOKIES })
        }
        applyLifecycle(refreshed, trustedSessDataExpiry)
        clearServerRefreshState(refreshed)
        val pending = PendingCredentialRefresh(
            transactionId = UUID.randomUUID().toString(),
            oldRefreshToken = old.acTimeValue,
            candidate = copyCredential(refreshed),
            responseCookies = storedCookies,
            startedAtEpochMillis = System.currentTimeMillis(),
        )
        fileStore.stagePendingRefresh(pending)
        finalizePendingRefresh(pending)
        log.info("Bilibili Credential 刷新成功且旧 refresh token 已确认: issuedAt={}, expiresAt={}, nextRefreshAt={}, serverRefresh=unknown（等待下一轮探针）",
            describeEpoch(refreshed.issuedAtEpochSeconds), describeEpoch(refreshed.expiresAtEpochSeconds),
            describeEpoch(refreshed.nextRefreshAtEpochSeconds))
        return refreshed
    }

    private fun resumePendingRefresh(
        currentCredential: Cookies? = null,
        propagateFailure: Boolean = false,
    ): Cookies? {
        val pending = fileStore.pendingRefresh() ?: return null
        return runCatching {
            log.warn("检测到未完成的 Credential 刷新事务，开始恢复: transactionId={}, phase={}",
                pending.transactionId, pending.phase)
            finalizePendingRefresh(pending)
            if (clearMaintenanceFailure(
                    pending.candidate,
                    CredentialMaintenanceStage.REFRESH,
                    "未完成的刷新事务已成功恢复",
                )
            ) {
                save(pending.candidate)
            }
            pending.candidate
        }.getOrElse { error ->
            fileStore.updatePendingRefresh {
                it.lastAttemptAtEpochMillis = System.currentTimeMillis()
                it.lastError = error.toString()
            }
            log.warn("Credential 刷新事务恢复尚未完成，将保留新候选并稍后重试: transactionId={}, error={}",
                pending.transactionId, error.toString())
            if (propagateFailure) throw error
            currentCredential?.takeIf { sameCredentialGeneration(it, pending.candidate) } ?: pending.candidate
        }
    }

    private fun sameCredentialGeneration(first: Cookies, second: Cookies): Boolean =
        first.sessData == second.sessData && first.biliJct == second.biliJct &&
            first.dedeUserId == second.dedeUserId && first.acTimeValue == second.acTimeValue

    private fun finalizePendingRefresh(pending: PendingCredentialRefresh) {
        val candidate = pending.candidate
        val collected = Collections.synchronizedList(pending.responseCookies.toMutableList())
        if (pending.phase != "CONFIRMED") {
            val ssoFuture = CompletableFuture.runAsync { performSso(copyCredential(candidate), collected) }
            val confirm = linkedMapOf("csrf" to candidate.biliJct, "refresh_token" to pending.oldRefreshToken)
            val response = try {
                request("POST", CONFIRM_REFRESH_URL, candidate, confirm, persistResponseCookies = false)
            } finally {
                ssoFuture.join()
            }
            requireSuccess(response, "confirm Credential refresh")
            collected += parseStoredCookies(response.headers().allValues("set-cookie"), URI.create(CONFIRM_REFRESH_URL), "jvm")
            applySetCookieValues(candidate, response.headers().allValues("set-cookie"))
            pending.phase = "CONFIRMED"
            pending.lastAttemptAtEpochMillis = System.currentTimeMillis()
            pending.responseCookies = collected.toMutableList()
            fileStore.updatePendingRefresh {
                it.phase = pending.phase
                it.lastAttemptAtEpochMillis = pending.lastAttemptAtEpochMillis
                it.responseCookies = pending.responseCookies
                it.candidate = copyCredential(candidate)
                it.lastError = ""
            }
        }
        fileStore.completePendingRefresh(candidate, collected)
    }

    private fun performSso(candidate: Cookies, collected: MutableList<StoredCookie>) {
        runCatching {
            val url = "$SSO_LIST_URL?biliCSRF=${encode(candidate.biliJct)}"
            val data = requireSuccess(
                request("GET", url, candidate, persistResponseCookies = false),
                "get Credential SSO list"
            )
            val urls = data.getJSONArray("sso")?.toJavaList(String::class.java).orEmpty()
                .map { if (it.startsWith("//")) "https:$it" else it }
                .map { if (it.startsWith("http://")) "https://${it.removePrefix("http://")}" else it }
                .filter { it.startsWith("https://") }
                .filter { URI.create(it).host?.let { host -> host == "bilibili.com" || host.endsWith(".bilibili.com") } == true }
                .distinct()
                .take(MAX_SSO_TARGETS)
            val futures = urls.map { target ->
                CompletableFuture.runAsync {
                    val response = request("POST", target, candidate, emptyMap(), persistResponseCookies = false)
                    if (response.statusCode() in 200..399) {
                        val headers = response.headers().allValues("set-cookie")
                        collected += parseStoredCookies(headers, URI.create(target), "jvm")
                    } else {
                        log.warn("Bilibili Credential SSO 域同步返回异常: host={}, status={}", URI.create(target).host, response.statusCode())
                    }
                }
            }
            CompletableFuture.allOf(*futures.toTypedArray()).join()
            log.info("Bilibili Credential SSO 域同步完成: targets={}", urls.size)
        }.onFailure { error ->
            // Current Web treats SSO fan-out as best effort and confirms the old token independently.
            log.warn("Bilibili Credential SSO 域同步未完整完成，继续确认旧 refresh token: {}", error.toString())
            log.debug("Credential SSO synchronization detail", error)
        }
    }

    fun generateQrCode(): QrCodeSession {
        val data = requireSuccess(request("GET", QR_GENERATE_URL), "generate QR login")
        return QrCodeSession(data.getString("url") ?: error("QR response omitted url"), data.getString("qrcode_key") ?: error("QR response omitted key"))
    }

    fun pollQrCode(session: QrCodeSession): QrCodePollResult {
        val response = request("GET", "$QR_POLL_URL?qrcode_key=${encode(session.key)}")
        val outer = parseJson(response)
        if (response.statusCode() != 200 || outer.getIntValue("code", -1) != 0)
            return QrCodePollResult(QrCodeState.ERROR, message = outer.getString("message"))
        val data = outer.getJSONObject("data") ?: return QrCodePollResult(QrCodeState.ERROR, message = "missing data")
        return when (val code = data.getIntValue("code")) {
            86101 -> QrCodePollResult(QrCodeState.WAIT_SCAN)
            86090 -> QrCodePollResult(QrCodeState.WAIT_CONFIRM)
            86038 -> QrCodePollResult(QrCodeState.EXPIRED)
            0 -> completeQrLogin(data, response.headers().allValues("set-cookie"))
            else -> QrCodePollResult(QrCodeState.ERROR, message = data.getString("message") ?: "QR status $code")
        }
    }

    private fun completeQrLogin(data: JSONObject, setCookieHeaders: List<String>): QrCodePollResult {
        val query = parseQuery(data.getString("url") ?: "")
        val responseCookies = parseSetCookies(setCookieHeaders)
        val storedCookies = parseStoredCookies(
            setCookieHeaders,
            URI.create(QR_POLL_URL),
            "shared",
            "qr-login",
        ).toMutableList()
        val buvid = fetchBuvid()
        val credential = Cookies().apply {
            sessData = responseCookies["SESSDATA"] ?: query["SESSDATA"].orEmpty()
            biliJct = responseCookies["bili_jct"] ?: query["bili_jct"].orEmpty()
            dedeUserId = responseCookies["DedeUserID"] ?: query["DedeUserID"].orEmpty()
            acTimeValue = data.getString("refresh_token").orEmpty()
            buvid3 = responseCookies["buvid3"] ?: query["buvid3"] ?: buvid.first
            buvid4 = responseCookies["buvid4"] ?: query["buvid4"] ?: buvid.second
            bNut = responseCookies["b_nut"] ?: query["b_nut"].orEmpty()
            extraCookies.putAll(query.filterKeys { it !in KNOWN_COOKIES })
            extraCookies.putAll(responseCookies.filterKeys { it !in KNOWN_COOKIES })
        }
        if (!credential.hasRefreshableCredential())
            return QrCodePollResult(QrCodeState.ERROR, message = "QR login returned an incomplete Credential")
        val setCookieExpiry = storedCookies.firstOrNull { it.name.equals("SESSDATA", true) }
            ?.expiresAtEpochSeconds
        val redirectExpiry = query["Expires"]?.toLongOrNull()
        val expiry = selectCredentialExpiry(storedCookies, query)
        storedCookies.filter { it.name.equals("SESSDATA", true) && it.value == credential.sessData }
            .forEach { it.expiresAtEpochSeconds = expiry }
        applyLifecycle(credential, expiry)
        fileStore.saveCookiesWithMetadata(credential, storedCookies)
        log.info(
            "二维码 Credential 已原子保存: credentialExpiresAt={}, validationLeaseExpiresAt={}, " +
                "setCookieCount={}, sessDataExpirySource={}",
            describeEpoch(credential.expiresAtEpochSeconds),
            describeEpoch(credential.validationLeaseExpiresAtEpochSeconds),
            storedCookies.size,
            when {
                setCookieExpiry == expiry && expiry != null -> "set-cookie"
                redirectExpiry == expiry && expiry != null -> "redirect-url"
                else -> "session-cookie"
            },
        )
        return QrCodePollResult(QrCodeState.DONE, credential)
    }

    fun fetchBuvid(): Pair<String, String> {
        val data = requireSuccess(request("GET", SPI_URL), "get buvid3/buvid4")
        return data.getString("b_3").orEmpty() to data.getString("b_4").orEmpty()
    }

    internal fun parseQuery(url: String): Map<String, String> {
        val raw = runCatching { URI.create(url).rawQuery }.getOrNull()
            ?: url.substringAfter('?', "").takeIf { it.isNotBlank() } ?: return emptyMap()
        return raw.split('&').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else decode(part.substring(0, index)) to decode(part.substring(index + 1))
        }.toMap(LinkedHashMap())
    }

    internal fun parseSetCookies(headers: List<String>): Map<String, String> = headers.mapNotNull { header ->
        val pair = header.substringBefore(';')
        val index = pair.indexOf('=')
        if (index <= 0) null else pair.substring(0, index).trim() to pair.substring(index + 1).trim()
    }.toMap(LinkedHashMap())

    internal fun parseStoredCookies(
        headers: List<String>,
        requestUri: URI,
        transport: String,
        source: String = "credential-refresh",
    ): List<StoredCookie> =
        headers.mapNotNull { header ->
            val parts = header.split(';').map { it.trim() }
            val first = parts.firstOrNull() ?: return@mapNotNull null
            val index = first.indexOf('=')
            if (index <= 0) return@mapNotNull null
            val name = first.substring(0, index).trim()
            if (name.equals("b_lsid", true) || name.equals("ac_time_value", true)) return@mapNotNull null
            var domain = requestUri.host ?: return@mapNotNull null
            var hostOnly = true
            var path = "/"
            var secure = false
            var httpOnly = false
            var sameSite: String? = null
            var expires: Long? = null
            parts.drop(1).forEach { attribute ->
                val key = attribute.substringBefore('=').trim().lowercase()
                val value = attribute.substringAfter('=', "").trim()
                when (key) {
                    "domain" -> if (value.isNotBlank()) {
                        domain = ".${value.trimStart('.')}"
                        hostOnly = false
                    }
                    "path" -> if (value.isNotBlank()) path = value
                    "secure" -> secure = true
                    "httponly" -> httpOnly = true
                    "samesite" -> sameSite = value.replaceFirstChar { it.uppercase() }
                    "max-age" -> value.toLongOrNull()?.let { expires = Instant.now().epochSecond + it }
                    "expires" -> if (expires == null) expires = runCatching {
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
                    }.getOrNull()
                }
            }
            val scope = when {
                name.equals("sid", true) -> transport
                name.equals("bili_ticket", true) || name.equals("bili_ticket_expires", true) -> transport
                else -> "shared"
            }
            StoredCookie(name, first.substring(index + 1), domain, path, hostOnly, secure, httpOnly,
                sameSite, expires, scope, source)
        }

    internal fun parseExpiry(headers: List<String>): Long? {
        return parseStoredCookies(headers, URI.create(COOKIE_REFRESH_URL), "shared")
            .firstOrNull { it.name.equals("SESSDATA", true) }
            ?.expiresAtEpochSeconds
    }

    internal fun selectCredentialExpiry(
        storedCookies: Collection<StoredCookie>,
        redirectQuery: Map<String, String>,
        now: Long = Instant.now().epochSecond,
    ): Long? {
        val setCookieExpiry = storedCookies.firstOrNull { it.name.equals("SESSDATA", true) }
            ?.expiresAtEpochSeconds
        val redirectExpiry = redirectQuery["Expires"]?.toLongOrNull()
        val selected = sequenceOf(setCookieExpiry, redirectExpiry)
            .filterNotNull()
            .firstOrNull { isPlausibleCredentialExpiry(it, now) }
        if (setCookieExpiry != null && redirectExpiry != null && setCookieExpiry != redirectExpiry) {
            log.warn(
                "二维码 Credential 到期信息不一致: setCookieExpiresAt={}, redirectExpiresAt={}, selected={}",
                describeEpoch(setCookieExpiry), describeEpoch(redirectExpiry), describeEpoch(selected),
            )
        }
        if (selected == null && (setCookieExpiry != null || redirectExpiry != null)) {
            log.warn(
                "二维码 Credential 到期信息未通过可信边界校验，将作为会话 Cookie 保存: setCookieExpiresAt={}, redirectExpiresAt={}",
                describeEpoch(setCookieExpiry), describeEpoch(redirectExpiry),
            )
        }
        return selected
    }

    internal fun isPlausibleCredentialExpiry(expiry: Long, now: Long = Instant.now().epochSecond): Boolean =
        expiry >= now + MIN_CREDENTIAL_LIFETIME_SECONDS && expiry <= now + MAX_CREDENTIAL_LIFETIME_SECONDS

    internal fun applyLifecycle(credential: Cookies, serverExpiry: Long?) {
        val now = System.currentTimeMillis() / 1000
        val expiry = serverExpiry?.takeIf { isPlausibleCredentialExpiry(it, now) }
        val ratio = properties.refreshAtLifecycleRatio.coerceIn(0.01, 0.95)
        credential.issuedAtEpochSeconds = now
        credential.expiresAtEpochSeconds = expiry ?: 0L
        credential.nextRefreshAtEpochSeconds = expiry?.let {
            now + ((it - now) * ratio).toLong().coerceAtLeast(1)
        } ?: 0L
        renewValidationLease(credential, now)
        clearMaintenanceFailureFields(credential, CredentialMaintenanceStage.VALIDATION)
        clearMaintenanceFailureFields(credential, CredentialMaintenanceStage.REFRESH_WINDOW)
        credential.refreshFailureCount = 0
        credential.refreshRetryAfterEpochSeconds = 0L
        credential.refreshLastFailureAtEpochSeconds = 0L
        credential.refreshLastFailureReason = ""
    }

    internal fun isMaintenanceBackoffActive(
        credential: Cookies,
        stage: CredentialMaintenanceStage,
        now: Long = System.currentTimeMillis() / 1000,
    ): Boolean = maintenanceRetryAfter(credential, stage) > now

    internal fun clearMaintenanceFailure(
        credential: Cookies,
        stage: CredentialMaintenanceStage,
        resolution: String,
    ): Boolean {
        val failures = maintenanceFailureCount(credential, stage)
        val retryAfter = maintenanceRetryAfter(credential, stage)
        if (failures <= 0 && retryAfter <= 0) return false
        clearMaintenanceFailureFields(credential, stage)
        log.info(
            "Credential {}故障状态已解除: previousFailures={}, previousRetryAfter={}, resolution={}",
            stage.label, failures, describeEpoch(retryAfter), resolution,
        )
        return true
    }

    internal fun reconcileRefreshFailureWithServerWindow(credential: Cookies): Boolean {
        if (credential.serverRefreshRequired != false) return false
        val checkedAt = credential.serverRefreshCheckedAtEpochSeconds ?: 0L
        val failedAt = credential.refreshLastFailureAtEpochSeconds ?: 0L
        if (checkedAt <= 0 || (failedAt > 0 && checkedAt < failedAt)) {
            log.info(
                "保留 Credential 实际刷新故障状态: refresh=false 观测早于最近失败, " +
                    "serverRefreshCheckedAt={}, refreshLastFailureAt={}",
                describeEpoch(checkedAt), describeEpoch(failedAt),
            )
            return false
        }
        return clearMaintenanceFailure(
            credential,
            CredentialMaintenanceStage.REFRESH,
            "服务器刷新窗口在最近失败之后明确为 refresh=false",
        )
    }

    private fun maintenanceFailureCount(credential: Cookies, stage: CredentialMaintenanceStage): Int =
        when (stage) {
            CredentialMaintenanceStage.VALIDATION -> credential.validationFailureCount ?: 0
            CredentialMaintenanceStage.REFRESH_WINDOW -> credential.refreshWindowFailureCount ?: 0
            CredentialMaintenanceStage.REFRESH -> credential.refreshFailureCount ?: 0
        }

    private fun maintenanceRetryAfter(credential: Cookies, stage: CredentialMaintenanceStage): Long =
        when (stage) {
            CredentialMaintenanceStage.VALIDATION -> credential.validationRetryAfterEpochSeconds ?: 0L
            CredentialMaintenanceStage.REFRESH_WINDOW -> credential.refreshWindowRetryAfterEpochSeconds ?: 0L
            CredentialMaintenanceStage.REFRESH -> credential.refreshRetryAfterEpochSeconds ?: 0L
        }

    private fun maintenanceRetryPolicy(stage: CredentialMaintenanceStage): Pair<Long, Long> =
        when (stage) {
            CredentialMaintenanceStage.VALIDATION ->
                properties.validationRetryBaseSeconds to properties.validationRetryMaxSeconds
            CredentialMaintenanceStage.REFRESH_WINDOW ->
                properties.refreshWindowRetryBaseSeconds to properties.refreshWindowRetryMaxSeconds
            CredentialMaintenanceStage.REFRESH ->
                properties.refreshRetryBaseSeconds to properties.refreshRetryMaxSeconds
        }

    private fun setMaintenanceFailure(
        credential: Cookies,
        stage: CredentialMaintenanceStage,
        failures: Int,
        retryAfter: Long,
        failedAt: Long,
        reason: String,
    ) {
        when (stage) {
            CredentialMaintenanceStage.VALIDATION -> {
                credential.validationFailureCount = failures
                credential.validationRetryAfterEpochSeconds = retryAfter
                credential.validationLastFailureAtEpochSeconds = failedAt
                credential.validationLastFailureReason = reason
            }
            CredentialMaintenanceStage.REFRESH_WINDOW -> {
                credential.refreshWindowFailureCount = failures
                credential.refreshWindowRetryAfterEpochSeconds = retryAfter
                credential.refreshWindowLastFailureAtEpochSeconds = failedAt
                credential.refreshWindowLastFailureReason = reason
            }
            CredentialMaintenanceStage.REFRESH -> {
                credential.refreshFailureCount = failures
                credential.refreshRetryAfterEpochSeconds = retryAfter
                credential.refreshLastFailureAtEpochSeconds = failedAt
                credential.refreshLastFailureReason = reason
            }
        }
    }

    private fun clearMaintenanceFailureFields(credential: Cookies, stage: CredentialMaintenanceStage) {
        setMaintenanceFailure(credential, stage, 0, 0L, 0L, "")
    }

    private fun copyMaintenanceState(source: Cookies, target: Cookies) {
        target.validationFailureCount = source.validationFailureCount
        target.validationRetryAfterEpochSeconds = source.validationRetryAfterEpochSeconds
        target.validationLastFailureAtEpochSeconds = source.validationLastFailureAtEpochSeconds
        target.validationLastFailureReason = source.validationLastFailureReason
        target.refreshWindowFailureCount = source.refreshWindowFailureCount
        target.refreshWindowRetryAfterEpochSeconds = source.refreshWindowRetryAfterEpochSeconds
        target.refreshWindowLastFailureAtEpochSeconds = source.refreshWindowLastFailureAtEpochSeconds
        target.refreshWindowLastFailureReason = source.refreshWindowLastFailureReason
        target.refreshFailureCount = source.refreshFailureCount
        target.refreshRetryAfterEpochSeconds = source.refreshRetryAfterEpochSeconds
        target.refreshLastFailureAtEpochSeconds = source.refreshLastFailureAtEpochSeconds
        target.refreshLastFailureReason = source.refreshLastFailureReason
    }

    private fun validationLeaseSeconds(): Long = properties.validationLeaseSeconds.coerceIn(60, 300)

    private fun renewValidationLease(credential: Cookies, now: Long = System.currentTimeMillis() / 1000) {
        credential.lastValidatedAtEpochSeconds = now
        credential.validationLeaseExpiresAtEpochSeconds = now + validationLeaseSeconds()
    }

    private fun recordRefreshWindow(
        credential: Cookies,
        window: CredentialRefreshWindow,
        checkedAt: Long = System.currentTimeMillis() / 1000,
    ) {
        credential.serverRefreshRequired = window.refresh
        credential.serverRefreshCheckedAtEpochSeconds = checkedAt
        credential.serverRefreshWindowExpiresAtEpochSeconds =
            checkedAt + properties.refreshWindowLeaseSeconds.coerceIn(60, 3600)
        credential.serverRefreshTimestampMillis = window.timestampMillis
    }

    private fun clearServerRefreshState(credential: Cookies) {
        credential.serverRefreshRequired = null
        credential.serverRefreshCheckedAtEpochSeconds = 0L
        credential.serverRefreshWindowExpiresAtEpochSeconds = 0L
        credential.serverRefreshTimestampMillis = 0L
    }

    private fun storedRefreshWindow(
        credential: Cookies,
        now: Long = System.currentTimeMillis() / 1000,
    ): CredentialRefreshWindow? {
        val checkedAt = credential.serverRefreshCheckedAtEpochSeconds ?: 0L
        val leaseExpiresAt = credential.serverRefreshWindowExpiresAtEpochSeconds ?: 0L
        val timestamp = credential.serverRefreshTimestampMillis ?: 0L
        val refresh = credential.serverRefreshRequired ?: return null
        if (checkedAt <= 0 || leaseExpiresAt <= now || timestamp <= 0) return null
        return CredentialRefreshWindow(refresh, timestamp)
    }

    internal fun repairLegacyCredentialExpiry(
        credential: Cookies,
        now: Long = Instant.now().epochSecond,
    ): Boolean {
        val currentExpiry = credential.expiresAtEpochSeconds ?: 0L
        val issuedAt = credential.issuedAtEpochSeconds ?: 0L
        val looksLikeValidationLease = issuedAt > 0 &&
            currentExpiry in 1..(issuedAt + validationLeaseSeconds() + LEGACY_EXPIRY_TOLERANCE_SECONDS)
        if (currentExpiry > now && !looksLikeValidationLease) return false

        val recoveredExpiry = credential.extraCookies.orEmpty()["Expires"]?.toLongOrNull()
            ?.takeIf { isPlausibleCredentialExpiry(it, now) }
            ?: return false
        credential.expiresAtEpochSeconds = recoveredExpiry
        val lifecycleStart = issuedAt.takeIf { it in 1 until recoveredExpiry } ?: now
        val ratio = properties.refreshAtLifecycleRatio.coerceIn(0.01, 0.95)
        credential.nextRefreshAtEpochSeconds = lifecycleStart +
            ((recoveredExpiry - lifecycleStart) * ratio).toLong().coerceAtLeast(1)
        log.warn(
            "已在 JVM 有效性校验通过后修复旧版 Credential 到期时间: oldExpiresAt={}, " +
                "recoveredExpiresAt={}, nextRefreshAt={}, source=extraCookies.Expires",
            describeEpoch(currentExpiry), describeEpoch(recoveredExpiry),
            describeEpoch(credential.nextRefreshAtEpochSeconds),
        )
        return true
    }

    private fun getRefreshCsrf(credential: Cookies, serverTimestampMillis: Long): String {
        // Current Web uses cookie/info.data.timestamp verbatim. A client timestamp,
        // even if only milliseconds newer, decrypts to a path the server rejects.
        val path = correspondPath(serverTimestampMillis)
        val response = request("GET", "https://www.bilibili.com/correspond/1/$path", credential)
        if (response.statusCode() != 200) {
            error("Credential correspondPath was rejected (HTTP ${response.statusCode()}, serverTimestamp=$serverTimestampMillis)")
        }
        return REFRESH_CSRF.find(response.body())?.groupValues?.get(1)
            ?: error("Credential refresh CSRF was absent from correspond response")
    }

    internal fun correspondPath(serverTimestampMillis: Long): String {
        require(serverTimestampMillis > 0) { "serverTimestampMillis must be positive" }
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode(CORRESPOND_PUBLIC_KEY)))
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        return cipher.doFinal("refresh_$serverTimestampMillis".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun request(
        method: String,
        url: String,
        credential: Cookies? = null,
        form: Map<String, String>? = null,
        randomizeBuvid3: Boolean = false,
        persistResponseCookies: Boolean = true,
    ): HttpResponse<String> {
        val headers = linkedMapOf(
            "Referer" to "https://www.bilibili.com",
            "Accept" to "application/json, text/plain, */*",
            "Accept-Encoding" to "gzip, deflate",
        )
        headers.putAll(browserIdentity.headers(properties.userAgent))
        if (credential != null) headers["Cookie"] = cookieHeader(credential, randomizeBuvid3)
        val body = if (method == "POST") form.orEmpty().entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" } else null
        if (method == "POST") headers["Content-Type"] = "application/x-www-form-urlencoded"
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(properties.requestTimeoutSeconds))
        headers.forEach(builder::header)
        if (method == "POST") {
            builder.POST(HttpRequest.BodyPublishers.ofString(body.orEmpty(), StandardCharsets.UTF_8))
        } else builder.GET()
        val trace = networkLog.httpRequest("bilibili-credential", method, url, headers, body)
        return try {
            client.send(builder.build(), decodedUtf8BodyHandler()).also { response ->
                networkLog.httpResponse(trace, response.statusCode(), response.headers().map(), response.body())
                if (credential != null && url != COOKIE_REFRESH_URL && persistResponseCookies) {
                    mergeResponseCookies(credential, response.headers().allValues("set-cookie"))
                }
            }
        } catch (error: Throwable) {
            networkLog.httpFailure(trace, error)
            throw error
        }
    }

    private fun decodedUtf8BodyHandler(): HttpResponse.BodyHandler<String> = HttpResponse.BodyHandler { info ->
        val encoding = info.headers().firstValue("content-encoding").orElse("").trim().lowercase()
        HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofByteArray()) { bytes ->
            decodeHttpBody(bytes, encoding)
        }
    }

    internal fun decodeHttpBody(bytes: ByteArray, encoding: String): String {
        val decoded = when (encoding.trim().lowercase()) {
            "gzip" -> GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readAllBytes() }
            "deflate" -> InflaterInputStream(ByteArrayInputStream(bytes)).use { it.readAllBytes() }
            else -> bytes
        }
        return String(decoded, StandardCharsets.UTF_8)
    }

    private fun mergeResponseCookies(credential: Cookies, headers: List<String>) {
        val changed = applySetCookieValues(credential, headers)
        if (changed.isEmpty()) return
        save(credential)
        log.info("已按 Bilibili Set-Cookie 更新 Credential: names={}", changed.sorted())
    }

    internal fun applySetCookieValues(credential: Cookies, headers: List<String>): Set<String> {
        val changed = linkedSetOf<String>()
        credential.extraCookies = credential.extraCookies ?: LinkedHashMap()
        parseSetCookies(headers).forEach { (name, value) ->
            fun update(old: String?, assign: (String) -> Unit) {
                if (old != value) {
                    assign(value)
                    changed += name
                }
            }
            when (name.lowercase()) {
                "sessdata" -> update(credential.sessData) { credential.sessData = it }
                "bili_jct" -> update(credential.biliJct) { credential.biliJct = it }
                "buvid3" -> update(credential.buvid3) { credential.buvid3 = it }
                "buvid4" -> update(credential.buvid4) { credential.buvid4 = it }
                "dedeuserid" -> update(credential.dedeUserId) { credential.dedeUserId = it }
                "ac_time_value" -> update(credential.acTimeValue) { credential.acTimeValue = it }
                "b_nut" -> update(credential.bNut) { credential.bNut = it }
                "bili_ticket" -> update(credential.biliTicket) { credential.biliTicket = it }
                "bili_ticket_expires" -> {
                    val parsed = value.toLongOrNull() ?: return@forEach
                    if (credential.biliTicketExpires != parsed) {
                        credential.biliTicketExpires = parsed
                        changed += name
                    }
                }
                else -> update(credential.extraCookies[name]) { credential.extraCookies[name] = it }
            }
        }
        return changed
    }

    private fun describeEpoch(value: Long?): String = value?.takeIf { it > 0 }
        ?.let { "${Instant.ofEpochSecond(it)}($it)" } ?: "unset"

    private fun describeServerRefresh(credential: Cookies): String =
        credential.serverRefreshRequired?.toString() ?: "unknown"

    private fun requireSuccess(response: HttpResponse<String>, operation: String): JSONObject {
        val json = parseJson(response)
        if (response.statusCode() != 200 || json.getIntValue("code", -1) != 0)
            error("Failed to $operation: HTTP ${response.statusCode()}, code=${json.getIntValue("code", -1)}, message=${json.getString("message")}")
        return json.getJSONObject("data") ?: json.getJSONObject("result") ?: JSONObject()
    }

    private fun parseJson(response: HttpResponse<String>): JSONObject = runCatching { JSON.parseObject(response.body()) }
        .getOrElse { error("Bilibili returned invalid JSON (HTTP ${response.statusCode()}): ${response.body().take(256)}") }

    private fun cookieHeader(credential: Cookies, randomizeBuvid3: Boolean): String {
        val values = linkedMapOf<String, String?>()
        values.putAll(credential.extraCookies.orEmpty())
        values.putAll(linkedMapOf(
            "SESSDATA" to credential.sessData, "bili_jct" to credential.biliJct,
            "buvid3" to if (randomizeBuvid3) UUID.randomUUID().toString() else credential.buvid3,
            "buvid4" to credential.buvid4, "DedeUserID" to credential.dedeUserId,
            "b_nut" to credential.bNut, "bili_ticket" to credential.biliTicket,
            "bili_ticket_expires" to credential.biliTicketExpires?.takeIf { it > 0 }?.toString()
        ))
        return values.filterValues { !it.isNullOrBlank() }.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun normalize(c: Cookies) {
        c.sessData = c.sessData.orEmpty(); c.biliJct = c.biliJct.orEmpty(); c.buvid3 = c.buvid3.orEmpty()
        c.buvid4 = c.buvid4.orEmpty(); c.dedeUserId = c.dedeUserId.orEmpty(); c.acTimeValue = c.acTimeValue.orEmpty()
        c.bNut = c.bNut.orEmpty(); c.biliTicket = c.biliTicket.orEmpty(); c.biliTicketExpires = c.biliTicketExpires ?: 0L
        c.issuedAtEpochSeconds = c.issuedAtEpochSeconds ?: 0L; c.expiresAtEpochSeconds = c.expiresAtEpochSeconds ?: 0L
        c.nextRefreshAtEpochSeconds = c.nextRefreshAtEpochSeconds ?: 0L; c.lastValidatedAtEpochSeconds = c.lastValidatedAtEpochSeconds ?: 0L
        c.validationLeaseExpiresAtEpochSeconds = c.validationLeaseExpiresAtEpochSeconds?.takeIf { it > 0 }
            ?: c.lastValidatedAtEpochSeconds.takeIf { it > 0 }?.plus(validationLeaseSeconds()) ?: 0L
        c.serverRefreshCheckedAtEpochSeconds = c.serverRefreshCheckedAtEpochSeconds ?: 0L
        c.serverRefreshWindowExpiresAtEpochSeconds = c.serverRefreshWindowExpiresAtEpochSeconds?.takeIf { it > 0 }
            ?: c.serverRefreshCheckedAtEpochSeconds.takeIf { it > 0 }
                ?.plus(properties.refreshWindowLeaseSeconds.coerceIn(60, 3600)) ?: 0L
        c.serverRefreshTimestampMillis = c.serverRefreshTimestampMillis ?: 0L
        c.validationFailureCount = c.validationFailureCount ?: 0
        c.validationRetryAfterEpochSeconds = c.validationRetryAfterEpochSeconds ?: 0L
        c.validationLastFailureAtEpochSeconds = c.validationLastFailureAtEpochSeconds ?: 0L
        c.validationLastFailureReason = c.validationLastFailureReason.orEmpty()
        c.refreshWindowFailureCount = c.refreshWindowFailureCount ?: 0
        c.refreshWindowRetryAfterEpochSeconds = c.refreshWindowRetryAfterEpochSeconds ?: 0L
        c.refreshWindowLastFailureAtEpochSeconds = c.refreshWindowLastFailureAtEpochSeconds ?: 0L
        c.refreshWindowLastFailureReason = c.refreshWindowLastFailureReason.orEmpty()
        c.refreshFailureCount = c.refreshFailureCount ?: 0
        c.refreshRetryAfterEpochSeconds = c.refreshRetryAfterEpochSeconds ?: 0L
        c.refreshLastFailureAtEpochSeconds = c.refreshLastFailureAtEpochSeconds ?: 0L
        c.refreshLastFailureReason = c.refreshLastFailureReason.orEmpty()
        c.extraCookies = c.extraCookies ?: LinkedHashMap()
    }

    private fun copyCredential(s: Cookies) = Cookies().also {
        it.sessData=s.sessData; it.biliJct=s.biliJct; it.buvid3=s.buvid3; it.buvid4=s.buvid4
        it.dedeUserId=s.dedeUserId; it.acTimeValue=s.acTimeValue; it.bNut=s.bNut
        it.biliTicket=s.biliTicket; it.biliTicketExpires=s.biliTicketExpires; it.extraCookies=LinkedHashMap(s.extraCookies.orEmpty())
        it.issuedAtEpochSeconds=s.issuedAtEpochSeconds; it.expiresAtEpochSeconds=s.expiresAtEpochSeconds
        it.nextRefreshAtEpochSeconds=s.nextRefreshAtEpochSeconds; it.lastValidatedAtEpochSeconds=s.lastValidatedAtEpochSeconds
        it.validationLeaseExpiresAtEpochSeconds=s.validationLeaseExpiresAtEpochSeconds
        it.serverRefreshRequired=s.serverRefreshRequired
        it.serverRefreshCheckedAtEpochSeconds=s.serverRefreshCheckedAtEpochSeconds
        it.serverRefreshWindowExpiresAtEpochSeconds=s.serverRefreshWindowExpiresAtEpochSeconds
        it.serverRefreshTimestampMillis=s.serverRefreshTimestampMillis
        copyMaintenanceState(s, it)
    }
    private fun Cookies.hasLoginCredential() = sessData.isNotBlank() && biliJct.isNotBlank()
    private fun Cookies.hasRefreshableCredential() = hasLoginCredential() && dedeUserId.isNotBlank() && acTimeValue.isNotBlank() && buvid3.isNotBlank()
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
    private fun decode(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8)

    companion object {
        private const val QR_GENERATE_URL="https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
        private const val QR_POLL_URL="https://passport.bilibili.com/x/passport-login/web/qrcode/poll"
        private const val NAV_URL="https://api.bilibili.com/x/web-interface/nav"
        private const val COOKIE_INFO_URL="https://passport.bilibili.com/x/passport-login/web/cookie/info"
        private const val COOKIE_REFRESH_URL="https://passport.bilibili.com/x/passport-login/web/cookie/refresh"
        private const val CONFIRM_REFRESH_URL="https://passport.bilibili.com/x/passport-login/web/confirm/refresh"
        private const val SSO_LIST_URL="https://passport.bilibili.com/x/passport-login/web/sso/list"
        private const val MAX_SSO_TARGETS=32
        private const val MAX_FAILURE_REASON_LENGTH=512
        private const val MIN_CREDENTIAL_LIFETIME_SECONDS=600L
        private const val MAX_CREDENTIAL_LIFETIME_SECONDS=400L * 24 * 60 * 60
        private const val LEGACY_EXPIRY_TOLERANCE_SECONDS=5L
        private const val SPI_URL="https://api.bilibili.com/x/frontend/finger/spi"
        private val REFRESH_CSRF=Regex("""<div\s+id=["']1-name["']>(.+?)</div>""", RegexOption.DOT_MATCHES_ALL)
        private val KNOWN_COOKIES=setOf("SESSDATA","bili_jct","buvid3","buvid4","DedeUserID","ac_time_value","b_nut","bili_ticket","bili_ticket_expires")
        private const val CORRESPOND_PUBLIC_KEY="""
            MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0Eg
            Uc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71
            nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40
            JNrRuoEUXpabUzGB8QIDAQAB
        """
    }
}
