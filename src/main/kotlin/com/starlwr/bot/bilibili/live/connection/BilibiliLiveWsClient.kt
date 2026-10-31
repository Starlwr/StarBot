package com.starlwr.bot.bilibili.live.connection

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties
import org.slf4j.LoggerFactory
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.WebSocketClient
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * 弹幕 WebSocket 传输路由器。
 *
 * 按 `starbot.bilibili.live.ws-transport` 选择 JVM TLS 直连或浏览器桥:
 * - jvm: 恒走 JVM(默认, 未启用浏览器模块时的安全值);
 * - browser: 浏览器桥可用即走桥, 不可用回落 JVM 并告警;
 * - auto: 默认 JVM, 房间出现 TLS 层异常断开(noteTlsAbnormal)后该房间粘滞改走浏览器桥。
 */
class BilibiliLiveWsClient(
    private val jvmClient: WebSocketClient,
    private val browserClient: BrowserLiveWsClient?,
    private val properties: StarBotBilibiliProperties,
) : WebSocketClient {

    private val log = LoggerFactory.getLogger(javaClass)
    private val preferBrowser = ConcurrentHashMap.newKeySet<String>()

    @JvmOverloads
    fun executeForRoom(roomId: String, handler: WebSocketHandler, headers: WebSocketHttpHeaders, uri: URI, browserIdentity: Boolean = false): CompletableFuture<WebSocketSession> {
        val mode = properties.getLive().getWsTransport().trim().lowercase()
        val wantBrowser = browserIdentity || when (mode) {
            "browser" -> true
            "auto" -> preferBrowser.contains(roomId)
            else -> false
        }
        val browserUsable = wantBrowser && browserClient?.usable() == true
        if (browserIdentity && !browserUsable) {
            return CompletableFuture.failedFuture(BrowserWsHandshakeException("Browser identity requires BrowserDialer; JVM fallback prohibited"))
        }
        if (wantBrowser && !browserUsable) {
            log.warn("浏览器桥不可用(ws-transport={}, 浏览器模块={}), 房间 {} 回落 JVM 直连", mode, browserClient != null, roomId)
        }
        val useBrowser = wantBrowser && browserUsable
        if (useBrowser) {
            log.info("房间 {} 弹幕连接改经浏览器桥(真实浏览器 TLS 栈): uri={}", roomId, uri)
        }
        val client = if (useBrowser) browserClient!! else jvmClient
        return client.execute(handler, headers, uri)
    }

    /** 房间出现 TLS 层异常断开(auto 模式下粘滞改走浏览器桥)。 */
    fun noteTlsAbnormal(roomId: String) {
        if (!"auto".equals(properties.getLive().getWsTransport().trim(), ignoreCase = true)) return
        if (preferBrowser.add(roomId)) {
            log.info("房间 {} 出现 TLS 层异常断开, auto 模式下后续连接改走浏览器桥", roomId)
        }
    }

    override fun execute(handler: WebSocketHandler, uriTemplate: String, vararg uriVariables: Any?): CompletableFuture<WebSocketSession> =
        jvmClient.execute(handler, uriTemplate, *uriVariables)

    override fun execute(handler: WebSocketHandler, headers: WebSocketHttpHeaders?, uri: URI): CompletableFuture<WebSocketSession> =
        jvmClient.execute(handler, headers, uri)
}
