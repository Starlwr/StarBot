package com.starlwr.bot.bilibili.browser

import com.starlwr.bot.bilibili.credential.StoredCookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BrowserCredentialAuditPolicyTest {
    @Test
    fun `buvid4 percent encoding drift is metadata-equivalent`() {
        assertTrue(browserCredentialValuesEquivalent("buvid4", "device+identity==", "device%2Bidentity%3D%3D"))
    }

    @Test
    fun `ticket differences remain visible to audit`() {
        assertFalse(browserCredentialValuesEquivalent("bili_ticket", "jvm-ticket", "browser-ticket"))
    }

    @Test
    fun `CDP domain Cookie uses domain and covers Bilibili subdomains`() {
        val value = cdpCookieParam(StoredCookie(
            name = "SESSDATA", value = "session", domain = ".bilibili.com",
            hostOnly = false, secure = true, httpOnly = true,
        ))

        assertEquals(".bilibili.com", value["domain"])
        assertFalse(value.containsKey("url"))
    }

    @Test
    fun `CDP host-only Cookie uses URL instead of widening domain`() {
        val value = cdpCookieParam(StoredCookie(
            name = "host-cookie", value = "value", domain = "www.bilibili.com",
            path = "/account", hostOnly = true, secure = true,
        ))

        assertEquals("https://www.bilibili.com/account", value["url"])
        assertFalse(value.containsKey("domain"))
    }

    @Test
    fun `CDP verification accepts percent encoding without changing identity`() {
        assertTrue(cookieValueEquivalent("device+identity==", "device%2Bidentity%3D%3D"))
    }
}
