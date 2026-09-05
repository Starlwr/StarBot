package com.starlwr.bot.bilibili.report

import com.starlwr.bot.core.event.StarBotExternalBaseEvent
import com.starlwr.bot.core.event.live.base.StarBotLiveInteractionEvent
import com.starlwr.bot.core.event.live.common.*
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOnEvent
import com.starlwr.bot.core.plugin.StarBotComponent
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@StarBotComponent
class LiveReportCollector(
    private val driver: LiveReportDataDriver,
    private val demandService: LiveReportDemandService,
    private val sessions: LiveReportSessionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Order(-1_000) @EventListener fun onLive(event: LiveOnEvent) {
        if (!demandService.forUid(event.source.uid).enabled) return
        val result = sessions.onLive(event)
        val demand = demandService.forUid(event.source.uid)
        log.info("直播报告采集 Session 已{}, session={}, baseline={}, origin={}, sections={}, charts={}, wordCloud={}",
            if (result.createdFull) "开始" else "恢复", result.snapshot.sessionId, result.snapshot.baselineType,
            eventOrigin(event), demand.sections, demand.charts, demand.wordCloud)
    }

    @Order(-1_000) @EventListener fun onOff(event: LiveOffEvent) {
        sessions.onOff(event)?.let {
            log.info("直播报告采集 Session 已完成, session={}, disposition={}, origin={}, counts={}",
                it.sessionId, it.closeDisposition, eventOrigin(event), it.counts)
        } ?: log.debug("实时下播事件没有对应的活动报告 Session, uid={}", event.source.uid)
    }

    @EventListener fun onDanmu(event: DanmuEvent) = commit(event, ReportDelta(
        ReportMetric.DANMU, count = 1, user = user(event, 1), occurredAt = event.timestamp,
        label = "normal",
        text = event.contentText?.take(500)?.takeIf { demandService.forUid(event.source.uid).wordCloud }))

    @EventListener fun onEmoji(event: EmojiEvent) = commit(event, ReportDelta(
        ReportMetric.DANMU, count = 1, user = user(event, 1), occurredAt = event.timestamp,
        label = "emoji",
        text = event.emoji?.name?.take(500)?.takeIf { demandService.forUid(event.source.uid).wordCloud }))

    @EventListener fun onGift(event: PaidGiftEvent) {
        val gift = event.giftInfo; val count = (gift?.count ?: 1).coerceAtLeast(1)
        val value = event.value ?: ((gift?.price ?: 0.0) * count)
        commit(event, ReportDelta(ReportMetric.GIFT, count.toLong(), value,
            user = user(event, count.toLong(), value), occurredAt = event.timestamp, label = gift?.name))
    }

    @EventListener fun onBox(event: RandomGiftEvent) {
        val box = event.randomGiftInfo; val result = event.giftInfo
        val count = (box?.count ?: 1).coerceAtLeast(1); val resultCount = (result?.count ?: 1).coerceAtLeast(1)
        val cost = event.price ?: ((box?.price ?: 0.0) * count)
        val profit = event.profit ?: ((result?.price ?: 0.0) * resultCount - cost)
        commit(event, ReportDelta(ReportMetric.BOX, count.toLong(), cost + profit, profit,
            user(event, count.toLong(), cost + profit, profit), event.timestamp, label = result?.name))
    }

    @EventListener fun onSc(event: SuperChatEvent) {
        val value = event.value ?: 0.0
        commit(event, ReportDelta(ReportMetric.SC, 1, value, user = user(event, 1, value),
            occurredAt = event.timestamp, text = event.content?.take(500)))
    }

    @EventListener fun onGuard(event: MembershipEvent) {
        val count = (event.count ?: 1).coerceAtLeast(1); val value = (event.value ?: event.price ?: 0.0) * count
        val level = when { event.javaClass.simpleName.contains("Governor") -> "governor"
            event.javaClass.simpleName.contains("Commander") -> "commander"
            event.javaClass.simpleName.contains("Captain") -> "captain" else -> event.unit ?: "guard" }
        commit(event, ReportDelta(ReportMetric.GUARD, count.toLong(), value,
            user = user(event, count.toLong(), value), occurredAt = event.timestamp, label = level))
    }

    fun completed(event: StarBotExternalBaseEvent): LiveReportSnapshot? = sessions.completed(event)

    fun shouldCollectBaseline(event: StarBotExternalBaseEvent): Boolean = sessions.shouldCollectBaseline(event)

    fun recordMetadata(event: StarBotExternalBaseEvent, phase: String, values: Map<String, Long>) {
        sessions.recordMetadata(event, phase, values)
    }

    private fun commit(event: StarBotLiveInteractionEvent, delta: ReportDelta) {
        if (!demandService.forUid(event.source.uid).enabled) return
        val session = sessions.interactionSession(event)
        val demand = demandService.forUid(event.source.uid)
        val metricKey = delta.metric.name.lowercase()
        val adjusted = if (metricKey in demand.charts || (delta.metric == ReportMetric.BOX && "box_profit" in demand.charts)) delta
            else delta.copy(occurredAt = 0)
        driver.apply(session, eventId(event, delta), adjusted)
    }
    private fun user(event: StarBotLiveInteractionEvent, count: Long, value: Double = 0.0, profit: Double = 0.0): ReportUserDelta? =
        event.sender?.let { ReportUserDelta((it.uid ?: it.uname.hashCode().toLong()).toString(), it.uname ?: "", it.face, count, value, profit) }
    private fun eventId(event: StarBotExternalBaseEvent, delta: ReportDelta): String {
        val raw = listOf(event.platform, event.source.uid, event.source.roomId, event.javaClass.name, event.timestamp,
            delta.metric, delta.user?.uid, delta.count, delta.value, delta.profit, delta.text, delta.label).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
    }

    private fun eventOrigin(event: StarBotExternalBaseEvent): String = when (event) {
        is BilibiliLiveOnEvent -> event.getOrigin().name
        is BilibiliLiveOffEvent -> event.getOrigin().name
        else -> "REALTIME"
    }
}
