package com.starlwr.bot.bilibili.report

import com.starlwr.bot.bilibili.event.live.BilibiliDanmuEvent
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent
import com.starlwr.bot.core.model.LiveStreamerInfo
import com.starlwr.bot.core.model.UserInfo
import com.starlwr.bot.core.service.LiveDataService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.util.Optional

class LiveReportCollectorTest {
    @Test
    fun `mid-live collection keeps api identity but reports from first interaction`() {
        val start = 1_783_987_318_000L
        val end = start + 122_978L
        val driver = InMemoryLiveReportDataDriver().also { it.initialize() }
        val demand = Mockito.mock(LiveReportDemandService::class.java)
        Mockito.`when`(demand.forUid(511373704L)).thenReturn(
            ReportDemand(true, setOf("danmu"), setOf("danmu"), true)
        )
        val liveData = Mockito.mock(LiveDataService::class.java)
        Mockito.`when`(liveData.getLiveStartTime("bilibili", 511373704L)).thenReturn(Optional.of(start))
        Mockito.`when`(liveData.getLiveEndTime("bilibili", 511373704L)).thenReturn(Optional.of(end))
        val properties = LiveReportRecoveryProperties()
        val sessions = LiveReportSessionManager(driver, liveData, properties)
        val collector = LiveReportCollector(driver, demand, sessions)
        val source = LiveStreamerInfo(511373704L, "测试主播", 27460077L)
        collector.onDanmu(BilibiliDanmuEvent(source, UserInfo(1L, "观众"), "测试弹幕", "测试弹幕",
            Instant.ofEpochMilli(start + 64_321L)))
        val off = BilibiliLiveOffEvent(source, Instant.ofEpochMilli(end))
        collector.onOff(off)

        val snapshot = requireNotNull(collector.completed(off))
        assertEquals(start, snapshot.startedAt)
        assertEquals(start + 64_321L, snapshot.collectionStartedAt)
        assertEquals(ReportBaselineType.PARTIAL, snapshot.baselineType)
        assertEquals(end, snapshot.endedAt)
        assertEquals(1L, snapshot.counts["danmu"])
        assertNull(snapshot.metadata["before_fans"])
        assertEquals(setOf((start + 64_321L) / 1_000 * 1_000), snapshot.buckets["danmu"]?.keys)
    }
}
