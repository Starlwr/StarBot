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
    override fun handle(event: StarBotExternalBaseEvent, pushMessage: PushMessage) {
        if (event is RandomGiftEvent) BlindBoxStatsStore.record(event)
    }
    override fun getDefaultParams() = JSONObject().apply { put("note", "record blind-box statistics") }
}

/** Select this handler for the live-on event to start a clean report session. */
@StarBotComponent
class BlindBoxLiveOnResetHandler : StarBotEventHandler {
    override fun handle(event: StarBotExternalBaseEvent, pushMessage: PushMessage) = BlindBoxStatsStore.reset(event)
    override fun getDefaultParams() = JSONObject().apply { put("note", "reset blind-box statistics on live start") }
}

/** Select this handler for the live-off event to send the migrated blind-box section. */
@StarBotComponent
class BlindBoxLiveOffReportHandler(private val sender: StarBotMessageSender) : StarBotEventHandler {
    override fun handle(event: StarBotExternalBaseEvent, pushMessage: PushMessage) {
        val params = getDefaultParams().apply { pushMessage.paramsJsonObject?.let(::putAll) }
        val stats = BlindBoxStatsStore.snapshot(event)
        if (stats == null && params.getBooleanValue("only_when_non_empty", true)) return
        if (stats != null && stats.boxCount == 0L && params.getBooleanValue("only_when_non_empty", true)) return

        val raw = params.getString("message")
        val content = raw
            .replace("{uname}", stats?.uname ?: event.source.uname ?: "")
            .replace("{box_count}", (stats?.boxCount ?: 0).toString())
            .replace("{cost}", BlindBoxStatsStore.format(stats?.cost ?: 0.0))
            .replace("{value}", BlindBoxStatsStore.format(stats?.value ?: 0.0))
            .replace("{profit}", BlindBoxStatsStore.format(stats?.profit ?: 0.0))
            .replace("{user_count}", (stats?.userCount ?: 0).toString())
            .replace("{top_gifts}", stats?.topGifts(params.getIntValue("top_limit", 5)) ?: "无")
        val target = pushMessage.target
        Message.create(target.platform, target.type, target.num, content).forEach(sender::send)
    }

    override fun getDefaultParams() = JSONObject().apply {
        put("only_when_non_empty", true); put("top_limit", 5)
        put("message", "{uname} 本场盲盒统计\n盲盒次数: {box_count}\n盲盒成本: {cost}\n开出价值: {value}\n盈亏: {profit}\n参与人数: {user_count}\nTOP礼物: {top_gifts}")
    }
}
