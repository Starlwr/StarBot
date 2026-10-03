package com.starlwr.bot.bilibili.report

import org.slf4j.LoggerFactory
import java.util.concurrent.*

/** Admission, snapshots, retries and completion share one lock. No blocking queue operations. */
class BufferedLiveReportDataDriver(
    private val delegate: LiveReportDataDriver,
    capacity: Int = 20_000,
    private val batchSize: Int = 500,
    flushMillis: Long = 1_000
) : LiveReportDataDriver {
    override val id = "buffered-${delegate.id}"
    private val log = LoggerFactory.getLogger(javaClass)
    private val capacity = capacity.coerceAtLeast(100)
    private val queue = java.util.ArrayDeque<ReportEventWrite>()
    private val snapshots = HashMap<String, LiveReportSnapshot>()
    private val localEvents = HashMap<String, MutableSet<String>>()
    private data class Completion(val endedAt: Long, val disposition: ReportCloseDisposition, val reason: String?)
    private val completions = HashMap<String, Completion>()
    private var closed = false
    private var disposed = false
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "starbot-report-flush").apply { isDaemon = true } }
    init { scheduler.scheduleWithFixedDelay({ runCatching { flushBatch() }.onFailure { log.error("直播报告批量刷新失败", it) } }, flushMillis, flushMillis, TimeUnit.MILLISECONDS) }
    override fun initialize() = delegate.initialize()

    private fun retained(session: ReportSession): LiveReportSnapshot = snapshots[session.sessionId] ?: run {
        check(snapshots.size < capacity) { "Report session buffer is full; retry after persistence recovers" }
        val snapshot = delegate.createOrResume(session)
        if (snapshot.endedAt == null)
            localEvents[session.sessionId] = delegate.committedEventIds(session.sessionId).toMutableSet()
        snapshot.also { snapshots[session.sessionId] = it }
    }
    @Synchronized override fun createOrResume(session: ReportSession): LiveReportSnapshot {
        check(!closed) { "Report buffer is closed" }
        val result = retained(session).copySafe()
        if (result.endedAt != null && session.sessionId !in completions && queue.none { it.session.sessionId == session.sessionId })
            release(session.sessionId)
        return result
    }
    @Synchronized override fun apply(session: ReportSession, eventId: String, delta: ReportDelta): Boolean {
        check(!closed) { "Report buffer is closed" }
        if (localEvents[session.sessionId]?.contains(eventId) == true) return false
        // Failure here has no effect on this new event's identity or counters.
        if (queue.size >= capacity) flushBatch()
        check(queue.size < capacity) { "Report event buffer is full" }
        val snapshot = retained(session)
        if (localEvents[session.sessionId]?.contains(eventId) == true) return false
        // Late metadata and retransmissions for a durable closed session do not
        // recreate a historical cache. The delegate owns their durable dedup IDs.
        if (snapshot.endedAt != null && session.sessionId !in completions) {
            try { return delegate.apply(session, eventId, delta) } finally { release(session.sessionId) }
        }
        queue.addLast(ReportEventWrite(session, eventId, delta))
        localEvents.getOrPut(session.sessionId) { HashSet() }.add(eventId)
        snapshot.apply(delta)
        return true
    }
    @Synchronized override fun snapshot(sessionId: String): LiveReportSnapshot? = snapshots[sessionId]?.copySafe() ?: delegate.snapshot(sessionId)
    @Synchronized override fun lifecycleState(sessionId: String): ReportLifecycleState? =
        snapshots[sessionId]?.lifecycleState ?: delegate.lifecycleState(sessionId)
    @Synchronized override fun openSessions(): List<LiveReportSnapshot> {
        flushBatch()
        return (delegate.openSessions() + snapshots.values.filter { it.endedAt == null })
            .associateBy { it.sessionId }.values.map { it.copySafe() }
    }
    @Synchronized override fun updateLifecycle(sessionId: String, update: SessionLifecycleUpdate): LiveReportSnapshot? {
        check(!closed) { "Report buffer is closed" }
        flushSession(sessionId)
        val updated = delegate.updateLifecycle(sessionId, update) ?: return null
        if (updated.endedAt == null) snapshots[sessionId] = updated else release(sessionId)
        return updated.copySafe()
    }
    @Synchronized override fun complete(sessionId: String, endedAt: Long, disposition: ReportCloseDisposition, reason: String?): LiveReportSnapshot? {
        check(!closed) { "Report buffer is closed" }
        val completion = Completion(endedAt, disposition, reason)
        return try {
            flushSession(sessionId)
            persistCompletion(sessionId, completion)
        } catch (e: Exception) {
            if (sessionId !in snapshots) throw e
            completions[sessionId] = completion
            log.error("持久层暂时不可用，会话 {} 保留在内存缓冲并生成临时报告", sessionId, e)
            snapshots[sessionId]?.also {
                it.endedAt = endedAt; it.lifecycleState = ReportLifecycleState.CLOSED
                it.closeDisposition = disposition; it.closeReason = reason
                if (disposition == ReportCloseDisposition.ABNORMAL) it.reportEligible = false
            }?.copySafe()
        }
    }
    private fun persistCompletion(sessionId: String, completion: Completion): LiveReportSnapshot? {
        val result = delegate.complete(sessionId, completion.endedAt, completion.disposition, completion.reason)
        release(sessionId)
        return result
    }
    private fun release(sessionId: String) {
        snapshots.remove(sessionId); localEvents.remove(sessionId); completions.remove(sessionId)
    }
    @Synchronized override fun recent(uid: Long, limit: Int): List<LiveReportSnapshot> { flushBatch(); return delegate.recent(uid, limit) }
    @Synchronized override fun health(): DriverHealth {
        val health = delegate.health()
        return if (health.healthy) DriverHealth(true, "queued=${queue.size},retained=${snapshots.size},completing=${completions.size}") else health
    }
    @Synchronized internal fun flushBatch() {
        if (disposed) return
        // Keep original entries until persistence succeeds. Retry ambiguous partial commits
        // using durable event IDs, without allocating slots for a separate retry queue.
        val batch = queue.take(batchSize.coerceAtLeast(1))
        if (batch.isNotEmpty()) {
            val accepted = delegate.applyBatch(batch)
            check(accepted.size == batch.size) { "Invalid report batch result" }
            val reconciled = HashMap<String, LiveReportSnapshot>()
            batch.map { it.session.sessionId }.distinct().forEach { id ->
                if (batch.indices.any { batch[it].session.sessionId == id && !accepted[it] }) {
                    delegate.snapshot(id)?.let { durable ->
                        queue.drop(batch.size).filter { it.session.sessionId == id }.forEach { durable.apply(it.delta) }
                        reconciled[id] = durable
                    }
                }
            }
            repeat(batch.size) { queue.removeFirst() }
            snapshots.putAll(reconciled)
            batch.map { it.session.sessionId }.distinct().forEach { id ->
                if (snapshots[id]?.endedAt != null && id !in completions && queue.none { it.session.sessionId == id }) release(id)
            }
        }
        completions.toMap().forEach { (id, completion) ->
            if (queue.none { it.session.sessionId == id }) persistCompletion(id, completion)
        }
    }
    private fun flushSession(sessionId: String) {
        while (queue.any { it.session.sessionId == sessionId }) flushBatch()
    }
    @Synchronized override fun close() {
        if (disposed) return
        closed = true
        scheduler.shutdown()
        var failures = 0
        while ((queue.isNotEmpty() || completions.isNotEmpty()) && failures < 3)
            try { flushBatch(); failures = 0 } catch (_: Exception) { failures++ }
        if (queue.isNotEmpty() || completions.isNotEmpty()) {
            val path = java.nio.file.Path.of(System.getProperty("user.dir"), "data", "live-report-recovery-${System.currentTimeMillis()}.pb")
            java.nio.file.Files.createDirectories(path.parent)
            java.nio.file.Files.newOutputStream(path).use { out -> snapshots.values.forEach { ReportArchive.write(it.copySafe(), out) } }
            log.error("持久层关闭时仍不可用，已将 {} 个会话写入恢复文件 {}", snapshots.size, path)
        }
        delegate.close()
        queue.clear(); snapshots.clear(); localEvents.clear(); completions.clear(); disposed = true
    }
}
