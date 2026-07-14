package com.starlwr.bot.bilibili.report

import com.starlwr.bot.core.event.StarBotExternalBaseEvent
import com.starlwr.bot.core.event.live.base.StarBotLiveInteractionEvent
import com.starlwr.bot.core.event.live.common.*
import com.starlwr.bot.core.plugin.StarBotComponent
import com.starlwr.bot.core.service.LiveDataService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@StarBotComponent
class LiveReportCollector(
    private val driver: LiveReportDataDriver,
    private val demandService: LiveReportDemandService,
    private val liveDataService: LiveDataService
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val active = ConcurrentHashMap<String, ReportSession>()
    private val latest = ConcurrentHashMap<String, ReportSession>()
    private val completed = ConcurrentHashMap<String, LiveReportSnapshot>()

    @EventListener fun onLive(event: LiveOnEvent) {
        if (!demandService.forUid(event.source.uid).enabled) return
        val key = roomKey(event)
        if (event.isReconnect && active.containsKey(key)) return
        // Bilibili LIVE carries the authoritative live_time. Using it directly
        // avoids observing stale LiveData while other event listeners update it.
        val start = event.timestamp
        val session = ReportSession("${event.platform}:${event.source.uid}:$start", event.platform,
            event.source.uid ?: 0, event.source.roomId ?: 0, event.source.uname ?: "", start)
        driver.createOrResume(session)
        completed.remove(key)
        active[key] = session
        latest[key] = session
        val demand = demandService.forUid(event.source.uid)
        log.info("直播报告采集会话已开始, session={}, sections={}, charts={}, wordCloud={}",
            session.sessionId, demand.sections, demand.charts, demand.wordCloud)
    }

    @EventListener fun onOff(event: LiveOffEvent) {
        if (!demandService.forUid(event.source.uid).enabled && !active.containsKey(roomKey(event))) return
        val key = roomKey(event); val session = active.remove(key) ?: sessionFor(event)
        latest[key] = session
        driver.complete(session.sessionId, liveEnd(event))?.let {
            completed[key] = it
            log.info("直播报告采集会话已完成, session={}, counts={}", session.sessionId, it.counts)
        } ?: log.warn("直播报告采集会话完成时未找到快照, session={}", session.sessionId)
    }

    @EventListener fun onDanmu(event: DanmuEvent) = commit(event, ReportDelta(
        ReportMetric.DANMU, count = 1, user = user(event, 1), occurredAt = event.timestamp,
        text = event.contentText?.take(500)?.takeIf { demandService.forUid(event.source.uid).wordCloud }))

    @EventListener fun onEmoji(event: EmojiEvent) = commit(event, ReportDelta(
        ReportMetric.DANMU, count = 1, user = user(event, 1), occurredAt = event.timestamp,
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

    fun completed(event: StarBotExternalBaseEvent): LiveReportSnapshot? = active[roomKey(event)]
        ?.let { driver.snapshot(it.sessionId) } ?: completed[roomKey(event)]

    fun recordMetadata(event: StarBotExternalBaseEvent, phase: String, values: Map<String, Long>) {
        val key = roomKey(event)
        val session = active[key] ?: latest[key] ?: completed[key]?.let {
            ReportSession(it.sessionId, it.platform, it.uid, it.roomId, it.uname, it.startedAt)
        } ?: sessionFor(event)
        latest[key] = session
        driver.apply(session, "metadata:$phase:${event.timestamp}", ReportDelta(ReportMetric.DANMU,
            occurredAt = 0, metadata = values.mapKeys { "${phase}_${it.key}" }))
        if (phase == "after") driver.snapshot(session.sessionId)?.let { completed[key] = it }
    }

    private fun commit(event: StarBotLiveInteractionEvent, delta: ReportDelta) {
        if (!demandService.forUid(event.source.uid).enabled) return
        val key = roomKey(event)
        val session = active.computeIfAbsent(key) {
            sessionFor(event).also { created ->
                latest[key] = created
                log.info("直播报告在直播中途建立采集会话, session={}", created.sessionId)
            }
        }
        val demand = demandService.forUid(event.source.uid)
        val metricKey = delta.metric.name.lowercase()
        val adjusted = if (metricKey in demand.charts || (delta.metric == ReportMetric.BOX && "box_profit" in demand.charts)) delta
            else delta.copy(occurredAt = 0)
        driver.apply(session, eventId(event, delta), adjusted)
    }
    private fun sessionFor(event: StarBotExternalBaseEvent): ReportSession {
        val start = liveStart(event)
        return ReportSession("${event.platform}:${event.source.uid}:$start", event.platform,
            event.source.uid ?: 0, event.source.roomId ?: 0, event.source.uname ?: "", start)
    }
    private fun liveStart(event: StarBotExternalBaseEvent): Long {
        val uid = event.source.uid ?: return event.timestamp
        return liveDataService.getLiveStartTime(event.platform, uid).orElse(event.timestamp)
            .takeIf { it > 0 && it <= event.timestamp } ?: event.timestamp
    }
    private fun liveEnd(event: StarBotExternalBaseEvent): Long {
        val uid = event.source.uid ?: return event.timestamp
        return liveDataService.getLiveEndTime(event.platform, uid).orElse(event.timestamp)
            .takeIf { it >= liveStart(event) } ?: event.timestamp
    }
    private fun user(event: StarBotLiveInteractionEvent, count: Long, value: Double = 0.0, profit: Double = 0.0): ReportUserDelta? =
        event.sender?.let { ReportUserDelta((it.uid ?: it.uname.hashCode().toLong()).toString(), it.uname ?: "", it.face, count, value, profit) }
    private fun roomKey(event: StarBotExternalBaseEvent) = "${event.platform}:${event.source.uid}:${event.source.roomId}"
    private fun eventId(event: StarBotExternalBaseEvent, delta: ReportDelta): String {
        val raw = listOf(event.platform, event.source.uid, event.source.roomId, event.javaClass.name, event.timestamp,
            delta.metric, delta.user?.uid, delta.count, delta.value, delta.profit, delta.text, delta.label).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
    }
}
