package com.starlwr.bot.bilibili.report

import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.bilibili.event.live.BilibiliDanmuEvent
import com.starlwr.bot.core.model.LiveStreamerInfo
import com.starlwr.bot.core.model.UserInfo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReportEventIdentityTest {
    private fun event() = BilibiliDanmuEvent(LiveStreamerInfo(1L, "test", 2L), UserInfo(3L, "sender"), "text")
    @Test fun `danmu packet identity uses upstream id_str`() {
        val primary = com.alibaba.fastjson2.JSONArray()
        repeat(15) { primary.add(0) }
        primary.add(JSONObject.of("extra", """{"id_str":"message-123"}"""))
        val info = com.alibaba.fastjson2.JSONArray.of(primary)
        val packet = JSONObject.of("cmd", "DANMU_MSG", "info", info)
        val first = ReportEventIdentity.bindPacket(event(), packet)
        val second = ReportEventIdentity.bindPacket(event(), packet)
        assertEquals("DANMU_MSG:message-123", ReportEventIdentity.id(first))
        assertEquals(ReportEventIdentity.id(first), ReportEventIdentity.id(second))
    }
    @Test fun `invalid or missing identifiers preserve independent stable receipt ids`() {
        val packet = JSONObject.of("cmd", "SEND_GIFT", "data", JSONObject.of("tid", "0"))
        val first = ReportEventIdentity.bindPacket(event(), packet)
        val second = ReportEventIdentity.bindPacket(event(), packet)
        assertNotEquals(ReportEventIdentity.id(first), ReportEventIdentity.id(second))
        assertEquals(ReportEventIdentity.id(first), ReportEventIdentity.id(first))
    }
}
