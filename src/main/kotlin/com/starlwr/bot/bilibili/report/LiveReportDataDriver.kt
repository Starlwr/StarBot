package com.starlwr.bot.bilibili.report

import java.io.Closeable

interface LiveReportDataDriver : Closeable {
    val id: String
    fun initialize()
    fun createOrResume(session: ReportSession): LiveReportSnapshot
    /** Returns false when eventId was already committed. */
    fun apply(session: ReportSession, eventId: String, delta: ReportDelta): Boolean
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
    override fun openSessions() = sessions.values.filter { it.endedAt == null }.map { it.copySafe() }
    @Synchronized override fun updateLifecycle(sessionId: String, update: SessionLifecycleUpdate) =
        sessions[sessionId]?.also { it.updateLifecycle(update) }?.copySafe()
    @Synchronized override fun complete(sessionId: String, endedAt: Long, disposition: ReportCloseDisposition, reason: String?) =
        sessions[sessionId]?.also {
            it.endedAt = endedAt; it.lifecycleState = ReportLifecycleState.CLOSED
            it.closeDisposition = disposition; it.closeReason = reason
            if (disposition == ReportCloseDisposition.ABNORMAL) it.reportEligible = false
        }?.copySafe()
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
