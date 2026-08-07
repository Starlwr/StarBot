package com.starlwr.bot.bilibili.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LiveReportDataDriverTest {
    @TempDir lateinit var temp: Path
    private val session = ReportSession("bilibili:1:1000", "bilibili", 1, 2, "tester", 1000)
    private val delta = ReportDelta(ReportMetric.BOX, 2, 30.0, 5.0,
        ReportUserDelta("9", "sender", count = 2, value = 30.0, profit = 5.0), 65_432, label = "gift")

    @Test fun `memory driver is idempotent`() {
        val driver = InMemoryLiveReportDataDriver(); driver.initialize(); driver.createOrResume(session)
        assertTrue(driver.apply(session, "same", delta)); assertFalse(driver.apply(session, "same", delta))
        val result = driver.snapshot(session.sessionId)!!
        assertEquals(2, result.counts["box"]); assertEquals(5.0, result.profits["box"])
        assertEquals(2, result.users["box"]?.get("9")?.count)
        assertEquals(setOf(65_000L), result.buckets["box"]?.keys)
    }

    @Test fun `sqlite survives reopen and rejects duplicate event`() {
        val url = "jdbc:sqlite:${temp.resolve("report.db").toAbsolutePath()}"
        JdbcLiveReportDataDriver("sqlite", url).use { first ->
            first.initialize(); first.createOrResume(session); assertTrue(first.apply(session, "same", delta))
        }
        JdbcLiveReportDataDriver("sqlite", url).use { second ->
            second.initialize(); assertFalse(second.apply(session, "same", delta))
            assertEquals(2, second.snapshot(session.sessionId)?.counts?.get("box"))
        }
    }

    @Test fun `explicit false does not enable unrelated features`() {
        val json = com.alibaba.fastjson2.JSON.parseObject("""{"sections":{"box":false,"gift":true},"charts":{"box":{"enabled":true}},"word_cloud":{"enabled":false}}""")
        val config = LiveReportTargetConfig.from(json)
        assertFalse(config.section("box")); assertTrue(config.section("gift")); assertTrue(config.chart("box"))
        assertFalse(config.wordCloud); assertFalse(config.chart("gift"))
    }

    @Test fun `buffer flushes before completion`() {
        val delegate = InMemoryLiveReportDataDriver()
        val driver = BufferedLiveReportDataDriver(delegate, capacity = 100, batchSize = 10, flushMillis = 60_000)
        driver.initialize(); driver.createOrResume(session)
        repeat(25) { driver.apply(session, "e$it", ReportDelta(ReportMetric.DANMU, 1)) }
        assertEquals(25, driver.snapshot(session.sessionId)?.counts?.get("danmu"))
        assertEquals(25, driver.complete(session.sessionId, 2000)?.counts?.get("danmu"))
        driver.close()
    }

    @Test fun `open session and abnormal close survive sqlite reopen`() {
        val url = "jdbc:sqlite:${temp.resolve("recovery.db").toAbsolutePath()}"
        JdbcLiveReportDataDriver("sqlite", url).use { first ->
            first.initialize(); first.createOrResume(session)
            first.updateLifecycle(session.sessionId, SessionLifecycleUpdate(
                lifecycleState = ReportLifecycleState.PENDING_CLOSE, pendingCloseSince = 1_500))
        }
        JdbcLiveReportDataDriver("sqlite", url).use { second ->
            second.initialize()
            val open = second.openSessions().single()
            assertEquals(ReportLifecycleState.PENDING_CLOSE, open.lifecycleState)
            val closed = second.complete(open.sessionId, 2_000, ReportCloseDisposition.ABNORMAL, "test")!!
            assertFalse(closed.reportEligible)
            assertTrue(second.openSessions().isEmpty())
        }
    }

    @Test fun `schema one snapshots migrate without rewriting timestamp keys`() {
        val legacy = LiveReportSnapshot(schemaVersion = 1).apply {
            buckets["danmu"] = java.util.concurrent.ConcurrentHashMap(mapOf(60_000L to 3.0))
        }
        val migrated = LiveReportSchemaMigration.migrate(legacy)
        assertEquals(LiveReportSnapshot.CURRENT_SCHEMA, migrated.schemaVersion)
        assertEquals(mapOf(60_000L to 3.0), migrated.buckets["danmu"])
    }
}
