package com.starlwr.bot.bilibili.report

import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.bilibili.event.live.BilibiliDisconnectedEvent
import com.starlwr.bot.bilibili.http.BilibiliHttpPipeline
import com.starlwr.bot.bilibili.model.Room
import com.starlwr.bot.core.event.datasource.other.StarBotDataSourceLoadCompleteEvent
import com.starlwr.bot.core.plugin.StarBotComponent
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class LiveStatusObservation(val room: Room, val observedAt: Long, val timeSource: String)

@StarBotComponent
class LiveReportTrustedTimeProvider(private val properties: LiveReportRecoveryProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client = HttpClient.newBuilder().connectTimeout(properties.trustedTimeTimeout).build()
    @Volatile private var cachedEpochMillis = 0L
    @Volatile private var cachedAtNanos = 0L

    fun resolve(headers: Map<String, List<String>>): Pair<Long, String> {
        val date = headers.entries.firstOrNull { it.key.equals("date", true) }?.value?.firstOrNull()
        parseHttpDate(date)?.let { return it to "bilibili-http-date" }
        cloudflareNow()?.let { return it to "cloudflare-trace" }
        return System.currentTimeMillis() to "local-clock"
    }

    fun now(): Long = cloudflareNow() ?: System.currentTimeMillis()

    private fun parseHttpDate(value: String?): Long? = value?.let {
        runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun cloudflareNow(): Long? {
        val nowNanos = System.nanoTime()
        if (cachedEpochMillis > 0 && nowNanos - cachedAtNanos <= properties.trustedTimeCache.toNanos())
            return cachedEpochMillis + (nowNanos - cachedAtNanos) / 1_000_000
        return runCatching {
            val request = HttpRequest.newBuilder(URI.create(properties.cloudflareTraceUrl))
                .timeout(properties.trustedTimeTimeout).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            require(response.statusCode() in 200..299) { "Cloudflare trace HTTP ${response.statusCode()}" }
            val seconds = response.body().lineSequence().firstOrNull { it.startsWith("ts=") }
                ?.substringAfter("ts=")?.toDoubleOrNull() ?: error("Cloudflare trace omitted ts")
            (seconds * 1_000).toLong().also { cachedEpochMillis = it; cachedAtNanos = System.nanoTime() }
        }.onFailure { log.debug("可信时间回退到本机时钟: {}", it.toString()) }.getOrNull()
    }
}

@StarBotComponent
class LiveReportStatusProbeClient(
    private val pipeline: BilibiliHttpPipeline,
    private val trustedTime: LiveReportTrustedTimeProvider,
) {
    fun probe(uids: Set<Long>): Map<Long, LiveStatusObservation> {
        if (uids.isEmpty()) return emptyMap()
        val result = LinkedHashMap<Long, LiveStatusObservation>()
        uids.chunked(100).forEach { chunk ->
            val url = "https://api.live.bilibili.com/room/v1/Room/get_status_info_by_uids?uids[]=" +
                chunk.joinToString("&uids[]=")
            val response = pipeline.get(url, channel = "bilibili-report-recovery")
            require(response.successful()) { "Recovery live status HTTP ${response.status}" }
            val root = response.json()
            require(root.getIntValue("code") == 0) { "Recovery live status code=${root.getIntValue("code")}" }
            val data = root.getJSONObject("data") ?: JSONObject()
            val (observedAt, source) = trustedTime.resolve(response.headers)
            data.forEach { (uidText, value) ->
                val node = value as? JSONObject ?: return@forEach
                val uid = uidText.toLongOrNull() ?: return@forEach
                val room = Room(uid, node.getString("uname"), node.getLong("room_id"), node.getString("face"),
                    node.getInteger("live_status"), node.getLongValue("live_time") * 1_000,
                    node.getString("title"), node.getString("cover_from_user"))
                result[uid] = LiveStatusObservation(room, observedAt, source)
            }
        }
        return result
    }
}

@StarBotComponent
class LiveReportRecoveryService(
    private val properties: LiveReportRecoveryProperties,
    private val demandService: LiveReportDemandService,
    private val sessions: LiveReportSessionManager,
    private val probeClient: LiveReportStatusProbeClient,
    private val trustedTime: LiveReportTrustedTimeProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val enabled = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val retrySince = ConcurrentHashMap<Long, Long>()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "live-report-recovery").apply { isDaemon = true }
    }

    @PostConstruct
    fun initialize() {
        if (!properties.enabled) return
        if (!properties.valid()) {
            log.error("直播报告 Recovery 配置无效，已仅禁用 Recovery；事件采集和基础推送不受影响")
            return
        }
        enabled.set(true)
        val interval = properties.pendingCloseRetryInterval.toMillis().coerceAtLeast(1_000)
        scheduler.scheduleWithFixedDelay(::tickSafely, interval, interval, TimeUnit.MILLISECONDS)
    }

    @Order(10_000)
    @EventListener
    fun onDataSourceLoaded(@Suppress("UNUSED_PARAMETER") event: StarBotDataSourceLoadCompleteEvent) {
        if (!enabled.get()) return
        val uids = demandService.enabledUids()
        submitTask {
            val now = trustedTime.now()
            sessions.openSnapshots().filter { it.uid !in uids }.forEach {
                sessions.closeAbnormal(it.uid, now, "report_disabled")
            }
            probeWithRetry(uids, "startup")
        }
    }

    @EventListener
    fun onDisconnected(event: BilibiliDisconnectedEvent) {
        val uid = event.source.uid ?: return
        if (!enabled.get() || !demandService.forUid(uid).enabled || sessions.activeSnapshot(uid) == null) return
        submitProbe(setOf(uid), "collector_disconnected")
    }

    private fun submitProbe(uids: Set<Long>, reason: String) {
        if (uids.isEmpty() || closing.get()) return
        submitTask { probeWithRetry(uids, reason) }
    }

    private fun probeWithRetry(uids: Set<Long>, reason: String) {
        if (uids.isEmpty() || closing.get()) return
        val now = trustedTime.now()
        uids.forEach { retrySince.putIfAbsent(it, now) }
        probeSafely(uids, reason)
    }

    private fun submitTask(task: () -> Unit) {
        try {
            scheduler.execute(task)
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            if (!closing.get()) log.warn("直播报告 Recovery 任务提交失败: {}", error.toString())
        }
    }

    private fun tickSafely() {
        if (closing.get()) return
        val pending = sessions.openSnapshots().filter { it.lifecycleState == ReportLifecycleState.PENDING_CLOSE }
            .map { it.uid }.toSet()
        probeSafely((pending + retrySince.keys).toSet(), "scheduled_retry")
    }

    private fun probeSafely(uids: Set<Long>, reason: String) {
        runCatching { probe(uids, reason) }.onFailure { error ->
            val now = trustedTime.now()
            uids.forEach { uid -> handleFailure(uid, now, error.toString()) }
            log.warn("直播报告 Recovery 探测失败，不影响实时事件链: reason={}, uids={}, error={}", reason, uids, error.toString())
        }
    }

    private fun probe(uids: Set<Long>, reason: String) {
        if (uids.isEmpty()) return
        val observations = probeClient.probe(uids)
        val missing = uids - observations.keys
        val failureNow = trustedTime.now()
        missing.forEach { handleFailure(it, failureNow, "status_missing") }
        observations.forEach { (uid, observation) ->
            if (observation.room.getLiveStatus() == 1) {
                sessions.recoverLive(observation.room, observation.observedAt)
                retrySince.remove(uid)
                log.info("直播报告 Recovery 确认直播继续: uid={}, apiStart={}, timeSource={}, reason={}",
                    uid, observation.room.getLiveStartTime(), observation.timeSource, reason)
            } else {
                val pending = sessions.observeOffline(uid, observation.observedAt)
                retrySince.remove(uid)
                if (pending != null && observation.observedAt - (pending.pendingCloseSince ?: observation.observedAt) >=
                    properties.pendingCloseDelay.toMillis()) {
                    sessions.closeAbnormal(uid, observation.observedAt, "pending_close_confirmed_offline")
                    log.warn("直播报告 Session 已在下播复核后异常收口，不发送报告: uid={}, session={}", uid, pending.sessionId)
                }
            }
        }
    }

    private fun handleFailure(uid: Long, now: Long, reason: String) {
        val snapshot = sessions.markProbeFailure(uid, reason)
        val since = retrySince.computeIfAbsent(uid) { snapshot?.pendingCloseSince ?: now }
        if (snapshot == null) {
            if (now - since >= properties.pendingCloseMaxDuration.toMillis()) {
                retrySince.remove(uid)
                log.warn("直播报告 Recovery 连续失败已停止无 Session 主动探测: uid={}", uid)
            }
            return
        }
        val pendingSince = snapshot.pendingCloseSince
        if (pendingSince != null && now - pendingSince >= properties.pendingCloseMaxDuration.toMillis()) {
            sessions.closeAbnormal(uid, now, "pending_close_unconfirmed_timeout")
            retrySince.remove(uid)
            log.warn("直播报告 PENDING_CLOSE 已达到最大等待时间并异常收口，不发送报告: uid={}, session={}", uid, snapshot.sessionId)
        } else if (pendingSince == null && now - since >= properties.pendingCloseMaxDuration.toMillis()) {
            retrySince.remove(uid)
            log.warn("直播报告 Recovery 连续失败已停止主动探测，Session 保持活动等待实时事件: uid={}, session={}", uid, snapshot.sessionId)
        }
    }

    @PreDestroy
    fun close() {
        if (!closing.compareAndSet(false, true)) return
        scheduler.shutdownNow()
    }
}
