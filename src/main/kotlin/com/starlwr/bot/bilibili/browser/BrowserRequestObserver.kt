package com.starlwr.bot.bilibili.browser

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.bilibili.credential.RequestIdentitySnapshot
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.nio.ByteBuffer
import java.util.Base64

/** One page, exact request-id provenance. No Cookie writes, fetch hooks or auth logging. */
internal class BrowserRequestObserver(private val page: BrowserPage, private val roomId: String,
                                      private val initiatorMarker: String? = null,
                                      private val endpoint: URI = URI.create("https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo")) : AutoCloseable {
    data class Response(val sequence: Long, val url: String, val envelope: JSONObject,
                        val identity: RequestIdentitySnapshot?, val voucher: String?, val generation: Long = 0)
    private class Request(val url: String, val headers: MutableMap<String, String>, val generation: Long) {
        @Volatile var finished = false
        @Volatile var extra = false
        @Volatile var voucher: String? = null
    }
    private val requests = ConcurrentHashMap<String, Request>()
    private val earlyHeaders = ConcurrentHashMap<String, JSONObject>()
    private val responses = ConcurrentLinkedQueue<Response>()
    private val captchaRequests = ConcurrentHashMap.newKeySet<String>()
    private val sequence = AtomicLong()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "bilibili-browser-response").apply { isDaemon = true } }
    private val registration = page.connection.onEvent { accept(it) }
    @Volatile private var closed = false
    @Volatile private var lastCaptchaActivityAt: Long = 0
    @Volatile private var nativeStarted = false
    @Volatile private var generation = 0L
    private data class Socket(val generation: Long, @Volatile var key: String? = null, @Volatile var authenticated: Boolean = false)
    private val sockets = ConcurrentHashMap<String, Socket>()

    /** Latched until navigation: a quiet network does not mean the human challenge ended. */
    fun officialVerificationStarted(): Boolean = nativeStarted
    fun documentGeneration(): Long = generation
    fun officialSocketAuthenticated(token: String): Boolean = sockets.values.any {
        it.generation == generation && it.key == token && it.authenticated
    }

    fun enable() { page.connection.call("Page.enable"); page.connection.call("Network.enable") }
    fun poll(): Response? = responses.poll()
    fun captchaLoading(): Boolean = captchaRequests.isNotEmpty()
    fun captchaLastActivityMillis(): Long = lastCaptchaActivityAt

    internal fun accept(event: JSONObject) {
        if (closed) return
        val p = event.getJSONObject("params") ?: return
        if (event.getString("method") == "Page.frameNavigated" && p.getJSONObject("frame")?.getString("parentId") == null) {
            generation++; nativeStarted = false; lastCaptchaActivityAt = 0
            requests.clear(); responses.clear(); earlyHeaders.clear(); captchaRequests.clear(); sockets.clear()
            return
        }
        val id = p.getString("requestId") ?: return
        when (event.getString("method")) {
            "Network.webSocketCreated" -> {
                val uri = runCatching { URI.create(p.getString("url")) }.getOrNull()
                if (uri?.scheme == "wss" && uri.host?.endsWith(".bilibili.com") == true && uri.path == "/sub" && sockets.size < 16)
                    sockets[id] = Socket(generation)
            }
            "Network.webSocketFrameSent", "Network.webSocketFrameReceived" -> {
                val socket = sockets[id] ?: return
                val response = p.getJSONObject("response") ?: return
                if (response.getIntValue("opcode") != 2) return
                runCatching {
                    val bytes = Base64.getDecoder().decode(response.getString("payloadData"))
                    if (bytes.size < 16) return@runCatching
                    val buffer = ByteBuffer.wrap(bytes)
                    val length = buffer.int
                    val header = buffer.short.toInt() and 0xffff
                    val version = buffer.short.toInt() and 0xffff
                    val op = buffer.int
                    if (length != bytes.size || header < 16 || header > length || version != 1) return@runCatching
                    val body = JSON.parseObject(String(bytes, header, length - header, Charsets.UTF_8))
                    synchronized(socket) {
                        if (op == 7 && event.getString("method") == "Network.webSocketFrameSent"
                            && body.getString("roomid") == roomId) socket.key = body.getString("key")
                        if (op == 8 && event.getString("method") == "Network.webSocketFrameReceived")
                            socket.authenticated = body.getInteger("code") == 0 && socket.key != null
                    }
                }
            }
            "Network.webSocketClosed", "Network.webSocketFrameError" -> sockets.remove(id)
            "Network.requestWillBeSent" -> {
                val request = p.getJSONObject("request") ?: return
                val url = request.getString("url") ?: return
                if (Regex("risk-captcha-sdk|/risk/config|geetest|/captcha/|/gaia-vgate/", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
                    nativeStarted = true
                    captchaRequests.add(id); lastCaptchaActivityAt = System.currentTimeMillis()
                }
                if (!matches(url)) return
                if (initiatorMarker != null && !p.getJSONObject("initiator").toString().contains(initiatorMarker)) return
                if (requests.size >= 64) return
                val r = Request(url, ConcurrentHashMap(), generation)
                copyHeaders(request.getJSONObject("headers"), r.headers)
                earlyHeaders.remove(id)?.let { copyHeaders(it, r.headers); r.extra = true }
                requests[id] = r
            }
            "Network.requestWillBeSentExtraInfo" -> {
                val headers = p.getJSONObject("headers") ?: return
                val r = requests[id]
                if (r == null) { if (earlyHeaders.size < 128) earlyHeaders[id] = headers }
                else { copyHeaders(headers, r.headers); r.extra = true }
            }
            "Network.responseReceived" -> {
                val headers = p.getJSONObject("response")?.getJSONObject("headers")
                requests[id]?.voucher = headers?.entries?.firstOrNull { it.key.equals("x-bili-gaia-vvoucher", true) }?.value?.toString()
            }
            "Network.loadingFinished" -> {
                if (captchaRequests.remove(id)) lastCaptchaActivityAt = System.currentTimeMillis()
                earlyHeaders.remove(id)
                val r = requests[id] ?: return
                if (r.finished) return
                r.finished = true
                worker.execute {
                    try {
                        val raw = page.connection.call("Network.getResponseBody", mapOf("requestId" to id))
                        val body = raw.getString("body").orEmpty()
                        val text = if (raw.getBooleanValue("base64Encoded"))
                            String(java.util.Base64.getDecoder().decode(body), Charsets.UTF_8) else body
                        val envelope = JSON.parseObject(text) ?: return@execute
                        // Never reconstruct request identity from a later Cookie jar.
                        val extraDeadline = System.nanoTime() + 1_000_000_000L
                        while (!r.extra && !closed && System.nanoTime() < extraDeadline) Thread.sleep(10)
                        val identity = if (r.extra) fromHeaders(r.headers) else null
                        if (responses.size >= 16) responses.poll()
                        if (!closed && r.generation == generation) responses.add(Response(sequence.incrementAndGet(), r.url, envelope, identity, r.voucher, r.generation))
                    } catch (_: Exception) { /* CDP target disappeared; caller can retry without exporting identity. */ }
                    finally { requests.remove(id, r) }
                }
            }
            "Network.loadingFailed" -> {
                requests.remove(id); earlyHeaders.remove(id)
                if (captchaRequests.remove(id)) lastCaptchaActivityAt = System.currentTimeMillis()
            }
        }
    }

    private fun matches(url: String): Boolean = runCatching {
        val uri = URI.create(url)
        uri.scheme == endpoint.scheme && uri.rawAuthority == endpoint.rawAuthority && uri.path == endpoint.path &&
            uri.rawQuery.orEmpty().split('&').any { it == "id=$roomId" }
    }.getOrDefault(false)

    private fun copyHeaders(source: JSONObject?, target: MutableMap<String, String>) {
        source?.forEach { (key, value) -> target[key.lowercase()] = value.toString() }
    }

    private fun fromHeaders(headers: Map<String, String>): RequestIdentitySnapshot? {
        val cookie = headers["cookie"].orEmpty()
        val values = cookie.split(';').map { it.trim().split('=', limit = 2) }
            .filter { it.size == 2 }.associate { it[0] to it[1] }
        val buvid = values["buvid3"]?.takeIf { it.isNotBlank() } ?: return null
        val ua = headers["user-agent"]?.takeIf { it.isNotBlank() } ?: return null
        return RequestIdentitySnapshot(mapOf("Cookie" to cookie, "User-Agent" to ua,
            "Referer" to headers["referer"].orEmpty(), "Origin" to headers["origin"].orEmpty()),
            buvid, values["DedeUserID"] ?: "0", "observed-browser-request")
    }

    override fun close() {
        closed = true; registration.close(); worker.shutdownNow()
        requests.clear(); earlyHeaders.clear(); responses.clear(); captchaRequests.clear(); sockets.clear()
    }
}
