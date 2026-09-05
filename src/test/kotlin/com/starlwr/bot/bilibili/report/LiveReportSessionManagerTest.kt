package com.starlwr.bot.bilibili.report

import com.starlwr.bot.bilibili.event.live.BilibiliLiveOnEvent
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent
import com.starlwr.bot.bilibili.event.live.BilibiliLiveEventOrigin
import com.starlwr.bot.bilibili.model.Room
import com.starlwr.bot.core.model.LiveStreamerInfo
import com.starlwr.bot.core.service.LiveDataService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.util.Optional

class LiveReportSessionManagerTest {
    private val liveData = Mockito.mock(LiveDataService::class.java).also {
        Mockito.`when`(it.getLiveStartTime(Mockito.anyString(), Mockito.anyLong())).thenReturn(Optional.empty())
        Mockito.`when`(it.getLiveEndTime(Mockito.anyString(), Mockito.anyLong())).thenReturn(Optional.empty())
    }
    private val properties = LiveReportRecoveryProperties()

    @Test fun `restart recovers same full session without replacing baseline`() {
        val driver = InMemoryLiveReportDataDriver().also { it.initialize() }
        val first = LiveReportSessionManager(driver, liveData, properties)
        val source = LiveStreamerInfo(1L, "anchor", 2L)
        val event = BilibiliLiveOnEvent(source, Instant.ofEpochMilli(1_000_000))
        val created = first.onLive(event).snapshot
        first.recordMetadata(event, "before", mapOf("fans" to 10))

        val restarted = LiveReportSessionManager(driver, liveData, properties).also { it.restoreOpenSessions() }
        val room = Room(1L, "anchor", 2L, 1, 1_000_000L, "title", "cover")
        val recovered = restarted.recoverLive(room, 1_060_000)

        assertEquals(created.sessionId, recovered.sessionId)
        assertEquals(ReportBaselineType.FULL, recovered.baselineType)
        assertEquals(10L, recovered.metadata["before_fans"])
        assertEquals(1L, recovered.recoveryCount)
        assertEquals(ReportRecoveryStatus.RECOVERED, recovered.recoveryStatus)
    }

    @Test fun `new api start closes old session and creates partial replacement`() {
        val driver = InMemoryLiveReportDataDriver().also { it.initialize() }
        val manager = LiveReportSessionManager(driver, liveData, properties)
        val source = LiveStreamerInfo(1L, "anchor", 2L)
        val old = manager.onLive(BilibiliLiveOnEvent(source, Instant.ofEpochMilli(1_000_000))).snapshot
        val room = Room(1L, "anchor", 2L, 1, 2_000_000L, "title", "cover")

        val replacement = manager.recoverLive(room, 2_100_000)
        val closed = driver.snapshot(old.sessionId)!!
        assertEquals(ReportCloseDisposition.ABNORMAL, closed.closeDisposition)
        assertFalse(closed.reportEligible)
        assertEquals(ReportBaselineType.PARTIAL, replacement.baselineType)
        assertEquals(2_100_000L, replacement.collectionStartedAt)
    }

    @Test fun `offline observation enters pending close and abnormal close never reports`() {
        val driver = InMemoryLiveReportDataDriver().also { it.initialize() }
        val manager = LiveReportSessionManager(driver, liveData, properties)
        val source = LiveStreamerInfo(1L, "anchor", 2L)
        manager.onLive(BilibiliLiveOnEvent(source, Instant.ofEpochMilli(1_000_000)))

        val pending = manager.observeOffline(1L, 1_100_000)!!
        assertEquals(ReportLifecycleState.PENDING_CLOSE, pending.lifecycleState)
        val closed = manager.closeAbnormal(1L, 1_700_000, "confirmed")!!
        assertEquals(ReportCloseDisposition.ABNORMAL, closed.closeDisposition)
        assertFalse(closed.reportEligible)
    }

    @Test fun `backup lifecycle events retain normal report behavior and api start identity`() {
        val driver = InMemoryLiveReportDataDriver().also { it.initialize() }
        val manager = LiveReportSessionManager(driver, liveData, properties)
        val room = Room(1L, "anchor", 2L, 1, 1_000_000L, "title", "cover")
        val on = BilibiliLiveOnEvent(room, Instant.ofEpochMilli(1_300_000), BilibiliLiveEventOrigin.BACKUP_PUSH)

        val started = manager.onLive(on).snapshot
        assertEquals(1_000_000L, started.startedAt)
        assertEquals(1_000_000L, started.reportStartedAt())
        assertEquals(ReportBaselineType.FULL, started.baselineType)

        val off = BilibiliLiveOffEvent(room, Instant.ofEpochMilli(1_600_000), BilibiliLiveEventOrigin.BACKUP_PUSH)
        val completed = manager.onOff(off)!!
        assertEquals(ReportCloseDisposition.NORMAL, completed.closeDisposition)
        assertTrue(completed.reportEligible)
    }

    @Test fun `duplicate restored sessions retain newest and close stale copies`() {
        val driver = InMemoryLiveReportDataDriver().also { it.initialize() }
        driver.createOrResume(ReportSession("old", "bilibili", 1, 2, "anchor", 1_000_000))
        driver.createOrResume(ReportSession("new", "bilibili", 1, 2, "anchor", 2_000_000))

        val manager = LiveReportSessionManager(driver, liveData, properties).also { it.restoreOpenSessions() }

        assertEquals("new", manager.activeSnapshot(1)!!.sessionId)
        assertEquals(ReportCloseDisposition.ABNORMAL, driver.snapshot("old")!!.closeDisposition)
        assertFalse(driver.snapshot("old")!!.reportEligible)
    }
}
