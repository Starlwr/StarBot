package com.starlwr.bot.bilibili.report

import com.alibaba.fastjson2.JSON
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class ReportMetric { DANMU, BOX, GIFT, SC, GUARD }

enum class ReportBaselineType { FULL, PARTIAL }
enum class ReportLifecycleState { ACTIVE, PENDING_CLOSE, CLOSED }
enum class ReportRecoveryStatus { NORMAL, RECOVERED, RECOVERY_FAILED }
enum class ReportCloseDisposition { NORMAL, ABNORMAL }

data class SessionLifecycleUpdate(
    val lifecycleState: ReportLifecycleState? = null,
    val recoveryStatus: ReportRecoveryStatus? = null,
    val baselineType: ReportBaselineType? = null,
    val apiLiveStartedAt: Long? = null,
    val collectionStartedAt: Long? = null,
    val pendingCloseSince: Long? = null,
    val lastSuccessfulProbeAt: Long? = null,
    val lastRecoveredAt: Long? = null,
    val lastRecoveryReason: String? = null,
    val recoveryIncrement: Long = 0,
    val reportEligible: Boolean? = null,
    val clearPending: Boolean = false,
)

data class ReportUserDelta(
    val uid: String, val uname: String = "", val face: String? = null,
    val count: Long = 0, val value: Double = 0.0, val profit: Double = 0.0
)

data class ReportDelta(
    val metric: ReportMetric,
    val count: Long = 0,
    val value: Double = 0.0,
    val profit: Double = 0.0,
    val user: ReportUserDelta? = null,
    val occurredAt: Long = System.currentTimeMillis(),
    val text: String? = null,
    val label: String? = null,
    val metadata: Map<String, Long> = emptyMap()
)

data class ReportUserStats(
    var uname: String = "", var face: String? = null,
    var count: Long = 0, var value: Double = 0.0, var profit: Double = 0.0
)

data class LiveReportSnapshot(
    var schemaVersion: Int = CURRENT_SCHEMA,
    var sessionId: String = "",
    var platform: String = "bilibili",
    var uid: Long = 0,
    var roomId: Long = 0,
    var uname: String = "",
    var startedAt: Long = 0,
    var collectionStartedAt: Long = 0,
    var apiLiveStartedAt: Long? = null,
    var endedAt: Long? = null,
    var baselineType: ReportBaselineType = ReportBaselineType.FULL,
    var lifecycleState: ReportLifecycleState = ReportLifecycleState.ACTIVE,
    var recoveryStatus: ReportRecoveryStatus = ReportRecoveryStatus.NORMAL,
    var recoveryCount: Long = 0,
    var lastRecoveredAt: Long? = null,
    var lastRecoveryReason: String? = null,
    var pendingCloseSince: Long? = null,
    var lastSuccessfulProbeAt: Long? = null,
    var closeDisposition: ReportCloseDisposition? = null,
    var closeReason: String? = null,
    var reportEligible: Boolean = true,
    var firstInteractionAt: Long? = null,
    var lastEventAt: Long? = null,
    var counts: MutableMap<String, Long> = ConcurrentHashMap(),
    var values: MutableMap<String, Double> = ConcurrentHashMap(),
    var profits: MutableMap<String, Double> = ConcurrentHashMap(),
    var users: MutableMap<String, MutableMap<String, ReportUserStats>> = ConcurrentHashMap(),
    var buckets: MutableMap<String, MutableMap<Long, Double>> = ConcurrentHashMap(),
    var labels: MutableMap<String, MutableMap<String, Long>> = ConcurrentHashMap(),
    var metadata: MutableMap<String, Long> = ConcurrentHashMap(),
    var danmuTexts: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
) {
    @Synchronized fun apply(delta: ReportDelta, maxTexts: Int = 20_000) {
        val key = delta.metric.name.lowercase()
        counts.merge(key, delta.count, Long::plus)
        values.merge(key, delta.value, Double::plus)
        profits.merge(key, delta.profit, Double::plus)
        delta.label?.let { labels.computeIfAbsent(key) { ConcurrentHashMap() }.merge(it, delta.count.coerceAtLeast(1), Long::plus) }
        metadata.putAll(delta.metadata)
        delta.user?.let { d ->
            val stat = users.computeIfAbsent(key) { ConcurrentHashMap() }
                .computeIfAbsent(d.uid) { ReportUserStats(d.uname, d.face) }
            stat.uname = d.uname.ifBlank { stat.uname }; stat.face = d.face ?: stat.face
            stat.count += d.count; stat.value += d.value; stat.profit += d.profit
        }
        if (delta.occurredAt > 0) {
            if (firstInteractionAt == null || delta.occurredAt < firstInteractionAt!!) firstInteractionAt = delta.occurredAt
            if (lastEventAt == null || delta.occurredAt > lastEventAt!!) lastEventAt = delta.occurredAt
            // v2 records interaction time rather than minute-only samples. One-second
            // aggregation preserves its 20-bin curve while bounding long sessions.
            val second = delta.occurredAt / 1_000 * 1_000
            buckets.computeIfAbsent(key) { ConcurrentHashMap() }.merge(second,
                if (delta.value != 0.0) delta.value else delta.count.toDouble(), Double::plus)
        }
        if (delta.metric == ReportMetric.BOX && delta.profit != 0.0 && delta.occurredAt > 0) {
            buckets.computeIfAbsent("box_profit") { ConcurrentHashMap() }
                .merge(delta.occurredAt / 1_000 * 1_000, delta.profit, Double::plus)
        }
        delta.text?.takeIf { it.isNotBlank() && danmuTexts.size < maxTexts }?.let(danmuTexts::add)
    }

    fun reportStartedAt(): Long = if (baselineType == ReportBaselineType.PARTIAL)
        collectionStartedAt.takeIf { it > 0 } ?: startedAt else startedAt

    fun updateLifecycle(update: SessionLifecycleUpdate) {
        update.lifecycleState?.let { lifecycleState = it }
        update.recoveryStatus?.let { recoveryStatus = it }
        update.baselineType?.let { baselineType = it }
        update.apiLiveStartedAt?.let { apiLiveStartedAt = it }
        update.collectionStartedAt?.let { collectionStartedAt = it }
        update.pendingCloseSince?.let { pendingCloseSince = it }
        update.lastSuccessfulProbeAt?.let { lastSuccessfulProbeAt = it }
        update.lastRecoveredAt?.let { lastRecoveredAt = it }
        update.lastRecoveryReason?.let { lastRecoveryReason = it }
        recoveryCount += update.recoveryIncrement.coerceAtLeast(0)
        update.reportEligible?.let { reportEligible = it }
        if (update.clearPending) {
            pendingCloseSince = null
        }
    }

    fun copySafe(): LiveReportSnapshot = JSON.parseObject(JSON.toJSONString(this), LiveReportSnapshot::class.java)
    companion object { const val CURRENT_SCHEMA = 3 }
}

data class ReportSession(
    val sessionId: String, val platform: String, val uid: Long, val roomId: Long,
    val uname: String, val startedAt: Long = Instant.now().toEpochMilli(),
    val collectionStartedAt: Long = startedAt,
    val baselineType: ReportBaselineType = ReportBaselineType.FULL,
    val apiLiveStartedAt: Long? = null,
) {
    fun snapshot() = LiveReportSnapshot(sessionId = sessionId, platform = platform, uid = uid,
        roomId = roomId, uname = uname, startedAt = startedAt, collectionStartedAt = collectionStartedAt,
        baselineType = baselineType, apiLiveStartedAt = apiLiveStartedAt)
}

object LiveReportSchemaMigration {
    fun migrate(snapshot: LiveReportSnapshot): LiveReportSnapshot {
        require(snapshot.schemaVersion <= LiveReportSnapshot.CURRENT_SCHEMA) { "Unsupported future report schema ${snapshot.schemaVersion}" }
        while (snapshot.schemaVersion < LiveReportSnapshot.CURRENT_SCHEMA) when (snapshot.schemaVersion) {
            0 -> snapshot.schemaVersion = 1
            1 -> snapshot.schemaVersion = 2 // Epoch-millis bucket keys remain compatible; new data is second-precise.
            2 -> {
                val hasBefore = snapshot.metadata.keys.any { it.startsWith("before_") }
                snapshot.baselineType = if (hasBefore) ReportBaselineType.FULL else ReportBaselineType.PARTIAL
                snapshot.collectionStartedAt = if (hasBefore || snapshot.endedAt != null) snapshot.startedAt
                    else snapshot.buckets.values.asSequence().flatMap { it.keys.asSequence() }.minOrNull() ?: 0
                snapshot.lifecycleState = if (snapshot.endedAt == null) ReportLifecycleState.ACTIVE else ReportLifecycleState.CLOSED
                snapshot.closeDisposition = snapshot.endedAt?.let { ReportCloseDisposition.NORMAL }
                snapshot.reportEligible = true
                snapshot.schemaVersion = 3
            }
            else -> error("No report migration from schema ${snapshot.schemaVersion}")
        }
        return snapshot
    }
}
