package com.starlwr.bot.bilibili.onebot

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.bilibili.report.BlindBoxCommandController
import com.starlwr.bot.core.enums.PushTargetType
import com.starlwr.bot.core.model.Message
import com.starlwr.bot.core.plugin.StarBotComponent
import com.starlwr.bot.core.sender.StarBotMessageSender
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.event.EventListener
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@ConfigurationProperties("starbot.bilibili.onebot-command")
class OneBotCommandProperties {
    var enabled: Boolean = true
    var websocketUrl: String = "ws://127.0.0.1:3000/"
    var httpUrl: String = "http://127.0.0.1:3000"
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
    private val reportCommands: BlindBoxCommandController
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "onebot-command-reconnect").apply { isDaemon = true }
    }
    private val connecting = AtomicBoolean(false)
    @Volatile private var closed = false
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
        if (closed || !connecting.compareAndSet(false, true)) return
        val builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
        if (properties.accessToken.isNotBlank()) builder.header("Authorization", "Bearer ${properties.accessToken}")
        builder.buildAsync(URI.create(properties.websocketUrl), Listener()).whenComplete { webSocket, error ->
            connecting.set(false)
            if (error != null) {
                log.warn("OneBot command WebSocket connection failed: {}", error.toString())
                scheduleReconnect()
            } else {
                socket = webSocket
                log.info("OneBot interactive command WebSocket connected: {}", properties.websocketUrl)
            }
        }
    }

    private fun scheduleReconnect() {
        if (!closed) scheduler.schedule(::connect, properties.reconnectSeconds.coerceAtLeast(1), TimeUnit.SECONDS)
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
        log.info("OneBot command reply queued through {}, target={}:{}, messages={}",
            properties.senderPlatform, targetType, target, messages.size)
    }

    override fun close() {
        closed = true
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown")
        scheduler.shutdownNow()
    }

    private inner class Listener : WebSocket.Listener {
        private val buffer = StringBuilder()
        override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }
        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            synchronized(buffer) {
                buffer.append(data)
                if (last) { val message = buffer.toString(); buffer.setLength(0); handle(message) }
            }
            webSocket.request(1)
            return null
        }
        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            socket = null
            if (!closed) { log.warn("OneBot command WebSocket closed ({}: {}), reconnecting", statusCode, reason); scheduleReconnect() }
            return null
        }
        override fun onError(webSocket: WebSocket, error: Throwable) {
            socket = null
            if (!closed) { log.warn("OneBot command WebSocket error: {}", error.toString()); scheduleReconnect() }
        }
    }

    companion object {
        private val HELP_COMMANDS = setOf("帮助", "help", "/help", "菜单", "starbot帮助")
        private val STATUS_COMMANDS = setOf("状态", "status", "/status")
        private const val REPORT_COMMAND = "直播报告"
        private const val BLIND_BOX_COMMAND = "盲盒统计"
    }
}
