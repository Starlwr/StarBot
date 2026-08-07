package com.starlwr.bot.bilibili.telemetry

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class TelemetryTaskDispatcherTest {
    @Test
    fun `saturated telemetry worker never runs work on submitting thread`() {
        val worker = ThreadPoolTaskExecutor().apply {
            corePoolSize = 1
            maxPoolSize = 1
            queueCapacity = 1
            setThreadNamePrefix("telemetry-test-worker-")
            setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
            initialize()
        }
        val scheduler = ThreadPoolTaskScheduler().apply {
            poolSize = 1
            setThreadNamePrefix("telemetry-test-scheduler-")
            initialize()
        }
        try {
            val release = CountDownLatch(1)
            val running = CountDownLatch(1)
            worker.execute {
                running.countDown()
                try { release.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
            assertTrue(running.await(2, TimeUnit.SECONDS))
            worker.execute {
                try { release.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }

            val rejected = CountDownLatch(1)
            val caller = Thread.currentThread().name
            val started = System.nanoTime()
            TelemetryTaskDispatcher(worker, scheduler).submit("overload-test", 1, onRejected = {
                assertTrue(Thread.currentThread().name.startsWith("telemetry-test-scheduler-"))
                assertTrue(Thread.currentThread().name != caller)
                rejected.countDown()
            }) { error("must not run") }

            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 100)
            assertTrue(rejected.await(2, TimeUnit.SECONDS))
            release.countDown()
        } finally {
            worker.shutdown()
            scheduler.shutdown()
        }
    }
}
