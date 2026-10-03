package com.starlwr.bot.bilibili.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executors

class RedisLiveReportDataDriverTest {
    private val uri = System.getProperty("starbot.test.redis-uri", "redis://localhost:6379/0")
    @Test fun `two instances atomically deduplicate an event`() {
        val prefix = "starbot:test:${UUID.randomUUID()}"
        val first = runCatching { RedisLiveReportDataDriver(uri, prefix).also { it.initialize() } }.getOrNull()
        assumeTrue(first != null, "Redis 7 is not available on localhost:6379")
        val second = RedisLiveReportDataDriver(uri, prefix).also { it.initialize() }
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

    @Test fun `batch commits mixed duplicates and retains closed session dedup`() {
        val prefix = "starbot:test:${UUID.randomUUID()}"
        val first = runCatching { RedisLiveReportDataDriver(uri, prefix).also { it.initialize() } }.getOrNull()
        assumeTrue(first != null, "Redis is unavailable for integration test")
        first!!.use { driver ->
            val session = ReportSession("batch-test", "bilibili", 1, 2, "test", 1)
            driver.createOrResume(session)
            val events = (1..500).map { ReportEventWrite(session, "e$it", ReportDelta(ReportMetric.DANMU, 1)) }
            assertEquals(List(500) { true } + false, driver.applyBatch(events + events.first()))
            assertEquals(500L, driver.snapshot(session.sessionId)!!.counts["danmu"])
            // Configured batches can exceed Lua's unpack/argument stack limits.
            val large = (501..10_500).map { ReportEventWrite(session, "e$it", ReportDelta(ReportMetric.DANMU, 1)) }
            org.junit.jupiter.api.Assertions.assertTrue(driver.applyBatch(large).all { it })
            assertEquals(10_500L, driver.snapshot(session.sessionId)!!.counts["danmu"])
            driver.complete(session.sessionId, 2000)
            driver.createOrResume(session) // A late reader must not reopen the index.
            assertEquals(0, driver.openSessions().size)
            assertFalse(driver.apply(session, "e1", ReportDelta(ReportMetric.DANMU, 1)))
            assertEquals(10_500L, driver.snapshot(session.sessionId)!!.counts["danmu"])
            val client = io.lettuce.core.RedisClient.create(uri)
            try { client.connect().use { c ->
                val redis = c.sync()
                val ttl = redis.ttl("$prefix:{${session.sessionId}}:events")
                org.junit.jupiter.api.Assertions.assertTrue(ttl > 364 * 24 * 3600L)
                assertFalse(redis.sismember("$prefix:open", session.sessionId))
            } } finally { client.shutdown() }
        }
    }
}
