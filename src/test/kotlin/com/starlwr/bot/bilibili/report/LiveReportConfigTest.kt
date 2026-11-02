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

    @Test
    fun `upstream modules are imported without changing local defaults`() {
        val config = LiveReportTargetConfig.from(JSONObject.parseObject(
            """{"modules":{"enableDanmuAnalysis":true,"showDanmuTypeDistributionChart":true,"showDanmuWordCloud":true,"wordCloudLimit":42,"showGiftDetails":false,"giftRankingLimit":3}}"""
        ))
        assertTrue(config.section("danmu"))
        assertTrue(config.chart("danmu_type"))
        assertTrue(config.wordCloud)
        assertEquals(42, config.maxWords)
        assertTrue(!config.amount("gift"))
        assertEquals(3, config.top("gift"))
    }

    @Test
    fun `upstream renderer mapping keeps per-module amount privacy`() {
        val config = LiveReportTargetConfig.from(JSONObject.parseObject(
            """{"sections":{"box":true,"gift":true,"sc":true,"guard":true},"amounts":{"box":false,"gift":false,"sc":false,"guard":false},"rankings":{"box":{"enabled":true,"top":7}},"charts":{"box_profit":{"enabled":true}}}"""
        ))
        val upstream = config.toUpstreamConfig()
        assertTrue(upstream.isEnableBoxAnalysis)
        assertTrue(upstream.isShowBoxDetails)
        assertTrue(!upstream.isShowBoxProfitDetails)
        assertTrue(!upstream.isShowGiftDetails)
        assertTrue(!upstream.isShowSuperChatDetails)
        assertTrue(!upstream.isShowGuardDetails)
        assertEquals(7, upstream.boxRankingLimit)
        assertTrue(upstream.isShowBoxProfitGrowthChart)
    }

    @Test
    fun `renderer and upstream optional modules are target scoped`() {
        val config = LiveReportTargetConfig.from(JSONObject.parseObject(
            """{"painter":"upstream","sections":{"guard":true,"guard_list":true,"enter_room":true,"like":true,"share":true},"rankings":{"like":{"enabled":true,"top":4}},"charts":{"enter_room":{"enabled":true},"like":{"enabled":true},"share":{"enabled":true}}}"""
        ))
        assertTrue(config.usesUpstreamPainter("legacy"))
        val upstream = config.toUpstreamConfig()
        assertTrue(upstream.isShowGuardList)
        assertTrue(upstream.isEnableEnterRoomAnalysis)
        assertTrue(upstream.isEnableLikeAnalysis)
        assertEquals(4, upstream.likeRankingLimit)
        assertTrue(upstream.isShowEnterRoomGrowthChart)
        assertTrue(upstream.isShowLikeGrowthChart)
        assertTrue(upstream.isEnableShareAnalysis)
        assertTrue(upstream.isShowShareGrowthChart)
    }

    @Test
    fun `legacy modules retain upstream optional component switches`() {
        val config = LiveReportTargetConfig.from(JSONObject.parseObject(
            """{"modules":{"enableGuardAnalysis":true,"showGuardList":true,"enableEnterRoomAnalysis":true,"enableLikeAnalysis":true,"enableShareAnalysis":true}}"""
        ))
        assertTrue(config.section("guard_list"))
        assertTrue(config.section("enter_room"))
        assertTrue(config.section("like"))
        assertTrue(config.section("share"))
        assertTrue(config.toUpstreamConfig().isShowGuardList)
    }
}
