package com.starlwr.bot.bilibili.report

import com.alibaba.fastjson2.JSON
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class ReportMetric { DANMU, BOX, GIFT, SC, GUARD }

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
    var endedAt: Long? = null,
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
            val minute = delta.occurredAt / 60_000 * 60_000
            buckets.computeIfAbsent(key) { ConcurrentHashMap() }.merge(minute,
                if (delta.value != 0.0) delta.value else delta.count.toDouble(), Double::plus)
        }
        if (delta.metric == ReportMetric.BOX && delta.profit != 0.0 && delta.occurredAt > 0) {
            buckets.computeIfAbsent("box_profit") { ConcurrentHashMap() }
                .merge(delta.occurredAt / 60_000 * 60_000, delta.profit, Double::plus)
        }
        delta.text?.takeIf { it.isNotBlank() && danmuTexts.size < maxTexts }?.let(danmuTexts::add)
    }

    fun copySafe(): LiveReportSnapshot = JSON.parseObject(JSON.toJSONString(this), LiveReportSnapshot::class.java)
    companion object { const val CURRENT_SCHEMA = 1 }
}

data class ReportSession(
    val sessionId: String, val platform: String, val uid: Long, val roomId: Long,
    val uname: String, val startedAt: Long = Instant.now().toEpochMilli()
) {
    fun snapshot() = LiveReportSnapshot(sessionId = sessionId, platform = platform, uid = uid,
        roomId = roomId, uname = uname, startedAt = startedAt)
}

object LiveReportSchemaMigration {
    fun migrate(snapshot: LiveReportSnapshot): LiveReportSnapshot {
        require(snapshot.schemaVersion <= LiveReportSnapshot.CURRENT_SCHEMA) { "Unsupported future report schema ${snapshot.schemaVersion}" }
        while (snapshot.schemaVersion < LiveReportSnapshot.CURRENT_SCHEMA) when (snapshot.schemaVersion) {
            0 -> snapshot.schemaVersion = 1
            else -> error("No report migration from schema ${snapshot.schemaVersion}")
        }
        return snapshot
    }
}
