package com.starlwr.bot.bilibili.credential

import com.alibaba.fastjson2.JSON
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties
import com.starlwr.bot.bilibili.log.BilibiliNetworkLogger
import com.starlwr.bot.bilibili.model.Cookies
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
