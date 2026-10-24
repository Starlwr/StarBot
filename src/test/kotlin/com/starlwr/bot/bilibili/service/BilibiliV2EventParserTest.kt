package com.starlwr.bot.bilibili.service

import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties
import com.starlwr.bot.bilibili.event.live.BilibiliEnterRoomEvent
import com.starlwr.bot.bilibili.event.live.BilibiliRandomGiftEvent
import com.starlwr.bot.bilibili.event.live.BilibiliRawLiveEvent
import com.starlwr.bot.bilibili.log.BilibiliDebugFileLogger
import com.starlwr.bot.bilibili.api.model.BilibiliUserInfo
import com.starlwr.bot.bilibili.api.BilibiliApiService
import com.starlwr.bot.bilibili.live.gift.BilibiliGiftService
import com.starlwr.bot.bilibili.live.protocol.BilibiliEventParser
import com.starlwr.bot.core.event.model.LiveStreamerInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.io.ByteArrayOutputStream
import java.util.Base64

class BilibiliV2EventParserTest {
    companion object {
        private const val REAL_INTERACT_ENTER = "CNXghQYSCVBORVVNQTM3MyIBASgBMPS/7Ao4zu3k1AZA6q34s4Y0YgB4h+WknpP98OgYmgEAsgFkCNXghQYSVwoJUE5FVU1BMzczEkpodHRwczovL2kwLmhkc2xiLmNvbS9iZnMvZmFjZS80NjJlZTMzYjFmYzVmOWE1NTEyYmQ0NzRlODg1NTczYWRmN2E5NzE2LmpwZyICCAoyALoBAMIBAA=="
        private const val REAL_PAID_GIFT = "CNKUgLHHuqYGEgpDZXJpc2VsdW5lGkpodHRwczovL2kxLmhkc2xiLmNvbS9iZnMvZmFjZS80ZTg1NTllYjk1ODBjMDBjNzhjZGY0NmRiYTk4MTU2YTg4ODUxMDM5LmpwZ0IAUpcFCLzzARIP57KJ5Lid5Zui54Gv54mMGAEgAShkMGQ4ZEIEZ29sZEoTNDgxMjg4ODc1MTcyNDMzODE3NlCg8uTUBlgBYkViYXRjaDpnaWZ0OmNvbWJvX2lkOjM1NDY4Mzc1MTQ0NTU2MzQ6MTg5MzA2MjkyMjozMTE2NDoxNzg4NDI2NTI4LjYwNTdoCnBkeAWFAQAAgD+IAQGSAQbmipXlloLAAa+JEuoBFAoM5Y+v5Y+v5bCP5a6FEIqy14YHigLMAQiKsteGBxLDAQoM5Y+v5Y+v5bCP5a6FEkpodHRwczovL2kwLmhkc2xiLmNvbS9iZnMvZmFjZS9lNDRjYjIwMmJhZTdmNGU0ODhhNGRmNjdiMTQ2OTZmY2IyNDc0YzAyLmpwZzJaCgzlj6/lj6/lsI/lroUSSmh0dHBzOi8vaTAuaGRzbGIuY29tL2Jmcy9mYWNlL2U0NGNiMjAyYmFlN2Y0ZTQ4OGE0ZGY2N2IxNDY5NmZjYjI0NzRjMDIuanBnOgsg////////////AZICBwiM4NcCEAGaAuUBCkpodHRwczovL3MxLmhkc2xiLmNvbS9iZnMvbGl2ZS9lMDUxZGZkNDU1NzY3OGY4ZWRjYWM0OTkzZWQwMGEwOTM1Y2JkOWNjLnBuZxJLaHR0cHM6Ly9pMC5oZHNsYi5jb20vYmZzL2xpdmUvMzJiNzk5MTIwZTE2MTRmYTYyNzViNmQxNWRhN2E1MmIyMWRkMDE5ZC53ZWJwKkpodHRwczovL2kwLmhkc2xiLmNvbS9iZnMvbGl2ZS84MTZmOGI3YWEyMTMyODg4ZmNlOTI4Y2RmYjE3YjljZjIxY2MwODIzLmdpZqoCFAgBEgcI9bfwAhACEgcIjODXAhABWAFqAgggessBCNKUgLHHuqYGEr8BCgpDZXJpc2VsdW5lEkpodHRwczovL2kxLmhkc2xiLmNvbS9iZnMvZmFjZS80ZTg1NTllYjk1ODBjMDBjNzhjZGY0NmRiYTk4MTU2YTg4ODUxMDM5LmpwZzJYCgpDZXJpc2VsdW5lEkpodHRwczovL2kxLmhkc2xiLmNvbS9iZnMvZmFjZS80ZTg1NTllYjk1ODBjMDBjNzhjZGY0NmRiYTk4MTU2YTg4ODUxMDM5LmpwZzoLIP///////////wE="
    }
    private val properties = StarBotBilibiliProperties().also { it.live.isCompleteEvent = false }
    private val api = mock(BilibiliApiService::class.java)
    private val gifts = mock(BilibiliGiftService::class.java)
    private val parser = BilibiliEventParser(properties, api, gifts,
        mock(BilibiliDebugFileLogger::class.java))
    private val source = LiveStreamerInfo("1000", "streamer", "2000")

    @Test
    fun `gift v2 accepts direct payload and switch false compatibility`() {
        val payload = giftBroadcast(switchEnabled = false, count = 2L)
        val events = parser.parseMany(command("SEND_GIFT_V2", payload), source)

        assertEquals(2, events.size)
        val first = assertInstanceOf(BilibiliRandomGiftEvent::class.java, events[0])
        val sender = assertInstanceOf(BilibiliUserInfo::class.java, first.sender)
        assertEquals("42", sender.uid)
        assertEquals("sender", sender.uname)
        assertEquals("face.png", sender.face)
        assertEquals(3, sender.guard.guardType.code)
        assertEquals("guard.png", sender.guard.icon)
        assertEquals(12, sender.fansMedal.level)
        assertEquals(42, sender.honorLevel)
        assertEquals(5.0, first.randomGiftInfo.price)
        assertEquals(0.2, first.value)
    }

    @Test
    fun `gift v2 keeps nested payload path as compatibility fallback`() {
        val command = command("SEND_GIFT_V2", giftBroadcast(switchEnabled = true, count = 1L))
        val direct = command.getJSONObject("data").remove("pb")
        command.getJSONObject("data").put("data", JSONObject().fluentPut("pb", direct))

        assertEquals(2, parser.parseMany(command, source).size)
    }

    @Test
    fun `gift v2 rejects counts that cannot fit the core event model`() {
        val events = parser.parseMany(command("SEND_GIFT_V2",
            giftBroadcast(switchEnabled = true, count = Int.MAX_VALUE.toLong() + 1)), source)

        assertEquals(0, events.size)
    }

    @Test
    fun `universal gift v2 remains a raw event`() {
        val command = JSONObject().fluentPut("cmd", "UNIVERSAL_EVENT_GIFT_V2")
            .fluentPut("data", JSONObject().fluentPut("future", true))

        val event = assertInstanceOf(BilibiliRawLiveEvent::class.java,
            parser.parseMany(command, source).single())
        assertEquals("UNIVERSAL_EVENT_GIFT_V2", event.command)
    }

    @Test
    fun `interact v2 maps enter event metadata`() {
        val medal = message(
            fieldVarint(1, 1000), fieldVarint(2, 12), fieldString(3, "medal"), fieldVarint(8, 1))
        val base = message(fieldString(1, "sender"), fieldString(2, "face.png"))
        val wealth = message(fieldVarint(1, 42))
        val user = message(fieldBytes(2, base), fieldBytes(4, wealth))
        val payload = message(
            fieldVarint(1, 42), fieldString(2, "sender"), fieldVarint(5, 1),
            fieldVarint(7, 1_760_000_000), fieldBytes(9, medal), fieldVarint(10, 1),
            fieldString(13, "promotion"), fieldVarint(16, 3), fieldBytes(22, user))

        val event = assertInstanceOf(BilibiliEnterRoomEvent::class.java,
            parser.parseMany(command("INTERACT_WORD_V2", payload), source).single())
        val sender = assertInstanceOf(BilibiliUserInfo::class.java, event.sender)
        assertEquals("42", sender.uid)
        assertEquals("face.png", sender.face)
        assertEquals("promotion", event.promotionSource)
        assertFalse(sender.fansMedal == null)
        assertEquals(42, sender.honorLevel)
    }

    @Test
    fun `real upstream interact fixture decodes with kotlin reader`() {
        val event = assertInstanceOf(BilibiliEnterRoomEvent::class.java,
            parser.parseMany(command("INTERACT_WORD_V2", REAL_INTERACT_ENTER), source).single())

        assertEquals("12677205", event.sender.uid)
        assertEquals("PNEUMA373", event.sender.uname)
        assertFalse(event.isFromPromotion)
        assertEquals(1_788_425_934_000L, event.timestamp)
    }

    @Test
    fun `real upstream paid gift fixture decodes with kotlin reader`() {
        val event = parser.parseMany(command("SEND_GIFT_V2", REAL_PAID_GIFT), source).single()

        assertEquals("BilibiliPaidGiftEvent", event.javaClass.simpleName)
        val paid = event as com.starlwr.bot.bilibili.event.live.BilibiliPaidGiftEvent
        assertEquals("31164", paid.giftInfo.id)
        assertEquals(1, paid.giftInfo.count)
        assertEquals(0.1, paid.giftInfo.price)
        assertEquals(0.1, paid.value)
    }

    @Test
    fun `invalid and missing v2 payloads are isolated`() {
        val missing = JSONObject().fluentPut("cmd", "SEND_GIFT_V2").fluentPut("data", JSONObject())
        val invalid = JSONObject().fluentPut("cmd", "INTERACT_WORD_V2")
            .fluentPut("data", JSONObject().fluentPut("pb", "not-base64"))

        assertEquals(0, parser.parseMany(missing, source).size)
        assertEquals(0, parser.parseMany(invalid, source).size)
        assertNull(parser.parse(invalid, source).orElse(null))
    }

    private fun giftBroadcast(switchEnabled: Boolean, count: Long): ByteArray {
        val blind = message(fieldVarint(2, 9000), fieldString(3, "blind"), fieldVarint(6, 5000))
        val material = message(fieldString(1, "gift.png"))
        fun gift(id: Long, total: Long) = message(
            fieldVarint(1, id), fieldString(2, "gift-$id"), fieldVarint(3, count),
            fieldVarint(5, 100), fieldVarint(6, 100), fieldVarint(7, total),
            fieldString(8, "gold"), fieldString(9, "tid-$id"), fieldVarint(10, 1_760_000_000),
            fieldString(18, "send"), fieldBytes(35, material))
        val medal = message(
            fieldString(1, "medal"), fieldVarint(2, 12), fieldVarint(9, 1),
            fieldVarint(10, 1000), fieldString(13, "guard.png"))
        val base = message(fieldString(1, "sender"), fieldString(2, "face.png"))
        val sender = message(fieldVarint(1, 42), fieldBytes(2, base), fieldBytes(3, medal))
        val wealth = message(fieldVarint(1, 42))
        return message(
            fieldVarint(1, 41), fieldString(2, "root-sender"), fieldString(3, "root-face.png"),
            fieldVarint(5, 3), fieldBytes(9, blind), fieldBytes(10, gift(1, 200)),
            fieldBytes(99, byteArrayOf(1, 2, 3)), fieldBytes(10, gift(2, 200)),
            fieldVarint(11, if (switchEnabled) 1 else 0), fieldBytes(13, wealth), fieldBytes(15, sender))
    }

    private fun command(type: String, payload: ByteArray) = JSONObject().fluentPut("cmd", type)
        .fluentPut("data", JSONObject().fluentPut("pb", Base64.getEncoder().encodeToString(payload)))

    private fun command(type: String, payload: String) = JSONObject().fluentPut("cmd", type)
        .fluentPut("data", JSONObject().fluentPut("pb", payload))

    private fun message(vararg fields: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        fields.forEach(out::writeBytes)
    }.toByteArray()

    private fun fieldString(number: Int, value: String) = fieldBytes(number, value.toByteArray())
    private fun fieldBytes(number: Int, value: ByteArray) = concat(varint((number shl 3) or 2), varint(value.size.toLong()), value)
    private fun fieldVarint(number: Int, value: Long) = concat(varint(number shl 3), varint(value))
    private fun varint(input: Int) = varint(input.toLong())
    private fun varint(input: Long): ByteArray {
        var value = input
        return ByteArrayOutputStream().also { out ->
            while (value and 0x7f.inv().toLong() != 0L) {
                out.write(((value and 0x7f) or 0x80).toInt())
                value = value ushr 7
            }
            out.write(value.toInt())
        }.toByteArray()
    }
    private fun concat(vararg arrays: ByteArray) = ByteArrayOutputStream().also { out ->
        arrays.forEach(out::writeBytes)
    }.toByteArray()
}
