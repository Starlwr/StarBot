package com.starlwr.bot.bilibili.report

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.*

class BufferedLiveReportReliabilityTest {
    private val session = ReportSession("buffer-test", "bilibili", 1, 2, "test", 1)
    private val delta = ReportDelta(ReportMetric.DANMU, 1, occurredAt = 1000)
    private class FailingDriver(val memory: InMemoryLiveReportDataDriver = InMemoryLiveReportDataDriver()) : LiveReportDataDriver by memory {
        var fail = false
        var partialFailure = false
        var failComplete = false
        var beforeBatch: (() -> Unit)? = null
        override fun complete(sessionId: String, endedAt: Long, disposition: ReportCloseDisposition, reason: String?): LiveReportSnapshot? {
            if (failComplete) error("completion unavailable")
            return memory.complete(sessionId, endedAt, disposition, reason)
        }
        override fun applyBatch(events: List<ReportEventWrite>): List<Boolean> {
            beforeBatch?.invoke()
            if (fail) error("storage unavailable")
            return events.mapIndexed { index, event ->
                if (partialFailure && index == 1) { partialFailure = false; error("ambiguous partial commit") }
                memory.apply(event.session, event.eventId, event.delta)
            }
        }
    }
    private fun buffer(delegate: LiveReportDataDriver, batch: Int = 10) =
        BufferedLiveReportDataDriver(delegate, capacity = 100, batchSize = batch, flushMillis = 60_000)

    @Test fun `full queue failure does not admit or deduplicate the new event`() {
        val delegate = FailingDriver()
        buffer(delegate).use { driver ->
            driver.createOrResume(session)
            repeat(100) { driver.apply(session, "e$it", delta) }
            delegate.fail = true
            assertThrows(IllegalStateException::class.java) { driver.apply(session, "new", delta) }
            assertEquals(100L, driver.snapshot(session.sessionId)!!.counts["danmu"])
            delegate.fail = false
            assertTrue(driver.apply(session, "new", delta))
            assertEquals(101L, driver.complete(session.sessionId, 2000)!!.counts["danmu"])
        }
    }
    @Test fun `failure with concurrent producers never blocks in requeue`() {
        val delegate = FailingDriver()
        buffer(delegate, 2).use { driver ->
            repeat(100) { driver.apply(session, "e$it", delta) }
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            delegate.beforeBatch = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); error("failed batch") }
            val pool = Executors.newFixedThreadPool(3)
            try {
                val flush = pool.submit { assertThrows(IllegalStateException::class.java) { driver.flushBatch() } }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                val producers = (1..2).map { n -> pool.submit<Boolean> { driver.apply(session, "new$n", delta) } }
                delegate.beforeBatch = null
                release.countDown()
                flush.get(3, TimeUnit.SECONDS)
                producers.forEach { assertTrue(it.get(3, TimeUnit.SECONDS)) }
                assertEquals(102L, driver.complete(session.sessionId, 2000)!!.counts["danmu"])
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }
    @Test fun `ambiguous partial commit retries once and reconciles snapshot`() {
        val delegate = FailingDriver()
        buffer(delegate).use { driver ->
            repeat(3) { driver.apply(session, "e$it", delta) }
            delegate.partialFailure = true
            assertThrows(IllegalStateException::class.java) { driver.flushBatch() }
            driver.flushBatch()
            assertEquals(3L, delegate.snapshot(session.sessionId)!!.counts["danmu"])
            assertEquals(3L, driver.snapshot(session.sessionId)!!.counts["danmu"])
        }
    }
    @Test fun `completed reports release cache and ids while detached readers remain valid`() {
        val delegate = FailingDriver()
        buffer(delegate).use { driver ->
            val readers = (1..30).map { n ->
                val current = session.copy(sessionId = "session$n", startedAt = n.toLong())
                driver.apply(current, "event", delta)
                driver.complete(current.sessionId, 2000)!!
            }
            for (name in listOf("snapshots", "localEvents", "completions")) {
                val field = driver.javaClass.getDeclaredField(name).apply { isAccessible = true }
                assertTrue((field.get(driver) as Map<*, *>).isEmpty(), name)
            }
            assertTrue(readers.all { it.counts["danmu"] == 1L })
            assertEquals(1L, driver.snapshot("session1")!!.counts["danmu"])
            val first = session.copy(sessionId = "session1", startedAt = 1)
            assertFalse(driver.apply(first, "event", delta))
            assertTrue(driver.apply(first, "after", ReportDelta(ReportMetric.DANMU, occurredAt = 0, metadata = mapOf("after_fans" to 123L))))
            assertEquals(1L, driver.snapshot("session1")!!.counts["danmu"])
            assertTrue(driver.health().message.contains("retained=0,completing=0"))
        }
    }
    @Test fun `failed completion persists later and releases its temporary report`() {
        val delegate = FailingDriver()
        buffer(delegate).use { driver ->
            driver.apply(session, "event", delta)
            delegate.fail = true
            val temporary = driver.complete(session.sessionId, 2000)!!
            assertEquals(2000L, temporary.endedAt)
            delegate.fail = false
            driver.flushBatch()
            assertEquals(2000L, delegate.snapshot(session.sessionId)!!.endedAt)
            assertTrue(driver.health().message.contains("retained=0,completing=0"))
            assertEquals(1L, temporary.counts["danmu"])
        }
    }
    @Test fun `close retries failed completion even when the event queue is empty`() {
        val delegate = FailingDriver()
        buffer(delegate).use { driver ->
            driver.apply(session, "event", delta)
            driver.flushBatch()
            delegate.failComplete = true
            val temporary = driver.complete(session.sessionId, 2000)!!
            assertEquals(2000L, temporary.endedAt)
            assertNull(delegate.snapshot(session.sessionId)!!.endedAt)
            delegate.failComplete = false
            driver.close()
            assertEquals(2000L, delegate.snapshot(session.sessionId)!!.endedAt)
            assertThrows(IllegalStateException::class.java) { driver.apply(session, "late", delta) }
        }
    }
}
