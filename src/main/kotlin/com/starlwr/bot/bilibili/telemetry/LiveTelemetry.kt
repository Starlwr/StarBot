package com.starlwr.bot.bilibili.telemetry

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.bilibili.credential.BilibiliCredentialFileStore
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOnEvent
import com.starlwr.bot.bilibili.http.BilibiliHttpPipeline
import com.starlwr.bot.bilibili.service.BilibiliFailureIncidentReporter
import com.starlwr.bot.bilibili.util.BilibiliApiUtil
import com.starlwr.bot.core.plugin.StarBotComponent
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.event.EventListener
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
    var executorSchedulerPoolSize: Int = 2
    var executorCorePoolSize: Int = 4
    var executorMaxPoolSize: Int = 16
    var executorQueueCapacity: Int = 1024
    var executorKeepAliveSeconds: Int = 60
    var executorSubmitTimeoutMillis: Long = 100
    var webLogStartMaxAttempts: Int = 3
    var webLogRebootstrapSeconds: Long = 300
    var webLogRebootstrapJitterSeconds: Long = 60
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
    private val dispatcher: TelemetryTaskDispatcher,
    private val incidentReporter: BilibiliFailureIncidentReporter? = null,
    private val onTerminalFailure: (WebLogFailure) -> Unit = {},
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()
    private var sid = ""
    private var stky = ""
    private var qid = 0L
    private var interval = properties.webLogDefaultIntervalSeconds
    private var failures = 0
    private var timer: ScheduledFuture<*>? = null
    private val stopped = AtomicBoolean()

    fun start() {
        if (stopped.get()) return
        val maximumAttempts = properties.webLogStartMaxAttempts.coerceAtLeast(1)
        repeat(maximumAttempts) { index ->
            if (stopped.get()) return
            val result = runCatching {
                applyResponse(send("https://data.bilivideo.com/log/web/te9Kl", includeCsn = false))
                qid++
                applyResponse(send("https://data.bilivideo.com/log/web/te9Kl", includeCsn = true))
                qid++
            }
            if (result.isSuccess) {
                failures = 0
                log.info("WebLog 直播遥测进入成功: room={}, qid={}, intervalSeconds={}", context.roomId, qid, interval)
                scheduleHeartbeat()
                return
            }
            val failure = classifyFailure(result.exceptionOrNull()!!)
            logFailure("进入", index + 1, maximumAttempts, failure)
            if (index + 1 == maximumAttempts) terminate(failure)
        }
    }

    private fun heartbeat() {
        if (stopped.get()) return
        val result = runCatching {
            applyResponse(send("https://data.bilivideo.com/log/web/s82Tq", includeCsn = true))
            qid++
            failures = 0
        }
        if (result.isFailure) {
            val failure = classifyFailure(result.exceptionOrNull()!!)
            failures++
            logFailure("心跳", failures, 3, failure)
            if (failures >= 3) {
                terminate(failure)
                return
            }
        }
        scheduleHeartbeat()
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
        if (!response.successful()) throw WebLogRequestException(
            if (response.status == 504) WebLogFailureKind.HTTP_GATEWAY_TIMEOUT else WebLogFailureKind.HTTP_STATUS,
            "WebLog HTTP ${response.status}", response.status)
        return runCatching { response.json() }.getOrElse {
            throw WebLogRequestException(WebLogFailureKind.MALFORMED_RESPONSE,
                "WebLog response is not valid JSON", response.status, cause = it)
        }
    }

    private fun applyResponse(root: JSONObject) {
        val code = root.getIntValue("code", -1)
        if (code != 0) throw WebLogRequestException(WebLogFailureKind.BUSINESS_CODE, "WebLog code=$code", businessCode = code)
        var data = root.getJSONObject("data")
            ?: throw WebLogRequestException(WebLogFailureKind.MALFORMED_RESPONSE, "WebLog omitted data")
        data.getJSONObject("data")?.let { data = it }
        data.getString("sid")?.takeIf { it.isNotBlank() }?.let { sid = it }
        data.getString("stky")?.takeIf { it.isNotBlank() }?.let { stky = it }
        if (sid.isBlank() || stky.isBlank()) {
            throw WebLogRequestException(WebLogFailureKind.MALFORMED_RESPONSE, "WebLog omitted sid/stky")
        }
        data.getLong("hbil")?.let {
            interval = it.coerceIn(properties.webLogMinimumIntervalSeconds, properties.webLogMaximumIntervalSeconds)
        }
    }

    private fun classifyFailure(error: Throwable): WebLogFailure {
        val request = error as? WebLogRequestException
        return WebLogFailure(request?.kind ?: WebLogFailureKind.NETWORK,
            request?.status, request?.businessCode, error)
    }

    private fun logFailure(stage: String, attempt: Int, maximum: Int, failure: WebLogFailure) {
        val decision = if (failure.kind == WebLogFailureKind.HTTP_GATEWAY_TIMEOUT) {
            incidentReporter?.record(BilibiliFailureIncidentReporter.Observation(
                BilibiliFailureIncidentReporter.Category.TELEMETRY_HTTP_504,
                context.roomId, "data.bilivideo.com", 0, -1, -1, failure.error))
        } else null
        if (decision?.suppressWarning() != true) {
            log.warn("WebLog 直播{}失败: room={}, kind={}, failures={}/{}, httpStatus={}, businessCode={}, reason={}",
                stage, context.roomId, failure.kind, attempt, maximum, failure.httpStatus,
                failure.businessCode, failure.error.message)
        }
        if (decision == null || decision.includeStack()) {
            log.debug("WebLog failure detail: room={}, stage={}, kind={}", context.roomId, stage, failure.kind, failure.error)
        } else {
            log.debug("WebLog failure: room={}, stage={}, kind={}, httpStatus={}, businessCode={}, reason={}",
                context.roomId, stage, failure.kind, failure.httpStatus, failure.businessCode, failure.error.toString())
        }
    }

    private fun scheduleHeartbeat() {
        if (stopped.get()) return
        timer?.cancel(false)
        timer = dispatcher.schedule(Duration.ofSeconds(interval), "WebLog 直播心跳", context.roomId,
            onRejected = { if (!stopped.get()) scheduleHeartbeat() }) {
            if (!stopped.get()) heartbeat()
        }
    }

    private fun terminate(failure: WebLogFailure) {
        if (!stopped.compareAndSet(false, true)) return
        timer?.cancel(false)
        timer = null
        onTerminalFailure(failure)
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        timer?.cancel(false)
        timer = null
    }
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}

enum class WebLogFailureKind { HTTP_GATEWAY_TIMEOUT, HTTP_STATUS, BUSINESS_CODE, MALFORMED_RESPONSE, NETWORK }

data class WebLogFailure(
    val kind: WebLogFailureKind,
    val httpStatus: Int? = null,
    val businessCode: Int? = null,
    val error: Throwable,
)

private class WebLogRequestException(
    val kind: WebLogFailureKind,
    message: String,
    val status: Int? = null,
    val businessCode: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class GolPostwebTracker(
    private val context: LiveClientContext,
    private val properties: LiveTelemetryProperties,
    private val http: BilibiliHttpPipeline,
    private val dispatcher: TelemetryTaskDispatcher,
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
            timer = dispatcher.schedule(Duration.ofSeconds(properties.golFlushSeconds),
                "gol/postweb flush", context.roomId) { if (!closed.get()) flush() }
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
            dispatcher.schedule(Duration.ofSeconds(delays[index]), "gol/postweb retry", context.roomId) {
                if (closed.get()) return@schedule
                runCatching {
                    val response = http.postRaw("https://data.bilibili.com/gol/postweb", emptyMap(), body,
                        "text/plain;charset=UTF-8", "bilibili-telemetry-gol-retry")
                    if (response.status == 429) scheduleRetry(body, index + 1)
                }
            }
        }.onFailure { if (!closed.get()) log.warn("gol/postweb 重试调度失败: room={}, reason={}", context.roomId, it.toString()) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(queue) {
            timer?.cancel(false)
            timer = null
            queue += record(2, 2, params = mapOf("reason" to "stop", "mediaPull" to false))
            val records = queue.toList()
            queue.clear()
            if (records.isNotEmpty()) {
                val body = records.joinToString("\u0003").toByteArray(StandardCharsets.UTF_8)
                dispatcher.submit("gol/postweb close flush", context.roomId) {
                    runCatching {
                        http.postRaw("https://data.bilibili.com/gol/postweb",
                            mapOf("Referer" to "https://live.bilibili.com/${context.roomId}"), body,
                            "text/plain;charset=UTF-8", "bilibili-telemetry-gol-close")
                    }.onFailure {
                        log.debug("gol/postweb 关闭 flush 未完成: room={}, records={}", context.roomId, records.size, it)
                    }
                }
            }
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
    val gol: GolPostwebTracker?,
    val tasks: MutableList<ScheduledFuture<*>> = CopyOnWriteArrayList(),
) : AutoCloseable {
    private val closed = AtomicBoolean()
    @Volatile var webLog: WebLogSession? = null
    @Volatile var webLogRestartTask: ScheduledFuture<*>? = null
    fun isClosed() = closed.get()

    @Synchronized fun installWebLog(value: WebLogSession) {
        if (closed.get()) {
            value.close()
            return
        }
        webLog?.close()
        webLog = value
    }

    @Synchronized fun clearWebLog(value: WebLogSession): Boolean {
        if (webLog !== value) return false
        webLog = null
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        tasks.forEach { it.cancel(false) }
        tasks.clear()
        webLogRestartTask?.cancel(false)
        webLogRestartTask = null
        webLog?.close()
        webLog = null
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
    private val api: BilibiliApiUtil,
    private val dispatcher: TelemetryTaskDispatcher,
    private val incidentReporter: BilibiliFailureIncidentReporter,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sessions = ConcurrentHashMap<Long, ActiveTelemetrySession>()
    private val closed = AtomicBoolean()

    @EventListener
    fun onLive(event: BilibiliLiveOnEvent) {
        if (closed.get()) return
        val roomId = event.source.roomId ?: return
        if (sessions.containsKey(roomId)) return
        submit("启动直播遥测", roomId, onRejected = {
            dispatcher.schedule(Duration.ofSeconds(properties.webLogDefaultIntervalSeconds),
                "重试启动直播遥测", roomId) { onLive(event) }
        }) {
            runCatching {
                val lease = playUrlProvider.get(roomId)
                val context = LiveClientContext(roomId, lease.roomId, event.source.uid, lease.areaId, lease.parentAreaId,
                    event.timestamp, credentials.snapshot()?.identityRevision ?: 0, lease)
                val gol = if (properties.golPostwebEnabled) GolPostwebTracker(context, properties, http, dispatcher) else null
                val session = ActiveTelemetrySession(context, gol)
                if (closed.get() || sessions.putIfAbsent(roomId, session) != null) { session.close(); return@runCatching }
                gol?.event(2, 1, params = mapOf("event" to "client_init", "mediaPull" to false))
                if (properties.webLogEnabled) startWebLog(session)
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

    private fun startWebLog(session: ActiveTelemetrySession) {
        if (closed.get() || session.isClosed()) return
        lateinit var webLog: WebLogSession
        webLog = WebLogSession(session.context, properties, http, credentials, playUrlProvider, signer,
            dispatcher, incidentReporter) {
            failure -> scheduleWebLogRebootstrap(session, webLog, failure)
        }
        session.installWebLog(webLog)
        webLog.start()
    }

    private fun scheduleWebLogRebootstrap(
        session: ActiveTelemetrySession,
        failedSession: WebLogSession,
        failure: WebLogFailure,
    ) {
        if (!session.clearWebLog(failedSession) || closed.get() || session.isClosed()) return
        val jitterMaximum = properties.webLogRebootstrapJitterSeconds.coerceAtLeast(0)
        val jitter = if (jitterMaximum == 0L) 0L else Math.floorMod(session.context.roomId, jitterMaximum + 1)
        val delay = properties.webLogRebootstrapSeconds.coerceAtLeast(1) + jitter
        log.warn("WebLog 当前会话已停止，将使用全新会话延迟重建: room={}, kind={}, delaySeconds={}",
            session.context.roomId, failure.kind, delay)
        session.webLogRestartTask?.cancel(false)
        session.webLogRestartTask = dispatcher.schedule(Duration.ofSeconds(delay),
            "WebLog 会话重建", session.context.roomId) {
            session.webLogRestartTask = null
            startWebLog(session)
        }
    }

    private fun scheduleLegacy000916(session: ActiveTelemetrySession) {
        if (closed.get() || session.isClosed()) return
        val guid = UUID.randomUUID().toString()
        var delta = 0L
        val task = dispatcher.scheduleAtFixedRate(Duration.ofSeconds(properties.legacy000916IntervalSeconds),
            "000916 直播遥测", session.context.roomId) {
                if (session.isClosed()) return@scheduleAtFixedRate
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
        session.tasks += task
    }

    private fun scheduleLegacyRdata(session: ActiveTelemetrySession) {
        if (closed.get() || session.isClosed()) return
        val task = dispatcher.scheduleAtFixedRate(Duration.ofSeconds(properties.legacyRdataIntervalSeconds),
            "rdata 直播心跳", session.context.roomId) {
            if (!session.isClosed()) api.liveRoomHeartbeat(session.context.roomId)
        }
        session.tasks += task
    }

    private fun submit(operation: String, roomId: Long? = null, onRejected: () -> Unit = {}, action: () -> Unit) {
        if (closed.get()) return
        dispatcher.submit(operation, roomId, onRejected) { if (!closed.get()) action() }
    }

    @PreDestroy
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val active = sessions.values.toList()
        sessions.clear()
        active.forEach { it.close() }
    }
}
