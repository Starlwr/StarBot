package com.starlwr.bot.bilibili.report

import com.starlwr.bot.bilibili.model.Room
import com.starlwr.bot.core.event.StarBotExternalBaseEvent
import com.starlwr.bot.core.service.LiveDataService
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import com.starlwr.bot.core.plugin.StarBotComponent
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class ReportSessionStartResult(val snapshot: LiveReportSnapshot, val createdFull: Boolean)

@StarBotComponent
class LiveReportSessionManager(
    private val driver: LiveReportDataDriver,
    private val liveDataService: LiveDataService,
    private val recoveryProperties: LiveReportRecoveryProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val active = ConcurrentHashMap<Long, ReportSession>()
    private val completed = ConcurrentHashMap<Long, LiveReportSnapshot>()

    @PostConstruct
    fun restoreOpenSessions() {
        runCatching { driver.openSessions() }.onSuccess { snapshots ->
            snapshots.groupBy { it.uid }.forEach { (uid, sessions) ->
                val ordered = sessions.sortedBy { it.startedAt }
                ordered.dropLast(1).forEach { stale ->
                    closeAbnormal(stale.sessionId, ordered.last().startedAt, "duplicate_open_session_recovered")
                    log.warn("已异常收口重复的未完成直播报告 Session: uid={}, stale={}, retained={}",
                        uid, stale.sessionId, ordered.last().sessionId)
                }
                ordered.lastOrNull()?.let { active[uid] = session(it) }
            }
            if (snapshots.isNotEmpty()) log.info("已载入 {} 个未收口直播报告 Session，等待 Recovery 校验", snapshots.size)
        }.onFailure { log.warn("载入未收口直播报告 Session 失败；正常事件采集仍将继续: {}", it.toString()) }
    }

    @Synchronized
    fun onLive(event: StarBotExternalBaseEvent): ReportSessionStartResult {
        val uid = event.source.uid ?: 0
        val eventStart = liveStart(event)
        active[uid]?.let { current ->
            val snapshot = driver.snapshot(current.sessionId)
            if (snapshot != null && sameLive(snapshot, eventStart)) {
                val updated = driver.updateLifecycle(current.sessionId, SessionLifecycleUpdate(
                    lifecycleState = ReportLifecycleState.ACTIVE, recoveryStatus = ReportRecoveryStatus.RECOVERED,
                    apiLiveStartedAt = eventStart, lastSuccessfulProbeAt = event.timestamp,
                    lastRecoveredAt = event.timestamp, lastRecoveryReason = "realtime_live_start",
                    recoveryIncrement = 1, clearPending = true)) ?: snapshot
                active[uid] = session(updated)
                return ReportSessionStartResult(updated, false)
            }
            closeAbnormal(current.sessionId, event.timestamp, "replaced_by_realtime_live_start")
        }
        val created = ReportSession(
            sessionId = "${event.platform}:$uid:$eventStart", platform = event.platform, uid = uid,
            roomId = event.source.roomId ?: 0, uname = event.source.uname ?: "", startedAt = eventStart,
            collectionStartedAt = event.timestamp, baselineType = ReportBaselineType.FULL,
            apiLiveStartedAt = eventStart,
        )
        val snapshot = driver.createOrResume(created)
        active[uid] = session(snapshot); completed.remove(uid)
        return ReportSessionStartResult(snapshot, true)
    }

    @Synchronized
    fun onOff(event: StarBotExternalBaseEvent): LiveReportSnapshot? {
        val uid = event.source.uid ?: return null
        val current = active.remove(uid) ?: return null
        val before = driver.snapshot(current.sessionId)
        val disposition = if (before?.lifecycleState == ReportLifecycleState.PENDING_CLOSE)
            ReportCloseDisposition.ABNORMAL else ReportCloseDisposition.NORMAL
        val reason = if (disposition == ReportCloseDisposition.ABNORMAL) "live_end_after_interrupted_session" else "live_end_event"
        val result = driver.complete(current.sessionId, liveEnd(event, before?.startedAt ?: current.startedAt), disposition, reason)
        if (result != null) completed[uid] = result
        return result
    }

    fun interactionSession(event: StarBotExternalBaseEvent): ReportSession {
        val uid = event.source.uid ?: 0
        active[uid]?.let { current ->
            val snapshot = driver.snapshot(current.sessionId)
            if (snapshot?.lifecycleState == ReportLifecycleState.PENDING_CLOSE) {
                val resumed = driver.updateLifecycle(current.sessionId, SessionLifecycleUpdate(
                    lifecycleState = ReportLifecycleState.ACTIVE, recoveryStatus = ReportRecoveryStatus.RECOVERED,
                    lastRecoveredAt = event.timestamp, lastRecoveryReason = "realtime_interaction",
                    recoveryIncrement = 1, clearPending = true))
                if (resumed != null) active[uid] = session(resumed)
            }
            return active[uid] ?: current
        }
        synchronized(this) {
            active[uid]?.let { return it }
            val liveStart = liveDataService.getLiveStartTime(event.platform, uid).orElse(event.timestamp)
                .takeIf { it > 0 && it <= event.timestamp } ?: event.timestamp
            val partial = ReportSession(
                "${event.platform}:$uid:$liveStart", event.platform, uid, event.source.roomId ?: 0,
                event.source.uname ?: "", liveStart, event.timestamp, ReportBaselineType.PARTIAL,
            )
            val snapshot = driver.createOrResume(partial)
            active[uid] = session(snapshot); completed.remove(uid)
            log.info("直播报告在直播中途建立 PARTIAL Session: session={}, collectionStartedAt={}",
                snapshot.sessionId, snapshot.reportStartedAt())
            return active[uid]!!
        }
    }

    fun completed(event: StarBotExternalBaseEvent): LiveReportSnapshot? =
        event.source.uid?.let { completed[it]?.copySafe() }

    fun activeSnapshot(uid: Long): LiveReportSnapshot? = active[uid]?.let { driver.snapshot(it.sessionId) }
    fun openSnapshots(): List<LiveReportSnapshot> = runCatching { driver.openSessions() }.getOrElse {
        log.warn("读取未收口报告 Session 失败: {}", it.toString()); emptyList()
    }

    fun shouldCollectBaseline(event: StarBotExternalBaseEvent): Boolean {
        val snapshot = event.source.uid?.let(::activeSnapshot) ?: return false
        return snapshot.baselineType == ReportBaselineType.FULL && snapshot.metadata.keys.none { it.startsWith("before_") }
    }

    fun recordMetadata(event: StarBotExternalBaseEvent, phase: String, values: Map<String, Long>) {
        val uid = event.source.uid ?: return
        val current = active[uid] ?: completed[uid]?.let(::session) ?: return
        driver.apply(current, "metadata:$phase:${event.timestamp}", ReportDelta(ReportMetric.DANMU,
            occurredAt = 0, metadata = values.mapKeys { "${phase}_${it.key}" }))
        if (phase == "after") driver.snapshot(current.sessionId)?.let { completed[uid] = it }
    }

    @Synchronized
    fun recoverLive(room: Room, observedAt: Long): LiveReportSnapshot {
        val uid = room.uid ?: 0
        val apiStart = room.getLiveStartTime()?.takeIf { it > 0 }
        active[uid]?.let { current ->
            val snapshot = driver.snapshot(current.sessionId)
            if (snapshot != null && (apiStart == null || sameLive(snapshot, apiStart))) {
                val collectionStart = if (snapshot.baselineType == ReportBaselineType.PARTIAL && snapshot.collectionStartedAt <= 0)
                    observedAt else null
                val updated = driver.updateLifecycle(current.sessionId, SessionLifecycleUpdate(
                    lifecycleState = ReportLifecycleState.ACTIVE, recoveryStatus = ReportRecoveryStatus.RECOVERED,
                    apiLiveStartedAt = apiStart, collectionStartedAt = collectionStart,
                    lastSuccessfulProbeAt = observedAt,
                    lastRecoveredAt = observedAt, lastRecoveryReason = "api_live_same_session",
                    recoveryIncrement = 1, clearPending = true)) ?: snapshot
                active[uid] = session(updated)
                return updated
            }
            closeAbnormal(current.sessionId, observedAt, "api_live_start_changed")
        }
        val identityStart = apiStart ?: observedAt
        val partial = ReportSession("bilibili:$uid:$identityStart", "bilibili", uid, room.roomId ?: 0,
            room.uname ?: "", identityStart, observedAt, ReportBaselineType.PARTIAL, apiStart)
        val snapshot = driver.createOrResume(partial)
        active[uid] = session(snapshot); completed.remove(uid)
        log.info("Recovery 建立 PARTIAL 直播报告 Session: session={}, apiLiveStartedAt={}, collectionStartedAt={}",
            snapshot.sessionId, apiStart, observedAt)
        return snapshot
    }

    @Synchronized
    fun observeOffline(uid: Long, observedAt: Long): LiveReportSnapshot? {
        val current = active[uid] ?: return null
        val snapshot = driver.snapshot(current.sessionId) ?: return null
        val update = SessionLifecycleUpdate(
            lifecycleState = ReportLifecycleState.PENDING_CLOSE,
            pendingCloseSince = snapshot.pendingCloseSince ?: observedAt,
            lastSuccessfulProbeAt = observedAt,
            lastRecoveryReason = "api_offline",
        )
        return driver.updateLifecycle(current.sessionId, update)?.also { active[uid] = session(it) }
    }

    fun markProbeFailure(uid: Long, reason: String): LiveReportSnapshot? {
        val current = active[uid] ?: return null
        return driver.updateLifecycle(current.sessionId, SessionLifecycleUpdate(
            recoveryStatus = ReportRecoveryStatus.RECOVERY_FAILED, lastRecoveryReason = reason))
            ?.also { active[uid] = session(it) }
    }

    @Synchronized
    fun closeAbnormal(uid: Long, endedAt: Long, reason: String): LiveReportSnapshot? {
        val current = active.remove(uid) ?: return null
        return closeAbnormal(current.sessionId, endedAt, reason)?.also { completed[uid] = it }
    }

    private fun closeAbnormal(sessionId: String, endedAt: Long, reason: String): LiveReportSnapshot? =
        driver.complete(sessionId, endedAt, ReportCloseDisposition.ABNORMAL, reason)

    fun sameLive(snapshot: LiveReportSnapshot, observedStart: Long): Boolean {
        if (observedStart <= 0) return true
        val stored = snapshot.apiLiveStartedAt?.takeIf { it > 0 } ?: snapshot.startedAt
        return abs(stored - observedStart) <= recoveryProperties.sameSessionTolerance.toMillis()
    }

    private fun session(snapshot: LiveReportSnapshot) = ReportSession(snapshot.sessionId, snapshot.platform,
        snapshot.uid, snapshot.roomId, snapshot.uname, snapshot.startedAt, snapshot.reportStartedAt(),
        snapshot.baselineType, snapshot.apiLiveStartedAt)

    private fun liveStart(event: StarBotExternalBaseEvent): Long {
        val uid = event.source.uid ?: return event.timestamp
        val sourceStart = (event.source as? Room)?.getLiveStartTime()
        val storedStart = liveDataService.getLiveStartTime(event.platform, uid).orElse(null)
        return sequenceOf(sourceStart, storedStart, event.timestamp)
            .filterNotNull().firstOrNull { it > 0 && it <= event.timestamp + recoveryProperties.sameSessionTolerance.toMillis() }
            ?: event.timestamp
    }

    private fun liveEnd(event: StarBotExternalBaseEvent, startedAt: Long): Long {
        val uid = event.source.uid ?: return event.timestamp
        return liveDataService.getLiveEndTime(event.platform, uid).orElse(event.timestamp)
            .takeIf { it >= startedAt } ?: event.timestamp
    }
}
