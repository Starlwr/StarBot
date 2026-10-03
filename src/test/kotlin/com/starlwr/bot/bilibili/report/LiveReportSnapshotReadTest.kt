package com.starlwr.bot.bilibili.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import com.starlwr.bot.bilibili.event.live.BilibiliDanmuEvent
import com.starlwr.bot.core.model.LiveStreamerInfo
import com.starlwr.bot.core.model.UserInfo
import com.starlwr.bot.core.service.LiveDataService
import java.time.Instant
import java.util.Optional
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LiveReportSnapshotReadTest {
    @Test fun `interaction fast path does not copy the accumulated report`() {
        val driver = spy(InMemoryLiveReportDataDriver())
        val liveData = mock(LiveDataService::class.java)
        `when`(liveData.getLiveStartTime("bilibili", 1L)).thenReturn(Optional.of(1000L))
        val manager = LiveReportSessionManager(driver, liveData, LiveReportRecoveryProperties())
        val event = BilibiliDanmuEvent(LiveStreamerInfo(1L, "test", 2L), UserInfo(9L, "viewer"), "test", Instant.ofEpochMilli(2000))
        manager.interactionSession(event)
        clearInvocations(driver)
        repeat(500) { manager.interactionSession(event) }
        verify(driver, never()).snapshot(anyString())
        verify(driver, times(500)).lifecycleState(anyString())
    }
    @Test fun `concurrent snapshot reads are coherent detached copies`() {
        val snapshot = LiveReportSnapshot()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val writer = pool.submit { repeat(3000) {
                snapshot.apply(ReportDelta(ReportMetric.DANMU, 1, user = ReportUserDelta("1", count = 1), text = "text"))
            } }
            val reader = pool.submit { repeat(500) {
                val copy = snapshot.copySafe()
                assertEquals(copy.counts["danmu"] ?: 0L, copy.users["danmu"]?.get("1")?.count ?: 0L)
                assertEquals(copy.counts["danmu"] ?: 0L, copy.danmuTexts.size.toLong())
                copy.users["danmu"]?.get("1")?.count = -1
            } }
            writer.get(5, TimeUnit.SECONDS); reader.get(5, TimeUnit.SECONDS)
            assertEquals(3000L, snapshot.users["danmu"]!!["1"]!!.count)
        } finally { pool.shutdownNow() }
    }
}
