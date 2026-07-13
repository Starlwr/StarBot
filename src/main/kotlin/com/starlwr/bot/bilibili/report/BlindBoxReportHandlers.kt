package com.starlwr.bot.bilibili.report

import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.core.event.StarBotExternalBaseEvent
import com.starlwr.bot.core.event.live.common.RandomGiftEvent
import com.starlwr.bot.core.handler.DefaultHandlerForEvent
import com.starlwr.bot.core.handler.StarBotEventHandler
import com.starlwr.bot.core.model.Message
import com.starlwr.bot.core.model.PushMessage
import com.starlwr.bot.core.plugin.StarBotComponent
import com.starlwr.bot.core.sender.StarBotMessageSender

@StarBotComponent
@DefaultHandlerForEvent(event = "com.starlwr.bot.bilibili.event.live.BilibiliRandomGiftEvent")
class BlindBoxRecordHandler : StarBotEventHandler {
    /** Compatibility handler; collection is performed once by LiveReportCollector. */
    override fun handle(event: StarBotExternalBaseEvent, pushMessage: PushMessage) = Unit
    override fun getDefaultParams() = JSONObject().apply { put("note", "record blind-box statistics") }
}

/** Select this handler for the live-on event to start a clean report session. */
@StarBotComponent
class BlindBoxLiveOnResetHandler : StarBotEventHandler {
    override fun handle(event: StarBotExternalBaseEvent, pushMessage: PushMessage) = Unit
    override fun getDefaultParams() = JSONObject().apply { put("note", "reset blind-box statistics on live start") }
}

/** Select this handler for the live-off event to send the migrated blind-box section. */
@StarBotComponent
class BlindBoxLiveOffReportHandler(private val sender: StarBotMessageSender, private val collector: LiveReportCollector) : StarBotEventHandler {
    override fun handle(event: StarBotExternalBaseEvent, pushMessage: PushMessage) {
        val params = getDefaultParams().apply { pushMessage.paramsJsonObject?.let(::putAll) }
        val stats = collector.completed(event)
        val count = stats?.counts?.get("box") ?: 0L
        if (count == 0L && params.getBooleanValue("only_when_non_empty", true)) return

        val raw = params.getString("message")
        val content = raw
            .replace("{uname}", stats?.uname ?: event.source.uname ?: "")
            .replace("{box_count}", count.toString())
            .replace("{cost}", fmt((stats?.values?.get("box") ?: 0.0) - (stats?.profits?.get("box") ?: 0.0)))
            .replace("{value}", fmt(stats?.values?.get("box") ?: 0.0))
            .replace("{profit}", fmt(stats?.profits?.get("box") ?: 0.0))
            .replace("{user_count}", (stats?.users?.get("box")?.size ?: 0).toString())
            .replace("{top_gifts}", stats?.labels?.get("box")?.entries?.sortedByDescending { it.value }
                ?.take(params.getIntValue("top_limit", 5))?.joinToString(", ") { "${it.key} x${it.value}" }?.ifEmpty { "无" } ?: "无")
        val target = pushMessage.target
        Message.create(target.platform, target.type, target.num, content).forEach(sender::send)
    }

    override fun getDefaultParams() = JSONObject().apply {
        put("only_when_non_empty", true); put("top_limit", 5)
        put("message", "{uname} 本场盲盒统计\n盲盒次数: {box_count}\n盲盒成本: {cost}\n开出价值: {value}\n盈亏: {profit}\n参与人数: {user_count}\nTOP礼物: {top_gifts}")
    }
    private fun fmt(value: Double) = java.text.DecimalFormat("0.##").format(value)
}
