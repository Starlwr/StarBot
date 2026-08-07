package com.starlwr.bot.bilibili.risk

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties
import com.starlwr.bot.bilibili.credential.BilibiliBrowserIdentity
import com.starlwr.bot.bilibili.credential.BilibiliCredentialFileStore
import com.starlwr.bot.bilibili.http.BilibiliHttpPipeline
import com.starlwr.bot.bilibili.http.BilibiliHttpRequest
import com.starlwr.bot.bilibili.http.BilibiliHttpResponse
import com.starlwr.bot.bilibili.log.BilibiliNetworkLogger
import com.starlwr.bot.bilibili.util.BilibiliApiUtil
import com.starlwr.bot.core.util.HttpUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.net.URI

class GaiaRetryTest {
    @Test
    fun `interactive token retries only the original request once`() {
        val pipeline = Mockito.mock(BilibiliHttpPipeline::class.java)
        val original = BilibiliHttpRequest("GET", URI.create("https://api.bilibili.com/x/test"))
        val first = BilibiliHttpResponse(original, 200,
            mapOf("x-bili-gaia-vvoucher" to listOf("voucher-1")),
            """{"code":-352,"message":"risk","data":{"ga_data":{"type":"slide"}}}""".toByteArray(), 1, 1)
        val second = BilibiliHttpResponse(original, 200, emptyMap(),
            """{"code":0,"data":{"ok":true}}""".toByteArray(), 1, 1)
        val requestedUrls = mutableListOf<String>()
        Mockito.doAnswer { invocation ->
            requestedUrls += invocation.getArgument<String>(0)
            if (requestedUrls.size == 1) first else second
        }.`when`(pipeline)
            .get(Mockito.anyString(), Mockito.anyMap(), Mockito.anyString())
        var captured: GaiaChallenge? = null
        val provider = object : GaiaChallengeProvider {
            override fun submit(challenge: GaiaChallenge): String {
                captured = challenge
                return "verified token"
            }
        }
        val properties = StarBotBilibiliProperties()
        val api = BilibiliApiUtil(
            properties, Mockito.mock(HttpUtil::class.java), Mockito.mock(BilibiliBrowserIdentity::class.java),
            BilibiliNetworkLogger(properties), pipeline, Mockito.mock(BilibiliCredentialFileStore::class.java),
            BilibiliRiskProperties(), provider,
        )
        api.init()

        val result = api.requestBilibiliApi(original.uri.toString(), "GET", emptyMap(), emptyMap())

        assertTrue(result.getBooleanValue("ok"))
        assertEquals("voucher-1", captured?.voucher)
        assertEquals(-352, captured?.businessCode)
        Mockito.verify(pipeline, Mockito.times(2)).get(Mockito.anyString(), Mockito.anyMap(), Mockito.anyString())
        assertTrue(requestedUrls[1].contains("gaia_vtoken=verified+token"))
    }
}
