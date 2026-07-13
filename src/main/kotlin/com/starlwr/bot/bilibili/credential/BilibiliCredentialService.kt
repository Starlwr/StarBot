package com.starlwr.bot.bilibili.credential

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import com.alibaba.fastjson2.JSONWriter
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties
import com.starlwr.bot.bilibili.model.Cookies
import com.starlwr.bot.core.plugin.StarBotComponent
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.util.Base64
import java.util.LinkedHashMap
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

@ConfigurationProperties("starbot.bilibili.account")
class BilibiliCredentialProperties {
    var credentialFile: String = "./config/bilibili-credential.json"
    var legacyCookieFile: String = "./cookies.json"
    var autoRefresh: Boolean = true
    var refreshCheckMillis: Long = 6 * 60 * 60 * 1000L
    var qrPollMillis: Long = 3_000
    var qrRegenerateOnExpiry: Boolean = true
    var connectTimeoutSeconds: Long = 10
    var requestTimeoutSeconds: Long = 30
}

data class QrCodeSession(val url: String, val key: String)
enum class QrCodeState { WAIT_SCAN, WAIT_CONFIRM, EXPIRED, DONE, ERROR }
data class QrCodePollResult(val state: QrCodeState, val credential: Cookies? = null, val message: String? = null)

/** Full web Credential lifecycle ported from bilibili-api-python. */
@StarBotComponent
@EnableConfigurationProperties(BilibiliCredentialProperties::class)
class BilibiliCredentialService(
    private val properties: BilibiliCredentialProperties,
    private val bilibiliProperties: StarBotBilibiliProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds))
        .followRedirects(HttpClient.Redirect.NORMAL).build()
    private val lock = Any()

    fun getProperties(): BilibiliCredentialProperties = properties
    fun credentialPath(): Path = Path.of(properties.credentialFile).toAbsolutePath().normalize()

    fun load(): Cookies? = synchronized(lock) {
        val primary = credentialPath()
        val legacy = Path.of(properties.legacyCookieFile)
        val source = when {
            Files.isRegularFile(primary) -> primary
            Files.isRegularFile(legacy) -> legacy
            else -> return null
        }
        val credential = JSON.parseObject(Files.readString(source), Cookies::class.java) ?: return null
        normalize(credential)
        if (!credential.hasLoginCredential()) return null
        if (source != primary) {
            log.info("Migrating legacy credential file {} to {}", source.toAbsolutePath(), primary)
            save(credential)
        }
        credential
    }

    fun save(credential: Cookies) = synchronized(lock) {
        normalize(credential)
        val target = credentialPath()
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling("${target.fileName}.tmp-${UUID.randomUUID()}")
        try {
            Files.writeString(temporary, JSON.toJSONString(credential, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    fun checkValid(credential: Cookies): Boolean {
        val json = parseJson(request("GET", NAV_URL, credential))
        return json.getIntValue("code", -1) == 0 && json.getJSONObject("data")?.getBooleanValue("isLogin") == true
    }

    fun checkRefresh(credential: Cookies): Boolean =
        requireSuccess(request("GET", COOKIE_INFO_URL, credential), "check Credential refresh").getBooleanValue("refresh")

    fun refreshIfNeeded(credential: Cookies, force: Boolean = false): Cookies = synchronized(lock) {
        if (!force && (!properties.autoRefresh || !checkRefresh(credential))) credential else refresh(credential)
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
        val response = request("POST", COOKIE_REFRESH_URL, old, form, randomizeBuvid3 = true)
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
        val confirm = linkedMapOf("csrf" to refreshed.biliJct, "refresh_token" to old.acTimeValue)
        requireSuccess(request("POST", CONFIRM_REFRESH_URL, refreshed, confirm), "confirm Credential refresh")
        save(refreshed)
        log.info("Bilibili Credential refreshed and old refresh token confirmed")
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

    private fun getRefreshCsrf(credential: Cookies): String {
        val response = request("GET", "https://www.bilibili.com/correspond/1/${correspondPath()}", credential, randomizeBuvid3 = true)
        if (response.statusCode() == 404) error("Credential correspondPath expired or was rejected")
        if (response.statusCode() != 200) error("Unable to get refresh CSRF: HTTP ${response.statusCode()}")
        return REFRESH_CSRF.find(response.body())?.groupValues?.get(1)
            ?: error("Credential refresh CSRF was absent from correspond response")
    }

    private fun correspondPath(): String {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode(CORRESPOND_PUBLIC_KEY)))
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        return cipher.doFinal("refresh_${System.currentTimeMillis()}".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun request(method: String, url: String, credential: Cookies? = null, form: Map<String, String>? = null, randomizeBuvid3: Boolean = false): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(properties.requestTimeoutSeconds))
            .header("User-Agent", bilibiliProperties.network.userAgent).header("Referer", "https://www.bilibili.com")
            .header("Accept", "application/json, text/plain, */*")
        if (credential != null) builder.header("Cookie", cookieHeader(credential, randomizeBuvid3))
        if (method == "POST") {
            val body = form.orEmpty().entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
            builder.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        } else builder.GET()
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    }

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
        c.extraCookies = c.extraCookies ?: LinkedHashMap()
    }

    private fun copyCredential(s: Cookies) = Cookies().also {
        it.sessData=s.sessData; it.biliJct=s.biliJct; it.buvid3=s.buvid3; it.buvid4=s.buvid4
        it.dedeUserId=s.dedeUserId; it.acTimeValue=s.acTimeValue; it.bNut=s.bNut
        it.biliTicket=s.biliTicket; it.biliTicketExpires=s.biliTicketExpires; it.extraCookies=LinkedHashMap(s.extraCookies.orEmpty())
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
