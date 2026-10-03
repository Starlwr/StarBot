package com.starlwr.bot.bilibili.report

import java.io.Closeable

interface LiveReportDataDriver : Closeable {
    val id: String
    fun initialize()
    fun createOrResume(session: ReportSession): LiveReportSnapshot
    /** Returns false when eventId was already committed. */
    fun apply(session: ReportSession, eventId: String, delta: ReportDelta): Boolean
    /** Results preserve input order. Retrying a failed/ambiguous batch must be idempotent. */
    fun applyBatch(events: List<ReportEventWrite>): List<Boolean> =
        events.map { apply(it.session, it.eventId, it.delta) }
    fun lifecycleState(sessionId: String): ReportLifecycleState? = snapshot(sessionId)?.lifecycleState
    /** Loaded once when restoring an active buffered session, never for each interaction. */
    fun committedEventIds(sessionId: String): Set<String> = emptySet()
    fun snapshot(sessionId: String): LiveReportSnapshot?
    fun openSessions(): List<LiveReportSnapshot>
    fun updateLifecycle(sessionId: String, update: SessionLifecycleUpdate): LiveReportSnapshot?
    fun complete(sessionId: String, endedAt: Long,
                 disposition: ReportCloseDisposition = ReportCloseDisposition.NORMAL,
                 reason: String? = null): LiveReportSnapshot?
    fun recent(uid: Long, limit: Int = 10): List<LiveReportSnapshot>
    fun health(): DriverHealth
    override fun close() {}
}

data class DriverHealth(val healthy: Boolean, val message: String = "ok")
data class ReportEventWrite(val session: ReportSession, val eventId: String, val delta: ReportDelta)

class InMemoryLiveReportDataDriver(private val maxSessions: Int = 1_000, private val maxEvents: Int = 1_000_000) : LiveReportDataDriver {
    override val id = "memory"
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, LiveReportSnapshot>()
    private val events = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    override fun initialize() = Unit
    override fun createOrResume(session: ReportSession) = sessions.computeIfAbsent(session.sessionId) { session.snapshot() }.copySafe()
    override fun apply(session: ReportSession, eventId: String, delta: ReportDelta): Boolean {
        evictIfNeeded()
        if (!events.add("${session.sessionId}:$eventId")) return false
        sessions.computeIfAbsent(session.sessionId) { session.snapshot() }.apply(delta); return true
    }
    override fun snapshot(sessionId: String) = sessions[sessionId]?.copySafe()
    override fun lifecycleState(sessionId: String) = sessions[sessionId]?.let { synchronized(it) { it.lifecycleState } }
    override fun committedEventIds(sessionId: String): Set<String> {
        val prefix = "$sessionId:"
        return events.asSequence().filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
    }
    override fun openSessions() = sessions.values.filter { it.endedAt == null }.map { it.copySafe() }
    @Synchronized override fun updateLifecycle(sessionId: String, update: SessionLifecycleUpdate) =
        sessions[sessionId]?.also { it.updateLifecycle(update) }?.copySafe()
    @Synchronized override fun complete(sessionId: String, endedAt: Long, disposition: ReportCloseDisposition, reason: String?) =
        sessions[sessionId]?.let { snapshot ->
            synchronized(snapshot) {
                snapshot.endedAt = endedAt; snapshot.lifecycleState = ReportLifecycleState.CLOSED
                snapshot.closeDisposition = disposition; snapshot.closeReason = reason
                if (disposition == ReportCloseDisposition.ABNORMAL) snapshot.reportEligible = false
                snapshot.copySafe()
            }
        }
    override fun recent(uid: Long, limit: Int) = sessions.values.filter { it.uid == uid && it.endedAt != null }
        .sortedByDescending { it.startedAt }.take(limit).map { it.copySafe() }
    override fun health() = DriverHealth(true)
    private fun evictIfNeeded() {
        if (sessions.size > maxSessions) sessions.values.filter { it.endedAt != null }.minByOrNull { it.endedAt ?: Long.MAX_VALUE }?.let { old ->
            sessions.remove(old.sessionId); events.removeIf { it.startsWith("${old.sessionId}:") }
        }
        if (events.size > maxEvents) events.clear()
    }
}
