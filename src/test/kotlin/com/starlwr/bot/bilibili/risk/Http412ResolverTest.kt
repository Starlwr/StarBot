package com.starlwr.bot.bilibili.risk

import com.alibaba.fastjson2.JSON
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

class Http412ResolverTest {
    @Test
    fun `parses url-safe challenge and solves bounded proof of work`() {
        val q = "starbot-test-"
        val target = MessageDigest.getInstance("SHA-256")
            .digest("${q}3".toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val payload = JSON.toJSONString(mapOf(
            "q" to q, "r" to target, "iat" to Instant.now().epochSecond,
            "exp" to Instant.now().plusSeconds(300).epochSecond, "verity" to 0,
        ))
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        val properties = BilibiliRiskProperties().apply { powLimit = 10; submitReserveSeconds = 1 }
        val resolver = Http412Resolver(properties)
        val challenge = resolver.parse("X-BILI-SEC-TOKEN=v1,a.$encoded.z; Path=/; Secure")
        assertNotNull(challenge)
        assertEquals(3, resolver.solve(challenge!!).result)
    }
}
