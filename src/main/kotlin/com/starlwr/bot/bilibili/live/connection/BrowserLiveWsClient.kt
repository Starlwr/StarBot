package com.starlwr.bot.bilibili.live.connection

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.bilibili.browser.BilibiliBrowserRuntime
import com.starlwr.bot.bilibili.browser.BrowserPage
import com.starlwr.bot.bilibili.browser.BrowserPageNavigator
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties
import com.starlwr.bot.core.plugin.StarBotComponent
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketExtension
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.WebSocketClient
import java.net.InetSocketAddress
import java.net.URI
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Browser bridge dial failed before the upstream WebSocket opened. */
class BrowserWsHandshakeException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * 浏览器桥 WebSocket 客户端。
 *
 * 弹幕令牌与浏览器 TLS 环境绑定, JVM 直连 wss:// 会在认证后被 TLS 层掐断(1006)。
 * 本客户端把 WebSocket 的 TLS 拨号搬进受控 Chrome 的 live.bilibili.com 页面里执行:
 * 页面内以原生 WebSocket(真实浏览器指纹/Cookie/Origin)连接弹幕服务器, CDP 作为控制与字节管道。
 * 协议语义(认证/心跳/解包)仍由 JVM 侧连接器负责, 本类只做字节搬运。
 */
@StarBotComponent
open class BrowserLiveWsClient(
    private val runtime: BilibiliBrowserRuntime?,
    private val navigator: BrowserPageNavigator?,
    private val properties: StarBotBilibiliProperties,
) : WebSocketClient {

    private val log = LoggerFactory.getLogger(javaClass)
    private val sequence = AtomicLong()
    private val pageLock = Any()

    @Volatile private var bridgePage: BrowserPage? = null

    @Volatile private var pageHealthy = false

    private val sessions = ConcurrentHashMap<Long, BrowserWebSocketSession>()
    private val pendingDials = ConcurrentHashMap<Long, PendingDial>()

    private val pump = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "bilibili-browser-ws-pump").apply { isDaemon = true }
    }

    @Volatile private var pumpStarted = false

    private class PendingDial(val future: CompletableFuture<WebSocketSession>, val deadlineMillis: Long)

    open fun usable(): Boolean {
        val rt = runtime ?: return false
        return runCatching { rt.start(properties.network.userAgent) != null }.getOrDefault(false)
    }

    override fun execute(handler: WebSocketHandler, uriTemplate: String, vararg uriVariables: Any?): CompletableFuture<WebSocketSession> =
        throw UnsupportedOperationException("Browser bridge supports direct URIs only: $uriTemplate")

    override fun execute(handler: WebSocketHandler, headers: WebSocketHttpHeaders?, uri: URI): CompletableFuture<WebSocketSession> {
        val socketId = sequence.incrementAndGet()
        val future = CompletableFuture<WebSocketSession>()
        return try {
            ensureBridgePage()
            val dialResult = evaluateOnPage(
                "window.__starbotWsBridge.dial($socketId, ${JSON.toJSONString(uri.toString())})"
            )
            if (dialResult is JSONObject && dialResult.getBooleanValue("ok")) {
                pendingDials[socketId] = PendingDial(future, System.currentTimeMillis() + dialTimeoutMillis())
                sessions[socketId] = BrowserWebSocketSession(this, socketId, handler, uri, headers)
                startPump()
                future
            } else {
                future.completeExceptionally(
                    BrowserWsHandshakeException("浏览器桥 dial 被页面拒绝: ${JSON.toJSONString(dialResult)}")
                )
                future
            }
        } catch (error: Throwable) {
            markPageUnhealthy(error)
            future.completeExceptionally(
                BrowserWsHandshakeException("浏览器桥不可用: ${error.message}", error)
            )
            future
        }
    }

    fun sendBinary(socketId: Long, payload: ByteArray) {
        val encoded = Base64.getEncoder().encodeToString(payload)
        val result = evaluateOnPage("window.__starbotWsBridge.send($socketId, ${JSON.toJSONString(encoded)})")
        if (result is JSONObject && !result.getBooleanValue("ok")) {
            throw IllegalStateException("浏览器桥 socket 不再可用: socketId=$socketId")
        }
    }

    fun closeSocket(socketId: Long, status: CloseStatus) {
        try {
            evaluateOnPage("window.__starbotWsBridge.close($socketId, ${status.code})")
        } catch (error: Throwable) {
            log.debug("浏览器桥关闭 socket 失败(忽略, 等待页面健康检查兜底): socketId={}", socketId, error)
        }
    }

    private fun dialTimeoutMillis(): Long = 10_000L

    private fun pollIntervalMillis(): Long =
        properties.getLive().getBrowserWsPollIntervalMillis().coerceIn(25, 2000).toLong()

    private fun startPump() {
        if (pumpStarted) return
        synchronized(pump) {
            if (pumpStarted) return
            pumpStarted = true
            pump.scheduleAtFixedRate({ runCatching { pumpOnce() }.onFailure {
                log.debug("浏览器桥事件泵轮询异常(下一轮继续): {}", it.toString())
            } }, pollIntervalMillis(), pollIntervalMillis(), TimeUnit.MILLISECONDS)
        }
    }

    private fun pumpOnce() {
        if (sessions.isEmpty() && pendingDials.isEmpty()) return
        if (!pageHealthy) return
        val now = System.currentTimeMillis()
        for ((socketId, pending) in pendingDials) {
            if (now > pending.deadlineMillis) {
                pendingDials.remove(socketId)
                sessions.remove(socketId)
                pending.future.completeExceptionally(
                    BrowserWsHandshakeException("浏览器桥拨号超时: socketId=$socketId")
                )
            }
        }
        val events = try {
            evaluateOnPage("window.__starbotWsBridge.drain()") as? JSONArray
        } catch (error: Throwable) {
            markPageUnhealthy(error)
            return
        } ?: return
        for (element in events) {
            val event = element as? JSONObject ?: continue
            dispatch(event)
        }
    }

    private fun dispatch(event: JSONObject) {
        val socketId = event.getLongValue("id")
        val session = sessions[socketId]
        when (event.getString("t")) {
            "open" -> {
                val pending = pendingDials.remove(socketId)
                if (session != null) {
                    session.delegateOpen = true
                    pending?.future?.complete(session)
                    runCatching { session.handler.afterConnectionEstablished(session) }
                        .onFailure { log.error("浏览器桥连接建立回调异常: socketId={}", socketId, it) }
                } else {
                    pending?.future?.completeExceptionally(BrowserWsHandshakeException("浏览器桥 socket 状态丢失"))
                    evaluateOnPage("window.__starbotWsBridge.close($socketId, 1000)")
                }
            }
            "fail" -> {
                val pending = pendingDials.remove(socketId)
                sessions.remove(socketId)
                pending?.future?.completeExceptionally(
                    BrowserWsHandshakeException("浏览器桥 WebSocket 握手被远端拒绝")
                )
            }
            "m" -> {
                val payload = Base64.getDecoder().decode(event.getString("d"))
                if (session != null && session.delegateOpen) {
                    runCatching { session.handler.handleMessage(session, BinaryMessage(payload)) }
                        .onFailure { log.error("浏览器桥消息分发异常: socketId={}", socketId, it) }
                }
            }
            "close" -> {
                val pending = pendingDials.remove(socketId)
                val active = sessions.remove(socketId)
                val closeStatus = CloseStatus(event.getIntValue("c"), event.getString("r") ?: "")
                if (pending != null) {
                    pending.future.completeExceptionally(
                        BrowserWsHandshakeException("浏览器桥握手期间连接关闭: code=${closeStatus.code}")
                    )
                }
                if (active != null && active.delegateOpen) {
                    active.delegateOpen = false
                    runCatching { active.handler.afterConnectionClosed(active, closeStatus) }
                        .onFailure { log.error("浏览器桥连接关闭回调异常: socketId={}", socketId, it) }
                }
            }
        }
    }

    private fun ensureBridgePage() {
        val runtime = runtime ?: throw BrowserWsHandshakeException("浏览器能力宿主不可用")
        val navigator = navigator ?: throw BrowserWsHandshakeException("浏览器页面导航器不可用")
        val started = usable()
        if (!started) throw BrowserWsHandshakeException("浏览器能力未启动且自动启动失败")
        synchronized(pageLock) {
            if (pageHealthy && bridgePage != null) return
            bridgePage?.let { runCatching { runtime.closePage(it) } }
            bridgePage = null
            pageHealthy = false
            val page = runtime.createPage()
            try {
                // about:blank 起页后导航到 live 域: 直接 createPage(目标 URL) 会因跨进程导航
                // 目标替换而死页(此前 CDP 采集已踩过), 必须保持同一惯用法。
                navigator.navigate(page, "https://live.bilibili.com/")
                val origin = evaluateOnPage(page, "location.origin")
                if (origin != "https://live.bilibili.com") {
                    error("浏览器桥页面 origin 不正确: $origin")
                }
                val installed = evaluateOnPage(page, BRIDGE_SCRIPT)
                if (installed != "installed" && installed != "ready") {
                    error("浏览器桥脚本注入失败: $installed")
                }
                bridgePage = page
                pageHealthy = true
                log.info("浏览器桥页面已就绪: origin=https://live.bilibili.com, socketBridge=installed")
            } catch (error: Throwable) {
                runCatching { runtime.closePage(page) }
                throw error
            }
        }
    }

    private fun evaluateOnPage(script: String): Any? {
        val page = bridgePage ?: throw BrowserWsHandshakeException("浏览器桥页面尚未就绪")
        return evaluateOnPage(page, script)
    }

    private fun evaluateOnPage(page: BrowserPage, script: String): Any? {
        val result = page.connection.call("Runtime.evaluate", linkedMapOf(
            "expression" to "(function(){ $script })()",
            "returnByValue" to true,
            "awaitPromise" to false,
        ), 10)
        result.getJSONObject("exceptionDetails")?.let { details ->
            error("浏览器桥页面脚本异常: ${details.getJSONObject("exception")?.getString("description") ?: details.toJSONString()}")
        }
        return result.getJSONObject("result")?.get("value")
    }

    private fun markPageUnhealthy(error: Throwable) {
        pageHealthy = false
        log.warn("浏览器桥页面不可用, 在建连接全部失败并将按需重建页面: {}", error.toString())
        val deadSessions = sessions.values.toList()
        val deadPending = pendingDials.values.toList()
        sessions.clear()
        pendingDials.clear()
        for (session in deadSessions) {
            session.delegateOpen = false
            runCatching { session.handler.afterConnectionClosed(session, CloseStatus.SESSION_NOT_RELIABLE) }
                .onFailure { log.error("浏览器桥失效关闭回调异常: socketId={}", session.socketId, it) }
        }
        for (pending in deadPending) {
            pending.future.completeExceptionally(BrowserWsHandshakeException("浏览器桥页面失效", error))
        }
        synchronized(pageLock) {
            bridgePage?.let { page -> runtime?.let { runCatching { it.closePage(page) } } }
            bridgePage = null
        }
    }

    @PreDestroy
    fun shutdown() {
        pump.shutdownNow()
        synchronized(pageLock) {
            bridgePage?.let { page -> runtime?.let { runCatching { it.closePage(page) } } }
            bridgePage = null
            pageHealthy = false
        }
    }

    class BrowserWebSocketSession(
        private val client: BrowserLiveWsClient,
        val socketId: Long,
        val handler: WebSocketHandler,
        private val uri: URI,
        private val handshakeHeaders: WebSocketHttpHeaders?,
    ) : WebSocketSession {
        @Volatile var delegateOpen = false

        override fun getId(): String = "browser-ws-$socketId"
        override fun getUri(): URI = uri
        override fun getHandshakeHeaders(): org.springframework.http.HttpHeaders = handshakeHeaders ?: org.springframework.http.HttpHeaders()
        override fun getAttributes(): Map<String, Any> = emptyMap()
        override fun getPrincipal(): java.security.Principal? = null
        override fun getLocalAddress(): InetSocketAddress = InetSocketAddress.createUnresolved("browser-bridge", 0)
        override fun getRemoteAddress(): InetSocketAddress = InetSocketAddress.createUnresolved(uri.host, uri.port)
        override fun getAcceptedProtocol(): String = ""
        override fun setTextMessageSizeLimit(messageSizeLimit: Int) {}
        override fun getTextMessageSizeLimit(): Int = Int.MAX_VALUE
        override fun setBinaryMessageSizeLimit(messageSizeLimit: Int) {}
        override fun getBinaryMessageSizeLimit(): Int = Int.MAX_VALUE
        override fun getExtensions(): List<WebSocketExtension> = emptyList()

        override fun sendMessage(message: WebSocketMessage<*>) {
            when (message) {
                is BinaryMessage -> client.sendBinary(socketId, message.payload.array())
                is TextMessage -> throw UnsupportedOperationException("弹幕通道只发送二进制帧")
                else -> throw UnsupportedOperationException("不支持的浏览器桥消息类型: ${message.javaClass.simpleName}")
            }
        }

        override fun isOpen(): Boolean = delegateOpen
        override fun close() = close(CloseStatus.NORMAL)
        override fun close(status: CloseStatus) {
            delegateOpen = false
            client.closeSocket(socketId, status)
        }
    }

    private companion object {
        /**
         * 页面内桥实现: dial 打开原生 WebSocket 并把 open/message/close 事件与二进制帧
         * (base64)推进事件队列, JVM 侧轮询 drain() 消费; send/close 反向驱动。
         * 'fail' 仅在 CONNECTING 阶段上报, 打开后失败一律走 'close', 避免重复终结。
         */
        val BRIDGE_SCRIPT = """
            (() => {
              if (window.__starbotWsBridge) return 'ready';
              const bridge = { socks: new Map(), events: [] };
              const toB64 = (buf) => {
                const u = new Uint8Array(buf); let s = '';
                for (let i = 0; i < u.length; i += 0x8000) s += String.fromCharCode.apply(null, u.subarray(i, i + 0x8000));
                return btoa(s);
              };
              const fromB64 = (s) => {
                const bin = atob(s); const u = new Uint8Array(bin.length);
                for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
                return u.buffer;
              };
              bridge.dial = (id, url) => {
                try {
                  if (bridge.socks.has(id)) return { ok: false, error: 'duplicate' };
                  const ws = new WebSocket(url);
                  ws.binaryType = 'arraybuffer';
                  bridge.socks.set(id, ws);
                  ws.onopen = () => bridge.events.push({ t: 'open', id });
                  ws.onmessage = (ev) => { try { bridge.events.push({ t: 'm', id, d: toB64(ev.data) }); } catch (e) {} };
                  ws.onclose = (ev) => {
                    bridge.events.push({ t: 'close', id, c: ev.code || 1006, r: String(ev.reason || '') });
                    bridge.socks.delete(id);
                  };
                  ws.onerror = () => {
                    if (ws.readyState === WebSocket.CONNECTING) bridge.events.push({ t: 'fail', id });
                  };
                  return { ok: true };
                } catch (e) { return { ok: false, error: String(e) }; }
              };
              bridge.send = (id, d) => {
                const ws = bridge.socks.get(id);
                if (!ws || ws.readyState !== WebSocket.OPEN) return { ok: false, gone: true };
                try { ws.send(fromB64(d)); return { ok: true }; } catch (e) { return { ok: false, error: String(e) }; }
              };
              bridge.close = (id, code) => {
                const ws = bridge.socks.get(id);
                if (ws) { try { ws.close(code > 0 ? code : 1000); } catch (e) { try { ws.close(); } catch (e2) {} } }
                return { ok: true };
              };
              bridge.drain = () => bridge.events.splice(0, bridge.events.length);
              window.__starbotWsBridge = bridge;
              return 'installed';
            })()
        """.trimIndent()
    }
}
