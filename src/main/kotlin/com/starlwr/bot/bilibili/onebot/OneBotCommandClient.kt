package com.starlwr.bot.bilibili.onebot

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.bilibili.log.BilibiliNetworkLogger
import com.starlwr.bot.bilibili.report.BlindBoxCommandController
import com.starlwr.bot.core.enums.PushTargetType
import com.starlwr.bot.core.model.Message
import com.starlwr.bot.core.plugin.StarBotComponent
import com.starlwr.bot.core.sender.StarBotMessageSender
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.event.EventListener
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@ConfigurationProperties("starbot.bilibili.onebot-command")
class OneBotCommandProperties {
    var enabled: Boolean = true
    var websocketUrl: String = "ws://127.0.0.1:3000/"
    var accessToken: String = ""
    var botQq: Long = 0
    var senderPlatform: String = "qq-onebot"
    var owners: Set<Long> = emptySet()
    var reconnectSeconds: Long = 3
}

/**
 * Inbound OneBot command bridge. The official adapter remains responsible for
 * regular push delivery; this client only restores v2-style interactive commands.
 */
@StarBotComponent
@EnableConfigurationProperties(OneBotCommandProperties::class)
class OneBotCommandClient(
    private val properties: OneBotCommandProperties,
    private val sender: StarBotMessageSender,
    private val reportCommands: BlindBoxCommandController,
    private val networkLog: BilibiliNetworkLogger
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "onebot-command-reconnect").apply { isDaemon = true }
    }
    private val connecting = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile private var connectFuture: CompletableFuture<WebSocket>? = null
    @Volatile private var reconnectTask: ScheduledFuture<*>? = null
    @Volatile private var socket: WebSocket? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!properties.enabled) {
            log.info("OneBot interactive commands are disabled")
            return
        }
        connect()
    }

    private fun connect() {
        if (closed.get() || !connecting.compareAndSet(false, true)) return
        val builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
        val headers = linkedMapOf<String, String>()
        if (properties.accessToken.isNotBlank()) {
            headers["Authorization"] = "Bearer ${properties.accessToken}"
            builder.header("Authorization", headers.getValue("Authorization"))
        }
        val trace = networkLog.httpRequest("onebot-command-ws-handshake", "GET", properties.websocketUrl, headers, null)
        val future = builder.buildAsync(URI.create(properties.websocketUrl), Listener())
        connectFuture = future
        future.whenComplete { webSocket, error ->
            connecting.set(false)
            connectFuture = null
            if (closed.get()) {
                webSocket?.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown")
                return@whenComplete
            }
            if (error != null) {
                networkLog.httpFailure(trace, error)
                log.warn("OneBot command WebSocket connection failed: {}", error.toString())
                scheduleReconnect()
            } else {
                networkLog.httpResponse(trace, 101, emptyMap<String, String>(), "<websocket-upgrade>")
                socket = webSocket
                log.info("OneBot interactive command WebSocket connected: {}", properties.websocketUrl)
            }
        }
    }

    private fun scheduleReconnect() {
        if (closed.get()) return
        try {
            reconnectTask?.cancel(false)
            reconnectTask = scheduler.schedule({
                reconnectTask = null
                if (!closed.get()) connect()
            }, properties.reconnectSeconds.coerceAtLeast(1), TimeUnit.SECONDS)
        } catch (error: RejectedExecutionException) {
            if (!closed.get()) {
                log.warn("OneBot command WebSocket reconnect scheduling failed: {}", error.toString())
            }
        }
    }

    private fun handle(raw: String) {
        val event = runCatching { JSON.parseObject(raw) }.getOrNull() ?: return
        if (event.getString("post_type") != "message") return
        val selfId = event.getLongValue("self_id")
        if (properties.botQq > 0 && selfId != properties.botQq) return
        val userId = event.getLongValue("user_id")
        if (userId == selfId) return
        val messageType = event.getString("message_type")
        val groupId = event.getLongValue("group_id")
        val command = normalize(event.getString("raw_message").orEmpty())
        when {
            command in HELP_COMMANDS -> reply(messageType, userId, groupId, helpText(userId))
            command in STATUS_COMMANDS -> reply(messageType, userId, groupId,
                "StarBot v3 正在运行\nOneBot 入站命令连接正常\nBot QQ: ${properties.botQq}")
            command.startsWith(REPORT_COMMAND) || command.startsWith(BLIND_BOX_COMMAND) -> {
                event["raw_message"] = command
                reportCommands.onOneBotEvent(event).getString("reply")?.takeIf(String::isNotBlank)
                    ?.let { reply(messageType, userId, groupId, it) }
            }
        }
    }

    private fun normalize(raw: String): String {
        var text = raw.trim()
        if (properties.botQq > 0) {
            text = text.replace(Regex("^\\[CQ:at,qq=${properties.botQq}(?:,[^]]*)?]\\s*"), "")
        }
        return text.trim().lowercase()
    }

    private fun helpText(userId: Long): String = buildString {
        appendLine("StarBot v3 帮助")
        appendLine("帮助 / help：显示此帮助")
        appendLine("状态 / status：检查机器人与 OneBot 命令连接")
        appendLine("直播报告：查询当前会话关联主播最近一场报告")
        appendLine("盲盒统计 一周：查询最近一周盲盒汇总")
        appendLine("盲盒统计 一月：查询最近一月盲盒汇总")
        append("盲盒统计 YYYY-MM-DD [YYYY-MM-DD]：按日期查询")
        if (userId in properties.owners) append("\n当前账号拥有主人权限")
    }

    private fun reply(messageType: String?, userId: Long, groupId: Long, message: String) {
        val targetType = if (messageType == "group") PushTargetType.GROUP else PushTargetType.FRIEND
        val target = if (targetType == PushTargetType.GROUP) groupId else userId
        val messages = Message.create(properties.senderPlatform, targetType, target, message)
        messages.forEach(sender::send)
        log.debug("OneBot command reply OUT via sender={}, target={}:{}, body={}",
            properties.senderPlatform, targetType, target, message)
        log.info("OneBot command reply queued through {}, target={}:{}, messages={}",
            properties.senderPlatform, targetType, target, messages.size)
    }

    @PreDestroy
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        reconnectTask?.cancel(false)
        reconnectTask = null
        connectFuture?.cancel(true)
        connectFuture = null
        networkLog.websocketOut("onebot-command", null, "CLOSE", "shutdown".toByteArray(StandardCharsets.UTF_8).size,
            "shutdown", false)
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown")
        socket = null
        scheduler.shutdownNow()
    }

    private inner class Listener : WebSocket.Listener {
        private val buffer = StringBuilder()
        override fun onOpen(webSocket: WebSocket) {
            if (closed.get()) webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown") else webSocket.request(1)
        }
        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            synchronized(buffer) {
                buffer.append(data)
                if (last) {
                    val message = buffer.toString()
                    buffer.setLength(0)
                    networkLog.websocketIn("onebot-command", null, "TEXT",
                        message.toByteArray(StandardCharsets.UTF_8).size, message, false)
                    handle(message)
                }
            }
            webSocket.request(1)
            return null
        }
        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            socket = null
            if (!closed.get()) { log.warn("OneBot command WebSocket closed ({}: {}), reconnecting", statusCode, reason); scheduleReconnect() }
            return null
        }
        override fun onError(webSocket: WebSocket, error: Throwable) {
            socket = null
            if (!closed.get()) { log.warn("OneBot command WebSocket error: {}", error.toString()); scheduleReconnect() }
        }
    }

    companion object {
        private val HELP_COMMANDS = setOf("帮助", "help", "/help", "菜单", "starbot帮助")
        private val STATUS_COMMANDS = setOf("状态", "status", "/status")
        private const val REPORT_COMMAND = "直播报告"
        private const val BLIND_BOX_COMMAND = "盲盒统计"
    }
}
