package com.starlwr.bot.bilibili.http

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.bilibili.credential.BilibiliBrowserIdentity
import com.starlwr.bot.bilibili.credential.BilibiliCredentialFileStore
import com.starlwr.bot.bilibili.credential.BilibiliCredentialProperties
import com.starlwr.bot.bilibili.credential.StoredCookie
import com.starlwr.bot.bilibili.log.BilibiliNetworkLogger
import com.starlwr.bot.bilibili.risk.BrowserRiskExecutor
import com.starlwr.bot.core.plugin.StarBotComponent
import org.brotli.dec.BrotliInputStream
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

@ConfigurationProperties("starbot.bilibili.http")
class BilibiliHttpProperties {
    var connectTimeoutSeconds: Long = 10
    var requestTimeoutSeconds: Long = 30
    var maximumResponseBytes: Int = 32 * 1024 * 1024
    var maximumRedirects: Int = 5
    var retryMaxAttempts: Int = 3
    var retryBaseMillis: Long = 500
    var proxyUri: String? = null
}

internal fun configuredProxySelector(proxyUri: String?): ProxySelector? {
    val value = proxyUri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val uri = URI.create(value)
    require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
        "Bilibili proxy URI must use http or https: $value"
    }
    require(!uri.host.isNullOrBlank()) { "Bilibili proxy URI omitted host: $value" }
    val port = uri.port.takeIf { it > 0 } ?: if (uri.scheme.equals("https", true)) 443 else 80
    return ProxySelector.of(InetSocketAddress(uri.host, port))
}

internal fun proxyProfileId(proxyUri: String?): String {
    val value = proxyUri?.trim()?.takeIf { it.isNotEmpty() } ?: return "direct"
    val uri = URI.create(value)
    val port = uri.port.takeIf { it > 0 } ?: if (uri.scheme.equals("https", true)) 443 else 80
    return "${uri.scheme.lowercase(Locale.ROOT)}://${uri.host}:$port"
}

internal fun Throwable.isInterruptionSignal(): Boolean {
    var current: Throwable? = this
    val visited = HashSet<Throwable>()
    while (current != null && visited.add(current)) {
        if (current is InterruptedException || current is java.util.concurrent.CancellationException) return true
        current = current.cause
    }
    return false
}

enum class BilibiliBodyType(val contentType: String?) {
    NONE(null), JSON("application/json; charset=UTF-8"), FORM("application/x-www-form-urlencoded; charset=UTF-8"),
    RAW("text/plain; charset=UTF-8"), MULTIPART(null)
}

data class BilibiliHttpRequest(
    val method: String,
    val uri: URI,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val bodyType: BilibiliBodyType = BilibiliBodyType.NONE,
    val channel: String = "bilibili-api",
    val transport: String = "jvm",
    val idempotent: Boolean = method.equals("GET", true) || method.equals("HEAD", true),
)

data class BilibiliHttpResponse(
    val request: BilibiliHttpRequest,
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val attempts: Int,
    val elapsedMillis: Long,
) {
    fun text(): String {
        val contentType = headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value?.firstOrNull().orEmpty()
        val charset = Regex("charset=([^; ]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
            ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: StandardCharsets.UTF_8
        return body.toString(charset)
    }
    fun json(): JSONObject = JSON.parseObject(text())
    fun successful() = status in 200..299
}

@StarBotComponent
@EnableConfigurationProperties(BilibiliHttpProperties::class)
class BilibiliHttpPipeline(
    private val httpProperties: BilibiliHttpProperties,
    private val accountProperties: BilibiliCredentialProperties,
    private val browserIdentity: BilibiliBrowserIdentity,
    private val credentialStore: BilibiliCredentialFileStore,
    private val networkLog: BilibiliNetworkLogger,
    private val browserRiskExecutor: BrowserRiskExecutor,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sessionCookies = ConcurrentHashMap<String, String>()
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(httpProperties.connectTimeoutSeconds.coerceAtLeast(1)))
        .followRedirects(HttpClient.Redirect.NEVER)
        .apply {
            configuredProxySelector(httpProperties.proxyUri)?.let(::proxy)
        }.build()

    fun get(url: String, headers: Map<String, String> = emptyMap(), channel: String = "bilibili-api") =
        execute(BilibiliHttpRequest("GET", URI.create(url), headers, channel = channel))

    fun postForm(url: String, headers: Map<String, String>, params: Map<String, *> , channel: String = "bilibili-api") =
        execute(BilibiliHttpRequest("POST", URI.create(url), headers, encodeForm(params), BilibiliBodyType.FORM, channel, idempotent = false))

    fun postJson(url: String, headers: Map<String, String>, value: Any, channel: String = "bilibili-api") =
        execute(BilibiliHttpRequest("POST", URI.create(url), headers, JSON.toJSONBytes(value), BilibiliBodyType.JSON, channel, idempotent = false))

    fun postRaw(url: String, headers: Map<String, String>, body: ByteArray, contentType: String? = null,
                channel: String = "bilibili-api", idempotent: Boolean = false) =
        execute(BilibiliHttpRequest("POST", URI.create(url),
            if (contentType == null) headers else headers + ("Content-Type" to contentType),
            body, BilibiliBodyType.RAW, channel, idempotent = idempotent))

    fun postMultipart(url: String, headers: Map<String, String>, params: Map<String, *>, channel: String = "bilibili-api"): BilibiliHttpResponse {
        val boundary = "----StarBot${java.util.UUID.randomUUID().toString().replace("-", "")}" 
        val body = buildString {
            params.forEach { (name, value) ->
                append("--").append(boundary).append("\r\n")
                append("Content-Disposition: form-data; name=\"").append(name.replace("\"", "")).append("\"\r\n\r\n")
                append(value?.toString().orEmpty()).append("\r\n")
            }
            append("--").append(boundary).append("--\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        return execute(BilibiliHttpRequest("POST", URI.create(url),
            headers + ("Content-Type" to "multipart/form-data; boundary=$boundary"),
            body, BilibiliBodyType.MULTIPART, channel, idempotent = false))
    }

    fun execute(original: BilibiliHttpRequest): BilibiliHttpResponse {
        var request = original
        var redirects = 0
        var totalAttempts = 0
        val started = System.nanoTime()
        while (true) {
            val response = sendWithRetries(request) { totalAttempts++ }
            if (response.status == 412 && request.transport == "jvm") {
                browserRiskExecutor.resolve412(request)?.let { resolved ->
                    return resolved.copy(attempts = totalAttempts + resolved.attempts,
                        elapsedMillis = (System.nanoTime() - started) / 1_000_000)
                }
            }
            if (response.status !in REDIRECT_STATUS || redirects >= httpProperties.maximumRedirects) {
                return response.copy(attempts = totalAttempts, elapsedMillis = (System.nanoTime() - started) / 1_000_000)
            }
            val location = header(response.headers, "location") ?: return response
            val target = request.uri.resolve(location)
            val method = if (response.status == 303 || ((response.status == 301 || response.status == 302) && request.method == "POST")) "GET" else request.method
            request = request.copy(method = method, uri = target,
                body = if (method == "GET") ByteArray(0) else request.body,
                bodyType = if (method == "GET") BilibiliBodyType.NONE else request.bodyType,
                idempotent = method == "GET" || method == "HEAD")
            redirects++
        }
    }

    private fun sendWithRetries(request: BilibiliHttpRequest, attempted: () -> Unit): BilibiliHttpResponse {
        val max = if (request.idempotent) httpProperties.retryMaxAttempts.coerceAtLeast(1) else 1
        var last: Throwable? = null
        for (attempt in 1..max) {
            attempted()
            try { return send(request, attempt) } catch (error: Exception) {
                if (error.isInterruptionSignal() || Thread.currentThread().isInterrupted) {
                    if (error.isInterruptionSignal()) Thread.currentThread().interrupt()
                    throw error
                }
                last = error
                if (attempt == max) break
                val delay = (httpProperties.retryBaseMillis * (1L shl (attempt - 1))).coerceAtMost(5_000) +
                    ThreadLocalRandom.current().nextLong(0, 250)
                log.info("Bilibili HTTP 可重放请求失败，将在 {} ms 后重试: method={}, uri={}, attempt={}/{}, reason={}",
                    delay, request.method, request.uri, attempt, max, error.toString())
                Thread.sleep(delay)
            }
        }
        throw last ?: IllegalStateException("HTTP request failed")
    }

    private fun send(request: BilibiliHttpRequest, attempt: Int): BilibiliHttpResponse {
        val headers = linkedMapOf<String, String>()
        headers.putAll(browserIdentity.headers(accountProperties.userAgent))
        headers.putIfAbsent("Accept", "application/json, text/plain, */*")
        headers.putIfAbsent("Accept-Language", "zh-CN,zh;q=0.8,en;q=0.7")
        headers.putIfAbsent("Accept-Encoding", "gzip, deflate, br")
        headers.putAll(request.headers.filterKeys { !it.equals("cookie", true) && !it.equals("content-length", true) })
        request.bodyType.contentType?.let { headers.putIfAbsent("Content-Type", it) }
        val cookie = cookieHeader(request.uri, request.transport, request.headers.entries.firstOrNull { it.key.equals("cookie", true) }?.value)
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        val builder = HttpRequest.newBuilder(request.uri)
            .timeout(Duration.ofSeconds(httpProperties.requestTimeoutSeconds.coerceAtLeast(1)))
        headers.forEach(builder::header)
        val publisher = if (request.body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(request.body)
        builder.method(request.method.uppercase(Locale.ROOT), publisher)
        val trace = networkLog.httpRequest("${request.channel}#$attempt", request.method, request.uri.toString(), headers,
            if (request.body.isEmpty()) null else request.body.toString(StandardCharsets.UTF_8))
        val started = System.nanoTime()
        try {
            val raw = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            val decoded = decode(raw.body(), header(raw.headers().map(), "content-encoding"))
            require(decoded.size <= httpProperties.maximumResponseBytes) { "Bilibili response exceeds configured limit" }
            val response = BilibiliHttpResponse(request, raw.statusCode(), raw.headers().map(), decoded, attempt,
                (System.nanoTime() - started) / 1_000_000)
            mergeSetCookies(request, response)
            networkLog.httpResponse(trace, response.status, response.headers, response.text())
            return response
        } catch (error: Exception) {
            networkLog.httpFailure(trace, error)
            throw error
        }
    }

    private fun cookieHeader(uri: URI, transport: String, callerCookie: String?): String {
        val values = linkedMapOf<String, String>()
        callerCookie.orEmpty().split(';').map { it.trim() }.filter { it.contains('=') }.forEach { part ->
            values[part.substringBefore('=').trim()] = part.substringAfter('=')
        }
        credentialStore.cookiesFor(uri, transport).forEach { values[it.name] = it.value }
        values["b_lsid"] = sessionCookies.computeIfAbsent(transport) { generateLsid() }
        return values.entries.filter { it.key.isNotBlank() && it.value.isNotBlank() }.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun mergeSetCookies(request: BilibiliHttpRequest, response: BilibiliHttpResponse) {
        val headers = response.headers.entries.firstOrNull { it.key.equals("set-cookie", true) }?.value.orEmpty()
        headers.forEach { header ->
            val first = header.substringBefore(';')
            val name = first.substringBefore('=', "").trim()
            if (name.equals("b_lsid", true)) {
                val value = first.substringAfter('=', "")
                if (value.isBlank() || header.contains("max-age=0", true)) sessionCookies.remove(request.transport)
                else sessionCookies[request.transport] = value
            }
        }
        val parsed = headers.mapNotNull { parseSetCookie(it, request.uri, request.transport) }
        if (parsed.isNotEmpty()) credentialStore.mergeCookies(parsed, critical = true)
    }

    internal fun parseSetCookie(header: String, requestUri: URI, transport: String): StoredCookie? {
        val parts = header.split(';').map { it.trim() }
        val first = parts.firstOrNull() ?: return null
        val separator = first.indexOf('=')
        if (separator <= 0) return null
        val name = first.substring(0, separator).trim()
        if (name.equals("b_lsid", true)) return null
        val value = first.substring(separator + 1)
        var domain = requestUri.host ?: return null
        var hostOnly = true
        var path = "/"
        var secure = false
        var httpOnly = false
        var sameSite: String? = null
        var expires: Long? = null
        parts.drop(1).forEach { attribute ->
            val key = attribute.substringBefore('=').trim().lowercase(Locale.ROOT)
            val item = attribute.substringAfter('=', "").trim()
            when (key) {
                "domain" -> if (item.isNotBlank()) { domain = item; hostOnly = false }
                "path" -> if (item.isNotBlank()) path = item
                "secure" -> secure = true
                "httponly" -> httpOnly = true
                "samesite" -> sameSite = item.replaceFirstChar { it.uppercase() }
                "max-age" -> item.toLongOrNull()?.let { expires = Instant.now().epochSecond + it }
                "expires" -> if (expires == null) expires = runCatching {
                    ZonedDateTime.parse(item, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
                }.getOrNull()
            }
        }
        return StoredCookie(name, value, domain, path, hostOnly, secure, httpOnly, sameSite, expires,
            if (name.equals("X-BILI-SEC-TOKEN", true)) transport else "shared", "set-cookie")
    }

    private fun decode(bytes: ByteArray, encoding: String?): ByteArray {
        val input = when (encoding?.trim()?.lowercase(Locale.ROOT)) {
            "gzip" -> GZIPInputStream(ByteArrayInputStream(bytes))
            "deflate" -> InflaterInputStream(ByteArrayInputStream(bytes))
            "br" -> BrotliInputStream(ByteArrayInputStream(bytes))
            else -> return bytes
        }
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                require(output.size() + read <= httpProperties.maximumResponseBytes) { "Decoded response exceeds configured limit" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun encodeForm(params: Map<String, *>): ByteArray = params.entries.joinToString("&") {
        "${url(it.key)}=${url(it.value?.toString().orEmpty())}"
    }.toByteArray(StandardCharsets.UTF_8)

    private fun url(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    private fun generateLsid(): String = "%08X_%X".format(ThreadLocalRandom.current().nextInt(), System.currentTimeMillis())
    private fun header(headers: Map<String, List<String>>, name: String) =
        headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()

    companion object { private val REDIRECT_STATUS = setOf(301, 302, 303, 307, 308) }
}
