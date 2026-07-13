package com.starlwr.bot.bilibili.report

import org.slf4j.LoggerFactory
import java.util.concurrent.*

class BufferedLiveReportDataDriver(
    private val delegate: LiveReportDataDriver,
    capacity: Int = 20_000,
    private val batchSize: Int = 500,
    flushMillis: Long = 1_000
) : LiveReportDataDriver {
    override val id = "buffered-${delegate.id}"
    private data class Pending(val session: ReportSession, val eventId: String, val delta: ReportDelta)
    private val log = LoggerFactory.getLogger(javaClass)
    private val queue = ArrayBlockingQueue<Pending>(capacity.coerceAtLeast(100))
    private val snapshots = ConcurrentHashMap<String, LiveReportSnapshot>()
    private val localEvents = ConcurrentHashMap.newKeySet<String>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "starbot-report-flush").apply { isDaemon = true } }
    init { scheduler.scheduleWithFixedDelay({ runCatching { flushBatch() }.onFailure { log.error("直播报告批量刷新失败", it) } }, flushMillis, flushMillis, TimeUnit.MILLISECONDS) }
    override fun initialize() = delegate.initialize()
    override fun createOrResume(session: ReportSession): LiveReportSnapshot = snapshots.computeIfAbsent(session.sessionId) {
        delegate.createOrResume(session)
    }.copySafe()
    override fun apply(session: ReportSession, eventId: String, delta: ReportDelta): Boolean {
        val identity = "${session.sessionId}:$eventId"; if (!localEvents.add(identity)) return false
        snapshots.computeIfAbsent(session.sessionId) { delegate.createOrResume(session) }.apply(delta)
        val pending = Pending(session, eventId, delta)
        if (!queue.offer(pending)) { flushBatch(); queue.put(pending) }
        return true
    }
    override fun snapshot(sessionId: String): LiveReportSnapshot? = snapshots[sessionId]?.copySafe() ?: delegate.snapshot(sessionId)
    override fun complete(sessionId: String, endedAt: Long): LiveReportSnapshot? {
        return try {
            flushSession(sessionId); val completed = delegate.complete(sessionId, endedAt)
            if (completed != null) snapshots[sessionId] = completed
            completed
        } catch (e: Exception) {
            log.error("持久层暂时不可用，会话 {} 保留在内存缓冲并生成临时报告", sessionId, e)
            snapshots[sessionId]?.also { it.endedAt = endedAt }?.copySafe()
        }
    }
    override fun recent(uid: Long, limit: Int): List<LiveReportSnapshot> { flushBatch(); return delegate.recent(uid, limit) }
    override fun health(): DriverHealth { val health=delegate.health(); return if(health.healthy) DriverHealth(true,"queued=${queue.size}") else health }
    @Synchronized private fun flushBatch() {
        val batch = ArrayList<Pending>(batchSize.coerceAtLeast(1)); queue.drainTo(batch, batchSize)
        if (batch.isEmpty()) return
        var index = 0
        try { while (index < batch.size) { val p=batch[index]; delegate.apply(p.session,p.eventId,p.delta); index++ } }
        catch (e: Exception) {
            for (i in batch.lastIndex downTo index) queue.put(batch[i])
            throw e
        }
    }
    private fun flushSession(sessionId: String) {
        while (queue.any { it.session.sessionId == sessionId }) flushBatch()
    }
    override fun close() {
        scheduler.shutdown()
        var failures = 0
        while (queue.isNotEmpty() && failures < 3) try { flushBatch(); failures = 0 } catch (_: Exception) { failures++ }
        if (queue.isNotEmpty()) {
            val path = java.nio.file.Path.of(System.getProperty("user.dir"), "data", "live-report-recovery-${System.currentTimeMillis()}.pb")
            java.nio.file.Files.createDirectories(path.parent)
            java.nio.file.Files.newOutputStream(path).use { out -> snapshots.values.forEach { ReportArchive.write(it.copySafe(), out) } }
            log.error("持久层关闭时仍不可用，已将 {} 个会话写入恢复文件 {}", snapshots.size, path)
            queue.clear()
        }
        delegate.close()
    }
}
