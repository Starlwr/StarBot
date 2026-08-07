package com.starlwr.bot.bilibili.report

import com.alibaba.fastjson2.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveReportConfigTest {
    @Test
    fun `amount visibility defaults to enabled for legacy parameters`() {
        val config = LiveReportTargetConfig.from(JSONObject.parseObject("{}"))
        assertEquals(LiveReportTargetConfig.DEFAULT_AMOUNTS, config.amounts)
        assertTrue(config.amount("sc"))
    }

    @Test
    fun `amount visibility is independent per module`() {
        val config = LiveReportTargetConfig.from(JSONObject.parseObject(
            """{"amounts":{"box":false,"sc":false}}"""
        ))
        assertTrue(!config.amount("box"))
        assertTrue(config.amount("gift"))
        assertTrue(!config.amount("sc"))
        assertTrue(config.amount("guard"))
    }
}
