package com.starlwr.bot.bilibili.telemetry

import com.starlwr.bot.bilibili.http.BilibiliHttpPipeline
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.junit.jupiter.api.Assertions.assertEquals
import java.time.Instant

class LiveTelemetryShutdownTest {
    @Test
    fun `gol close submits final flush without blocking caller`() {
        val dispatcher = mock(TelemetryTaskDispatcher::class.java)
        val lease = PlayUrlLease(1, 2, 3, 4, "https://example.invalid/live.m3u8",
            System.currentTimeMillis(), Instant.now().plusSeconds(300).epochSecond)
        val context = LiveClientContext(1, 1, 2, 3, 4, System.currentTimeMillis(), 1, lease)
        val tracker = GolPostwebTracker(context, LiveTelemetryProperties(),
            mock(BilibiliHttpPipeline::class.java), dispatcher)

        tracker.close()

        val call = mockingDetails(dispatcher).invocations.single()
        assertEquals("submit", call.method.name)
        assertEquals("gol/postweb close flush", call.arguments[0])
        assertEquals(1L, call.arguments[1])
    }
}
