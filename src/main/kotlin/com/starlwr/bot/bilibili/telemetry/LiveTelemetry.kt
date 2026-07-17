package com.starlwr.bot.bilibili.telemetry

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.bilibili.credential.BilibiliCredentialFileStore
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOnEvent
import com.starlwr.bot.bilibili.http.BilibiliHttpPipeline
import com.starlwr.bot.bilibili.util.BilibiliApiUtil
import com.starlwr.bot.core.plugin.StarBotComponent
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.event.EventListener
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties("starbot.bilibili.telemetry")
class LiveTelemetryProperties {
    var webLogEnabled: Boolean = false
    var golPostwebEnabled: Boolean = false
    var legacy000916Enabled: Boolean = false
    var watchTrackerEnabled: Boolean = false
    var legacyRdataHeartbeatEnabled: Boolean = false
    var legacy000916IntervalSeconds: Long = 15
    var legacyRdataIntervalSeconds: Long = 30
    var webLogDefaultIntervalSeconds: Long = 50
    var webLogMinimumIntervalSeconds: Long = 10
    var webLogMaximumIntervalSeconds: Long = 300
    var golFlushSeconds: Long = 60
}

data class WebLogReportData(
    val uid: Long,
    val buvid: String,
    val platform: String = "web",
    val screenStatus: Int,
    val clickStatus: Int,
    val roomId: Long,
    val playUrl: String,
    val qid: Long,
    val sid: String,
    val cts: Long,
    val stky: String,
)

@StarBotComponent
class CsnSigner {
    private val algorithms = listOf("HmacSHA256", "HmacSHA224", "HmacSHA1", "HmacSHA384", "HmacMD5", "HmacSHA512")

    fun sign(data: WebLogReportData): String {
        val canonical = linkedMapOf<String, Any>(
            "uid" to data.uid, "buvid" to data.buvid, "platform" to data.platform,
            "screen_status" to data.screenStatus, "click_status" to data.clickStatus,
            "room_id" to data.roomId, "play_url" to data.playUrl, "qid" to data.qid,
            "sid" to data.sid, "cts" to data.cts, "stky" to data.stky,
        )
        val message = JSON.toJSONString(canonical).toByteArray(StandardCharsets.UTF_8)
        fun hmac(selector: Long): String {
            val algorithm = algorithms[Math.floorMod(selector, algorithms.size.toLong()).toInt()]
            val mac = Mac.getInstance(algorithm)
            mac.init(SecretKeySpec(data.stky.toByteArray(StandardCharsets.UTF_8), algorithm))
            return mac.doFinal(message).joinToString("") { "%02x".format(it) }
        }
        val h0 = hmac(data.screenStatus.toLong())
        val h1 = hmac(data.clickStatus.toLong())
        val h2 = hmac(data.qid)
        val h3 = hmac(data.cts)
        return h0.substring(8, 16) + h1.substring(0, 8) + h2.takeLast(8) + h3.substring(h3.length - 16, h3.length - 8)
    }
}

class WebLogSession(
    private val context: LiveClientContext,
    private val properties: LiveTelemetryProperties,
    private val http: BilibiliHttpPipeline,
    private val credentials: BilibiliCredentialFileStore,
    private val playUrlProvider: PlayUrlProvider,
    private val signer: CsnSigner,
    private val scheduler: TaskScheduler,
    private val executor: Executor,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()
    private var sid = ""
    private var stky = ""
    private var qid = 0L
    private var interval = properties.webLogDefaultIntervalSeconds
    private var failures = 0
    private var timer: ScheduledFuture<*>? = null
    @Volatile private var stopped = false

    fun start() {
        if (stopped) return
        runCatching {
            val response = send("https://data.bilivideo.com/log/web/te9Kl", includeCsn = false)
            applyResponse(response)
            qid++
            val confirmation = send("https://data.bilivideo.com/log/web/te9Kl", includeCsn = true)
            applyResponse(confirmation)
            qid++
            failures = 0
            schedule()
        }.onFailure(::failed)
    }

    private fun heartbeat() {
        if (stopped) return
        runCatching {
            val response = send("https://data.bilivideo.com/log/web/s82Tq", includeCsn = true)
            applyResponse(response)
            qid++
            failures = 0
        }.onFailure(::failed)
        if (!stopped) schedule()
    }

    private fun send(url: String, includeCsn: Boolean): JSONObject {
        val lease = playUrlProvider.get(context.roomId)
        val snapshot = credentials.snapshot() ?: error("Credential unavailable")
        val liveBuvid = snapshot.cookies.firstOrNull { it.name.equals("LIVE_BUVID", true) }?.value
            ?: error("LIVE_BUVID unavailable")
        val data = WebLogReportData(
            snapshot.account.dedeUserId.toLongOrNull() ?: 0L, liveBuvid,
            screenStatus = random.nextInt(100) + 1, clickStatus = random.nextInt(100) + 1,
            roomId = context.roomId, playUrl = lease.url, qid = qid,
            sid = sid, cts = System.currentTimeMillis(), stky = stky,
        )
        val body = linkedMapOf<String, Any>(
            "uid" to data.uid, "buvid" to data.buvid, "platform" to data.platform,
            "screen_status" to data.screenStatus, "click_status" to data.clickStatus,
            "room_id" to data.roomId, "play_url" to data.playUrl, "qid" to data.qid,
            "cts" to data.cts,
        )
        if (sid.isNotBlank()) body["sid"] = sid
        if (stky.isNotBlank()) body["stky"] = stky
        if (includeCsn) body["csn"] = signer.sign(data)
        val csrf = snapshot.account.biliJct
        val endpoint = if (url.endsWith("te9Kl") && csrf.isNotBlank()) "$url?csrf=${encode(csrf)}" else url
        val response = http.postJson(endpoint, mapOf("Referer" to "https://live.bilibili.com/${context.roomId}"),
            body, "bilibili-telemetry-weblog")
        require(response.successful()) { "WebLog HTTP ${response.status}" }
        return response.json()
    }

    private fun applyResponse(root: JSONObject) {
        require(root.getIntValue("code", -1) == 0) { "WebLog code=${root.getIntValue("code", -1)}" }
        var data = root.getJSONObject("data") ?: error("WebLog omitted data")
        data.getJSONObject("data")?.let { data = it }
        data.getString("sid")?.takeIf { it.isNotBlank() }?.let { sid = it }
        data.getString("stky")?.takeIf { it.isNotBlank() }?.let { stky = it }
        require(sid.isNotBlank() && stky.isNotBlank()) { "WebLog omitted sid/stky" }
        data.getLong("hbil")?.let {
            interval = it.coerceIn(properties.webLogMinimumIntervalSeconds, properties.webLogMaximumIntervalSeconds)
        }
    }

    private fun failed(error: Throwable) {
        failures++
        log.warn("WebLog 直播心跳失败: room={}, failures={}/3, reason={}", context.roomId, failures, error.toString())
        log.debug("WebLog failure detail", error)
        if (failures >= 3) close()
    }

    private fun schedule() {
        if (stopped) return
        timer?.cancel(false)
        timer = runCatching {
            scheduler.schedule({
                if (!stopped) executor.execute { if (!stopped) heartbeat() }
            }, Instant.now().plusSeconds(interval))
        }.getOrElse { error ->
            if (!stopped) failed(error)
            null
        }
    }

    override fun close() { stopped = true; timer?.cancel(false); timer = null }
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}

class GolPostwebTracker(
    private val context: LiveClientContext,
    private val properties: LiveTelemetryProperties,
    private val http: BilibiliHttpPipeline,
    private val scheduler: TaskScheduler,
    private val executor: Executor,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val uvid = UUID.randomUUID().toString()
    private val queue = mutableListOf<String>()
    private var timer: ScheduledFuture<*>? = null
    private val closed = AtomicBoolean()

    fun event(category: Int, code: Int, value: String = "", params: Any = emptyMap<String, Any>()) = synchronized(queue) {
        if (closed.get()) return@synchronized
        queue += record(category, code, value, params)
        if (queue.size >= 10) flushLocked() else if (timer == null) {
            timer = runCatching {
                scheduler.schedule({
                    if (!closed.get()) executor.execute { if (!closed.get()) flush() }
                }, Instant.now().plusSeconds(properties.golFlushSeconds))
            }.getOrElse { error ->
                if (!closed.get()) log.warn("gol/postweb 调度失败: room={}, reason={}", context.roomId, error.toString())
                null
            }
        }
    }

    fun flush() = synchronized(queue) { flushLocked() }
    private fun flushLocked(allowRetry: Boolean = true) {
        if (queue.isEmpty()) return
        val records = queue.toList(); queue.clear(); timer?.cancel(false); timer = null
        val body = records.joinToString("\u0003").toByteArray(StandardCharsets.UTF_8)
        runCatching {
            val response = http.postRaw("https://data.bilibili.com/gol/postweb",
                mapOf("Referer" to "https://live.bilibili.com/${context.roomId}"), body,
                "text/plain;charset=UTF-8", "bilibili-telemetry-gol")
            if (response.status == 429 && allowRetry) scheduleRetry(body, 0)
            else require(response.successful()) { "gol/postweb HTTP ${response.status}" }
        }.onFailure {
            if (closed.get()) log.debug("gol/postweb 关闭 flush 未完成: room={}, records={}, reason={}", context.roomId, records.size, it.toString())
            else log.warn("gol/postweb flush失败: room={}, records={}, reason={}", context.roomId, records.size, it.toString())
        }
    }

    private fun scheduleRetry(body: ByteArray, index: Int) {
        val delays = longArrayOf(10, 20, 40)
        if (closed.get() || index >= delays.size) return
        runCatching {
            scheduler.schedule({
                if (!closed.get()) executor.execute {
                    if (closed.get()) return@execute
                    runCatching {
                        val response = http.postRaw("https://data.bilibili.com/gol/postweb", emptyMap(), body,
                            "text/plain;charset=UTF-8", "bilibili-telemetry-gol-retry")
                        if (response.status == 429) scheduleRetry(body, index + 1)
                    }
                }
            }, Instant.now().plusSeconds(delays[index]))
        }.onFailure { if (!closed.get()) log.warn("gol/postweb 重试调度失败: room={}, reason={}", context.roomId, it.toString()) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(queue) {
            timer?.cancel(false)
            timer = null
            queue += record(2, 2, params = mapOf("reason" to "stop", "mediaPull" to false))
            flushLocked(allowRetry = false)
        }
    }

    private fun record(category: Int, code: Int, value: String = "", params: Any): String {
        val now = System.currentTimeMillis()
        val staticInfo = "$uvid|0|starbot-v3.0.0|${context.roomId}"
        return "009658$now$staticInfo|$now|$category|$code|$value|${JSON.toJSONString(params)}"
    }
}

private class ActiveTelemetrySession(
    val context: LiveClientContext,
    val webLog: WebLogSession?,
    val gol: GolPostwebTracker?,
    val tasks: MutableList<ScheduledFuture<*>> = CopyOnWriteArrayList(),
) : AutoCloseable {
    private val closed = AtomicBoolean()
    fun isClosed() = closed.get()
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        tasks.forEach { it.cancel(false) }
        tasks.clear()
        webLog?.close()
        gol?.close()
    }
}

@StarBotComponent
@EnableConfigurationProperties(LiveTelemetryProperties::class)
class LiveTelemetryCoordinator(
    private val properties: LiveTelemetryProperties,
    private val http: BilibiliHttpPipeline,
    private val credentials: BilibiliCredentialFileStore,
    private val playUrlProvider: PlayUrlProvider,
    private val signer: CsnSigner,
    private val scheduler: TaskScheduler,
    private val api: BilibiliApiUtil,
    @param:Qualifier("bilibiliThreadPool") private val telemetryExecutor: ThreadPoolTaskExecutor,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sessions = ConcurrentHashMap<Long, ActiveTelemetrySession>()
    private val closed = AtomicBoolean()

    @EventListener
    fun onLive(event: BilibiliLiveOnEvent) {
        if (closed.get()) return
        val roomId = event.source.roomId ?: return
        if (sessions.containsKey(roomId)) return
        submit("启动直播遥测") {
            runCatching {
                val lease = playUrlProvider.get(roomId)
                val context = LiveClientContext(roomId, lease.roomId, event.source.uid, lease.areaId, lease.parentAreaId,
                    event.timestamp, credentials.snapshot()?.identityRevision ?: 0, lease)
                val gol = if (properties.golPostwebEnabled) GolPostwebTracker(context, properties, http, scheduler, telemetryExecutor) else null
                val webLog = if (properties.webLogEnabled) WebLogSession(context, properties, http, credentials,
                    playUrlProvider, signer, scheduler, telemetryExecutor) else null
                val session = ActiveTelemetrySession(context, webLog, gol)
                if (closed.get() || sessions.putIfAbsent(roomId, session) != null) { session.close(); return@runCatching }
                gol?.event(2, 1, params = mapOf("event" to "client_init", "mediaPull" to false))
                webLog?.start()
                if (properties.legacy000916Enabled) scheduleLegacy000916(session)
                if (properties.legacyRdataHeartbeatEnabled) scheduleLegacyRdata(session)
                log.info("直播遥测会话已启动: room={}, webLog={}, gol={}, legacy000916={}, x25={}, rdata={}",
                    roomId, properties.webLogEnabled, properties.golPostwebEnabled,
                    properties.legacy000916Enabled, properties.watchTrackerEnabled,
                    properties.legacyRdataHeartbeatEnabled)
            }.onFailure { log.warn("启动直播遥测失败: room={}, reason={}", roomId, it.toString()) }
        }
    }

    @EventListener
    fun onLiveOff(event: BilibiliLiveOffEvent) {
        event.source.roomId?.let { sessions.remove(it)?.close() }
    }

    private fun scheduleLegacy000916(session: ActiveTelemetrySession) {
        if (closed.get() || session.isClosed()) return
        val guid = UUID.randomUUID().toString()
        var delta = 0L
        val task = scheduler.scheduleAtFixedRate({
            submit("000916 直播遥测") {
                if (session.isClosed()) return@submit
                val now = System.currentTimeMillis()
                val lease = playUrlProvider.get(session.context.roomId)
                val payload = linkedMapOf<String, Any>(
                    "room_id" to session.context.roomId, "up_id" to session.context.anchorUid,
                    "area" to session.context.areaId, "parent_area" to session.context.parentAreaId,
                    "guid" to guid, "play_type" to 1, "pid" to 0,
                    "playurl" to URLEncoder.encode(lease.url, StandardCharsets.UTF_8),
                    "delta_ts" to delta, "c_time" to now, "s_time" to now,
                    "version" to "starbot-v3.0.0", "relay_room_id" to 0,
                )
                delta += properties.legacy000916IntervalSeconds
                runCatching {
                    http.postJson("https://data.bilibili.com/log/web",
                        mapOf("Referer" to "https://live.bilibili.com/${session.context.roomId}"),
                        mapOf("logId" to "000916", "param" to payload), "bilibili-telemetry-legacy-000916")
                }
            }
        }, Duration.ofSeconds(properties.legacy000916IntervalSeconds))
        session.tasks += task
    }

    private fun scheduleLegacyRdata(session: ActiveTelemetrySession) {
        if (closed.get() || session.isClosed()) return
        val task = scheduler.scheduleAtFixedRate({ submit("rdata 直播心跳") {
            if (!session.isClosed()) api.liveRoomHeartbeat(session.context.roomId)
        } },
            Duration.ofSeconds(properties.legacyRdataIntervalSeconds))
        session.tasks += task
    }

    private fun submit(operation: String, action: () -> Unit) {
        if (closed.get()) return
        runCatching {
            telemetryExecutor.execute { if (!closed.get()) action() }
        }.onFailure { error ->
            if (!closed.get()) log.warn("{}提交失败: {}", operation, error.toString())
        }
    }

    @PreDestroy
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val active = sessions.values.toList()
        sessions.clear()
        active.forEach { it.close() }
    }
}
