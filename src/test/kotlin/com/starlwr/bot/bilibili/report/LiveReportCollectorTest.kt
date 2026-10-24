package com.starlwr.bot.bilibili.report

import com.starlwr.bot.bilibili.event.live.BilibiliDanmuEvent
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent
import com.starlwr.bot.core.event.model.LiveStreamerInfo
import com.starlwr.bot.core.event.model.UserInfo
import com.starlwr.bot.core.live.LiveDataService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.util.Optional

class LiveReportCollectorTest {
    @Test fun `same timestamp interactions remain distinct with word cloud disabled`() {
        val driver = InMemoryLiveReportDataDriver()
        val demand = Mockito.mock(LiveReportDemandService::class.java)
        Mockito.`when`(demand.forUid("1")).thenReturn(ReportDemand(true, setOf("danmu"), emptySet(), false))
        val liveData = Mockito.mock(LiveDataService::class.java)
        Mockito.`when`(liveData.getLiveStartTime("bilibili", "1")).thenReturn(Optional.of(1000L))
        val sessions = LiveReportSessionManager(driver, liveData, LiveReportRecoveryProperties())
        val collector = LiveReportCollector(driver, demand, sessions)
        val source = LiveStreamerInfo("1", "test", "2")
        val sender = UserInfo("9", "viewer")
        val first = BilibiliDanmuEvent(source, sender, "one", "one", Instant.ofEpochMilli(2000))
        val second = BilibiliDanmuEvent(source, sender, "two", "two", Instant.ofEpochMilli(2000))
        val repeatedContent = BilibiliDanmuEvent(source, sender, "one", "one", Instant.ofEpochMilli(2000))
        collector.onDanmu(first); collector.onDanmu(second); collector.onDanmu(repeatedContent)
        collector.onDanmu(first) // Delivery retry of the same event retains its identity.
        assertEquals(3L, sessions.activeSnapshot("1")!!.counts["danmu"])
        assertEquals(emptyList<String>(), sessions.activeSnapshot("1")!!.danmuTexts)
    }

    @Test fun `upstream identity deduplicates separate deliveries independently of report options`() {
        val driver = InMemoryLiveReportDataDriver()
        val demand = Mockito.mock(LiveReportDemandService::class.java)
        Mockito.`when`(demand.forUid("1")).thenReturn(ReportDemand(true, setOf("danmu"), emptySet(), false))
        val liveData = Mockito.mock(LiveDataService::class.java)
        Mockito.`when`(liveData.getLiveStartTime("bilibili", "1")).thenReturn(Optional.of(1000L))
        val sessions = LiveReportSessionManager(driver, liveData, LiveReportRecoveryProperties())
        val collector = LiveReportCollector(driver, demand, sessions)
        val source = LiveStreamerInfo("1", "test", "2")
        repeat(2) {
            collector.onDanmu(ReportEventIdentity.bind(BilibiliDanmuEvent(source, UserInfo("9", "viewer"), "one", "one",
                Instant.ofEpochMilli(2000)), "DANMU_MSG:upstream-1"))
        }
        assertEquals(1L, sessions.activeSnapshot("1")!!.counts["danmu"])
    }
    @Test
    fun `mid-live collection keeps api identity but reports from first interaction`() {
        val start = 1_783_987_318_000L
        val end = start + 122_978L
        val driver = InMemoryLiveReportDataDriver().also { it.initialize() }
        val demand = Mockito.mock(LiveReportDemandService::class.java)
        Mockito.`when`(demand.forUid("511373704")).thenReturn(
            ReportDemand(true, setOf("danmu"), setOf("danmu"), true)
        )
        val liveData = Mockito.mock(LiveDataService::class.java)
        Mockito.`when`(liveData.getLiveStartTime("bilibili", "511373704")).thenReturn(Optional.of(start))
        Mockito.`when`(liveData.getLiveEndTime("bilibili", "511373704")).thenReturn(Optional.of(end))
        val properties = LiveReportRecoveryProperties()
        val sessions = LiveReportSessionManager(driver, liveData, properties)
        val collector = LiveReportCollector(driver, demand, sessions)
        val source = LiveStreamerInfo("511373704", "测试主播", "27460077")
        collector.onDanmu(BilibiliDanmuEvent(source, UserInfo("1", "观众"), "测试弹幕", "测试弹幕",
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
