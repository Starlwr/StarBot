package com.starlwr.bot.bilibili.http

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class BilibiliHttpCancellationTest {
    @Test
    fun `recognizes nested interruption and cancellation signals`() {
        assertTrue(InterruptedException("shutdown").isInterruptionSignal())
        assertTrue(IOException("cancelled", CancellationException("shutdown")).isInterruptionSignal())
        assertFalse(IOException("connection reset").isInterruptionSignal())
    }
}
