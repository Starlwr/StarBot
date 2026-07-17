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
import java.util.Base64
import java.util.LinkedHashMap
import java.util.UUID
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
    var refreshAtLifecycleRatio: Double = 0.25
    var externalCredentialInitialRefresh: Boolean = true
    var maintenanceIntervalMillis: Long = 30_000
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
        val credential = fileStore.loadCookies() ?: return null
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
        val mode = properties.validationMode.trim().lowercase()
        val navValid = if (mode in setOf("nav", "both")) {
            val json = parseJson(request("GET", NAV_URL, credential))
            json.getIntValue("code", -1) == 0 && json.getJSONObject("data")?.getBooleanValue("isLogin") == true
        } else true
        val cookieInfoValid = if (mode in setOf("cookie-info", "cookie_info", "both")) {
            runCatching { requireSuccess(request("GET", COOKIE_INFO_URL, credential), "validate Credential") }.isSuccess
        } else true
        require(mode in setOf("nav", "cookie-info", "cookie_info", "both")) {
            "validation-mode must be nav, cookie-info, or both"
        }
        val valid = navValid && cookieInfoValid
        log.info("Bilibili Credential 有效性校验完成: mode={}, navValid={}, cookieInfoValid={}, valid={}",
            mode, navValid, cookieInfoValid, valid)
        return valid
    }

    fun validateAndUpdateLease(credential: Cookies): Boolean = synchronized(lock) {
        if (!properties.validateCredential) {
            log.info("不重置 Credential 校验租约: 有效性校验已禁用")
            return true
        }
        val valid = checkValid(credential)
        if (valid) {
            val pendingInitialRefresh = credential.nextRefreshAtEpochSeconds <= 0 &&
                properties.externalCredentialInitialRefresh && credential.acTimeValue.isNotBlank()
            applyLifecycle(credential, null)
            if (pendingInitialRefresh) credential.nextRefreshAtEpochSeconds = 0L
            save(credential)
            log.info("Credential 校验租约已重置: expiresAt={}, nextRefreshAt={}, pendingInitialRefresh={}",
                describeEpoch(credential.expiresAtEpochSeconds), describeEpoch(credential.nextRefreshAtEpochSeconds),
                pendingInitialRefresh)
        } else {
            log.info("Credential 校验失败: 保留现有凭据，不重置租约")
        }
        valid
    }

    fun checkRefresh(credential: Cookies): Boolean {
        val refresh = requireSuccess(request("GET", COOKIE_INFO_URL, credential), "check Credential refresh")
            .getBooleanValue("refresh")
        log.info("Bilibili Credential 刷新窗口检查完成: serverRefresh={}", refresh)
        return refresh
    }

    fun refreshIfNeeded(credential: Cookies, force: Boolean = false): Cookies = synchronized(lock) {
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
            lifecycleDue -> "Credential 生命周期已到刷新点 ${describeEpoch(credential.nextRefreshAtEpochSeconds)}"
            initialRefresh -> "外部导入 Credential 尚未建立生命周期，配置要求首次尝试刷新"
            else -> null
        }
        if (reason == null) {
            log.info("跳过 Bilibili Credential 刷新: 尚未到刷新点, now={}, nextRefreshAt={}",
                describeEpoch(now), describeEpoch(credential.nextRefreshAtEpochSeconds))
            return credential
        }
        log.info("准备检查 Bilibili Credential 是否可刷新: reason={}", reason)
        if (!force && !checkRefresh(credential)) {
            // Bilibili rejects correspondPath with 404 outside its refresh window.
            // A successful cookie-info response renews our short validation lease.
            applyLifecycle(credential, null)
            save(credential)
            log.info("跳过实际 Credential 刷新: Bilibili 返回 refresh=false; 已重置短期租约, nextRefreshAt={}",
                describeEpoch(credential.nextRefreshAtEpochSeconds))
            return credential
        }
        log.info("开始刷新 Bilibili Credential: reason={}, force={}", reason, force)
        refresh(credential)
    }

    fun maintain(credential: Cookies): Cookies = synchronized(lock) {
        val now = System.currentTimeMillis() / 1000
        if ((credential.refreshRetryAfterEpochSeconds ?: 0L) > now) {
            log.info("跳过本轮 Credential 维护: 刷新失败退避中, retryAfter={}",
                describeEpoch(credential.refreshRetryAfterEpochSeconds))
            return credential
        }
        if (properties.validateCredential && (credential.expiresAtEpochSeconds ?: 0L) <= now) {
            log.info("开始 Credential 有效性校验: 校验租约已到期, now={}, expiresAt={}",
                describeEpoch(now), describeEpoch(credential.expiresAtEpochSeconds))
            check(validateAndUpdateLease(credential)) { "Bilibili Credential is no longer valid" }
        } else if (!properties.validateCredential) {
            log.info("跳过本轮 Credential 有效性校验: validate-credential=false")
        } else {
            log.info("跳过本轮 Credential 有效性校验: 校验租约仍有效, now={}, expiresAt={}",
                describeEpoch(now), describeEpoch(credential.expiresAtEpochSeconds))
        }
        refreshIfNeeded(credential, false)
    }

    fun recordMaintenanceFailure(credential: Cookies): Long = synchronized(lock) {
        val failures = ((credential.refreshFailureCount ?: 0) + 1).coerceAtMost(30)
        val multiplier = 1L shl (failures - 1).coerceAtMost(20)
        val delay = (properties.refreshRetryBaseSeconds.coerceAtLeast(60) * multiplier)
            .coerceAtMost(properties.refreshRetryMaxSeconds.coerceAtLeast(60))
        credential.refreshFailureCount = failures
        credential.refreshRetryAfterEpochSeconds = System.currentTimeMillis() / 1000 + delay
        save(credential)
        log.warn("Credential 维护失败已进入退避: failures={}, delaySeconds={}, retryAfter={}",
            failures, delay, describeEpoch(credential.refreshRetryAfterEpochSeconds))
        delay
    }

    fun refresh(old: Cookies): Cookies = synchronized(lock) {
        require(old.biliJct.isNotBlank()) { "bili_jct is required to refresh Credential" }
        require(old.acTimeValue.isNotBlank()) { "ac_time_value/refresh_token is required to refresh Credential" }
        val form = linkedMapOf(
            "csrf" to old.biliJct,
            "refresh_csrf" to getRefreshCsrf(old),
            "refresh_token" to old.acTimeValue,
            "source" to "main_web"
        )
        val response = request("POST", COOKIE_REFRESH_URL, old, form)
        val data = requireSuccess(response, "refresh Credential")
        val responseCookies = parseSetCookies(response.headers().allValues("set-cookie"))
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
        applyLifecycle(refreshed, parseExpiry(response.headers().allValues("set-cookie")))
        val confirm = linkedMapOf("csrf" to refreshed.biliJct, "refresh_token" to old.acTimeValue)
        requireSuccess(request("POST", CONFIRM_REFRESH_URL, refreshed, confirm), "confirm Credential refresh")
        save(refreshed)
        log.info("Bilibili Credential 刷新成功且旧 refresh token 已确认: issuedAt={}, expiresAt={}, nextRefreshAt={}",
            describeEpoch(refreshed.issuedAtEpochSeconds), describeEpoch(refreshed.expiresAtEpochSeconds),
            describeEpoch(refreshed.nextRefreshAtEpochSeconds))
        refreshed
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
            0 -> completeQrLogin(data)
            else -> QrCodePollResult(QrCodeState.ERROR, message = data.getString("message") ?: "QR status $code")
        }
    }

    private fun completeQrLogin(data: JSONObject): QrCodePollResult {
        val query = parseQuery(data.getString("url") ?: "")
        val buvid = fetchBuvid()
        val credential = Cookies().apply {
            sessData = query["SESSDATA"].orEmpty()
            biliJct = query["bili_jct"].orEmpty()
            dedeUserId = query["DedeUserID"].orEmpty()
            acTimeValue = data.getString("refresh_token").orEmpty()
            buvid3 = query["buvid3"] ?: buvid.first
            buvid4 = query["buvid4"] ?: buvid.second
            bNut = query["b_nut"].orEmpty()
            extraCookies.putAll(query.filterKeys { it !in KNOWN_COOKIES })
        }
        if (!credential.hasRefreshableCredential())
            return QrCodePollResult(QrCodeState.ERROR, message = "QR login returned an incomplete Credential")
        applyLifecycle(credential, null)
        save(credential)
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

    internal fun parseExpiry(headers: List<String>): Long? {
        val now = System.currentTimeMillis() / 1000
        headers.forEach { header ->
            header.split(';').drop(1).forEach { attribute ->
                val name = attribute.substringBefore('=').trim()
                val value = attribute.substringAfter('=', "").trim()
                if (name.equals("max-age", true)) value.toLongOrNull()?.let { return now + it }
                if (name.equals("expires", true)) runCatching {
                    java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
                }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun applyLifecycle(credential: Cookies, serverExpiry: Long?) {
        val now = System.currentTimeMillis() / 1000
        val lease = properties.validationLeaseSeconds.coerceIn(60, 300)
        val expiry = serverExpiry?.takeIf { it > now } ?: (now + lease)
        val ratio = properties.refreshAtLifecycleRatio.coerceIn(0.01, 0.95)
        credential.issuedAtEpochSeconds = now
        credential.expiresAtEpochSeconds = expiry
        credential.nextRefreshAtEpochSeconds = now + ((expiry - now) * ratio).toLong().coerceAtLeast(1)
        credential.lastValidatedAtEpochSeconds = now
        credential.refreshFailureCount = 0
        credential.refreshRetryAfterEpochSeconds = 0L
    }

    private fun getRefreshCsrf(credential: Cookies): String {
        var lastStatus = 0
        repeat(3) { attempt ->
            // Current bilibili binds this page to the established device identity.
            // Replacing buvid3 (as older bilibili-api-python did) now yields HTTP 404.
            val response = request("GET", "https://www.bilibili.com/correspond/1/${correspondPath()}", credential)
            lastStatus = response.statusCode()
            if (lastStatus == 200) {
                return REFRESH_CSRF.find(response.body())?.groupValues?.get(1)
                    ?: error("Credential refresh CSRF was absent from correspond response")
            }
            if (lastStatus != 404) error("Unable to get refresh CSRF: HTTP $lastStatus")
            if (attempt < 2) Thread.sleep(250L shl attempt)
        }
        error("Credential correspondPath was rejected after 3 attempts (HTTP $lastStatus)")
    }

    private fun correspondPath(): String {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode(CORRESPOND_PUBLIC_KEY)))
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        return cipher.doFinal("refresh_${System.currentTimeMillis()}".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun request(method: String, url: String, credential: Cookies? = null, form: Map<String, String>? = null, randomizeBuvid3: Boolean = false): HttpResponse<String> {
        val headers = linkedMapOf("Referer" to "https://www.bilibili.com", "Accept" to "application/json, text/plain, */*")
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
            client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).also { response ->
                networkLog.httpResponse(trace, response.statusCode(), response.headers().map(), response.body())
            }
        } catch (error: Throwable) {
            networkLog.httpFailure(trace, error)
            throw error
        }
    }

    private fun describeEpoch(value: Long?): String = value?.takeIf { it > 0 }
        ?.let { "${Instant.ofEpochSecond(it)}($it)" } ?: "unset"

    private fun requireSuccess(response: HttpResponse<String>, operation: String): JSONObject {
        val json = parseJson(response)
        if (response.statusCode() != 200 || json.getIntValue("code", -1) != 0)
            error("Failed to $operation: HTTP ${response.statusCode()}, code=${json.getIntValue("code", -1)}, message=${json.getString("message")}")
        return json.getJSONObject("data") ?: json.getJSONObject("result") ?: JSONObject()
    }

    private fun parseJson(response: HttpResponse<String>): JSONObject = runCatching { JSON.parseObject(response.body()) }
        .getOrElse { error("Bilibili returned invalid JSON (HTTP ${response.statusCode()}): ${response.body().take(256)}") }

    private fun cookieHeader(credential: Cookies, randomizeBuvid3: Boolean): String {
        val values = linkedMapOf<String, String?>(
            "SESSDATA" to credential.sessData, "bili_jct" to credential.biliJct,
            "buvid3" to if (randomizeBuvid3) UUID.randomUUID().toString() else credential.buvid3,
            "buvid4" to credential.buvid4, "DedeUserID" to credential.dedeUserId,
            "b_nut" to credential.bNut, "bili_ticket" to credential.biliTicket,
            "bili_ticket_expires" to credential.biliTicketExpires?.takeIf { it > 0 }?.toString()
        )
        values.putAll(credential.extraCookies.orEmpty())
        return values.filterValues { !it.isNullOrBlank() }.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun normalize(c: Cookies) {
        c.sessData = c.sessData.orEmpty(); c.biliJct = c.biliJct.orEmpty(); c.buvid3 = c.buvid3.orEmpty()
        c.buvid4 = c.buvid4.orEmpty(); c.dedeUserId = c.dedeUserId.orEmpty(); c.acTimeValue = c.acTimeValue.orEmpty()
        c.bNut = c.bNut.orEmpty(); c.biliTicket = c.biliTicket.orEmpty(); c.biliTicketExpires = c.biliTicketExpires ?: 0L
        c.issuedAtEpochSeconds = c.issuedAtEpochSeconds ?: 0L; c.expiresAtEpochSeconds = c.expiresAtEpochSeconds ?: 0L
        c.nextRefreshAtEpochSeconds = c.nextRefreshAtEpochSeconds ?: 0L; c.lastValidatedAtEpochSeconds = c.lastValidatedAtEpochSeconds ?: 0L
        c.refreshFailureCount = c.refreshFailureCount ?: 0; c.refreshRetryAfterEpochSeconds = c.refreshRetryAfterEpochSeconds ?: 0L
        c.extraCookies = c.extraCookies ?: LinkedHashMap()
    }

    private fun copyCredential(s: Cookies) = Cookies().also {
        it.sessData=s.sessData; it.biliJct=s.biliJct; it.buvid3=s.buvid3; it.buvid4=s.buvid4
        it.dedeUserId=s.dedeUserId; it.acTimeValue=s.acTimeValue; it.bNut=s.bNut
        it.biliTicket=s.biliTicket; it.biliTicketExpires=s.biliTicketExpires; it.extraCookies=LinkedHashMap(s.extraCookies.orEmpty())
        it.issuedAtEpochSeconds=s.issuedAtEpochSeconds; it.expiresAtEpochSeconds=s.expiresAtEpochSeconds
        it.nextRefreshAtEpochSeconds=s.nextRefreshAtEpochSeconds; it.lastValidatedAtEpochSeconds=s.lastValidatedAtEpochSeconds
        it.refreshFailureCount=s.refreshFailureCount; it.refreshRetryAfterEpochSeconds=s.refreshRetryAfterEpochSeconds
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
