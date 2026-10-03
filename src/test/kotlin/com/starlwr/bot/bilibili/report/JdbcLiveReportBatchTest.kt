package com.starlwr.bot.bilibili.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

class JdbcLiveReportBatchTest {
    @TempDir lateinit var temp: Path
    private val session = ReportSession("batch", "bilibili", 1, 2, "test", 1)
    private fun events() = (1..500).map { ReportEventWrite(session, "e$it", ReportDelta(ReportMetric.DANMU, 1)) }
    @Test fun `batch writes a snapshot once and preserves durable dedup after reopen`() {
        val url = "jdbc:sqlite:${temp.resolve("batch.db")}"
        JdbcLiveReportDataDriver("sqlite", url).use { driver ->
            driver.initialize(); driver.createOrResume(session)
            DriverManager.getConnection(url).use { c -> c.createStatement().use {
                it.execute("CREATE TABLE writes(n INTEGER)")
                it.execute("CREATE TRIGGER count_writes AFTER UPDATE ON starbot_report_session BEGIN INSERT INTO writes VALUES(1); END")
            } }
            assertTrue(driver.applyBatch(events()).all { it })
            assertEquals(500L, driver.snapshot(session.sessionId)!!.counts["danmu"])
            DriverManager.getConnection(url).use { c -> c.createStatement().executeQuery("SELECT COUNT(*) FROM writes").use {
                assertTrue(it.next()); assertEquals(1, it.getInt(1))
            } }
        }
        JdbcLiveReportDataDriver("sqlite", url).use { driver ->
            driver.initialize()
            assertTrue(driver.applyBatch(events()).none { it })
            assertEquals(500L, driver.snapshot(session.sessionId)!!.counts["danmu"])
        }
    }
    @Test fun `failed batch rolls back both ids and counters and is retryable`() {
        val url = "jdbc:sqlite:${temp.resolve("rollback.db")}"
        JdbcLiveReportDataDriver("sqlite", url).use { driver ->
            driver.initialize(); driver.createOrResume(session)
            DriverManager.getConnection(url).use { c -> c.createStatement().use {
                it.execute("CREATE TRIGGER fail_write BEFORE UPDATE ON starbot_report_session BEGIN SELECT RAISE(ABORT,'injected failure'); END")
            } }
            assertThrows(java.sql.SQLException::class.java) { driver.applyBatch(events()) }
            assertNull(driver.snapshot(session.sessionId)!!.counts["danmu"])
            DriverManager.getConnection(url).use { c -> c.createStatement().use {
                it.executeQuery("SELECT COUNT(*) FROM starbot_report_event").use { rs -> assertTrue(rs.next()); assertEquals(0, rs.getInt(1)) }
                it.execute("DROP TRIGGER fail_write")
            } }
            assertTrue(driver.applyBatch(events()).all { it })
            assertEquals(500L, driver.snapshot(session.sessionId)!!.counts["danmu"])
        }
    }
}
