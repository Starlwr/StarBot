package com.starlwr.bot.bilibili.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executors

class RedisLiveReportDataDriverTest {
    @Test fun `two instances atomically deduplicate an event`() {
        val prefix = "starbot:test:${UUID.randomUUID()}"
        val first = runCatching { RedisLiveReportDataDriver("redis://localhost:6379/0", prefix).also { it.initialize() } }.getOrNull()
        assumeTrue(first != null, "Redis 7 is not available on localhost:6379")
        val second = RedisLiveReportDataDriver("redis://localhost:6379/0", prefix).also { it.initialize() }
        try {
            val session = ReportSession("redis-test", "bilibili", 1, 2, "test", 1)
            first!!.createOrResume(session)
            val pool = Executors.newFixedThreadPool(2)
            val results = listOf(first, second).map { driver -> pool.submit<Boolean> {
                driver.apply(session, "event", ReportDelta(ReportMetric.DANMU, 1))
            } }.map { it.get() }
            pool.shutdown()
            assertEquals(1, results.count { it }); assertEquals(1, first.snapshot(session.sessionId)?.counts?.get("danmu"))
            assertFalse(first.apply(session, "event", ReportDelta(ReportMetric.DANMU, 1)))
        } finally { first?.close(); second.close() }
    }
}
