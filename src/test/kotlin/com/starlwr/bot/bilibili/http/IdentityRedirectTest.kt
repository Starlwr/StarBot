package com.starlwr.bot.bilibili.http

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URI

class IdentityRedirectTest {
    @Test
    fun `fixed identity is never forwarded across host scheme port or path`() {
        val source = URI.create("https://api.live.bilibili.com/xlive?id=1")
        assertTrue(canReplayIdentityRedirect(source, URI.create("https://api.live.bilibili.com/xlive?id=2")))
        listOf("https://other.invalid/xlive", "http://api.live.bilibili.com/xlive",
            "https://api.live.bilibili.com:8443/xlive", "https://api.live.bilibili.com/private").forEach {
            assertFalse(canReplayIdentityRedirect(source, URI.create(it)))
        }
    }
}
