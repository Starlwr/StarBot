package com.starlwr.bot.bilibili.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CsnSignerTest {
    @Test
    fun `matches official wasm fixed vector`() {
        val data = WebLogReportData(
            uid = 0, buvid = "AUTO1234567890123456", screenStatus = 42, clickStatus = 17,
            roomId = 123456, playUrl = "https://example.invalid/live.m3u8", qid = 1,
            sid = "sid-example", cts = 1784048159000, stky = "stky-example",
        )
        assertEquals("ba9f9c927a25b8909b06e583319f4ef9", CsnSigner().sign(data))
    }
}
