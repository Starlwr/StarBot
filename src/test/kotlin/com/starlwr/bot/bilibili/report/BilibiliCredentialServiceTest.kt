package com.starlwr.bot.bilibili.credential

import com.alibaba.fastjson2.JSON
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties
import com.starlwr.bot.bilibili.log.BilibiliNetworkLogger
import com.starlwr.bot.bilibili.model.Cookies
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class BilibiliCredentialServiceTest {
    private val properties = BilibiliCredentialProperties()
    private val identity = BilibiliBrowserIdentity(properties)
    private val service = BilibiliCredentialService(
        properties, identity, BilibiliNetworkLogger(StarBotBilibiliProperties())
    )

    @Test
    fun `QR query preserves encoded credential values`() {
        val values = service.parseQuery(
            "https://passport.bilibili.com/login?SESSDATA=a%2Cb%2Bc&bili_jct=csrf&DedeUserID=42"
        )
        assertEquals("a,b+c", values["SESSDATA"])
        assertEquals("csrf", values["bili_jct"])
        assertEquals("42", values["DedeUserID"])
    }

    @Test
    fun `Set-Cookie parser does not split Expires comma`() {
        val values = service.parseSetCookies(listOf(
            "SESSDATA=abc%2Cdef; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/; HttpOnly",
            "bili_jct=new-csrf; Path=/"
        ))
        assertEquals("abc%2Cdef", values["SESSDATA"])
        assertEquals("new-csrf", values["bili_jct"])
        assertTrue(service.parseExpiry(listOf("SESSDATA=x; Max-Age=120; Path=/"))!! > System.currentTimeMillis() / 1000)
    }

    @Test
    fun `Chrome 2026 identity is internally consistent`() {
        properties.uaType = "cHrOmE/2026"
        val headers = identity.headers()
        assertEquals("2026", headers["X-Browser-Year"])
        assertTrue(headers.getValue("User-Agent").contains("Chrome/152.0.0.0"))
        assertEquals(
            identity.validationHeader(headers.getValue("User-Agent"), "AIzaSyA2KlwBX3mkFo30om9LUFYQhpqLoa_BNhE"),
            headers["X-Browser-Validation"]
        )
    }

    @Test
    fun `Generic identity emits no Chrome integrity hints`() {
        properties.uaType = "Generic"
        val headers = identity.headers("test-agent")
        assertEquals(mapOf("User-Agent" to "test-agent"), headers)
    }

    @Test
    fun `external raw cookie names map to Credential`() {
        val credential = JSON.parseObject(
            """{"SESSDATA":"sess","bili_jct":"csrf","buvid3":"b3","buvid4":"b4","DedeUserID":"42","refresh_token":"refresh"}""",
            Cookies::class.java
        )
        assertEquals("sess", credential.sessData)
        assertEquals("42", credential.dedeUserId)
        assertEquals("refresh", credential.acTimeValue)
    }

    @Test
    fun `correspond path uses cookie info server timestamp`() {
        val timestamp = 1_784_302_907_043L
        val window = service.parseRefreshWindow(JSON.parseObject(
            """{"refresh":true,"timestamp":$timestamp}"""
        ))

        assertTrue(window.refresh)
        assertEquals(timestamp, window.timestampMillis)
        val path = service.correspondPath(window.timestampMillis)
        assertEquals(256, path.length)
        assertTrue(path.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `credential HTTP gzip body is decoded as UTF-8`() {
        val expected = "刷新路径不存在"
        val compressed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(expected.toByteArray(Charsets.UTF_8)) }
            output.toByteArray()
        }

        assertEquals(expected, service.decodeHttpBody(compressed, "gzip"))
    }
}
