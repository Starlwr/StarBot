package com.starlwr.bot.bilibili.risk

import com.starlwr.bot.bilibili.api.BilibiliApiService
import com.starlwr.bot.bilibili.api.exception.ResponseCodeException
import com.starlwr.bot.bilibili.api.sign.WebSign
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties
import com.starlwr.bot.bilibili.credential.BilibiliBrowserIdentity
import com.starlwr.bot.bilibili.credential.BilibiliCredentialFileStore
import com.starlwr.bot.bilibili.http.*
import com.starlwr.bot.bilibili.log.BilibiliNetworkLogger
import com.starlwr.bot.core.support.http.HttpService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.test.util.ReflectionTestUtils
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

class WbiRefreshOrderingTest {
    private val properties = StarBotBilibiliProperties().also { it.network.apiRetryInterval = 1 }
    private val pipeline = Mockito.mock(BilibiliHttpPipeline::class.java)
    private val submitted = mutableListOf<GaiaChallenge>()
    private val provider = object : GaiaChallengeProvider {
        override fun submit(challenge: GaiaChallenge): GaiaResolution? { submitted += challenge; return null }
    }
    private val api = Mockito.spy(BilibiliApiService(properties, Mockito.mock(HttpService::class.java),
        Mockito.mock(BilibiliBrowserIdentity::class.java), BilibiliNetworkLogger(properties), pipeline,
        Mockito.mock(BilibiliCredentialFileStore::class.java), BilibiliRiskProperties(), provider,
        Mockito.mock(BrowserRiskExecutor::class.java), null)).also { it.init() }
    private val original = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id=1&wts=1&w_rid=old"
    private fun response(code: Int) = BilibiliHttpResponse(BilibiliHttpRequest("GET", URI.create(original)),
        200, emptyMap(), """{"code":$code,"data":{"ok":true}}""".toByteArray(), 1, 1)

    @Test
    fun `stale WBI refresh occurs before resolver and retry URL is re signed`() {
        val calls = mutableListOf<String>()
        Mockito.doAnswer { call ->
            calls += call.getArgument<String>(0)
            if (calls.size == 1) response(-352) else response(0)
        }.`when`(pipeline).get(Mockito.anyString(), Mockito.anyMap(), Mockito.anyString())
        val refreshed = WebSign("ticket", Int.MAX_VALUE,
            "0123456789abcdef0123456789abcdef", "fedcba9876543210fedcba9876543210")
        Mockito.doAnswer {
            ReflectionTestUtils.setField(api, "sign", refreshed)
            ReflectionTestUtils.setField(api, "webSignUpdatedAtMillis", AtomicLong(System.currentTimeMillis()))
            refreshed
        }.`when`(api).generateBilibiliWebSign()
        assertTrue(api.requestBilibiliApi(original, "GET", emptyMap(), emptyMap()).getBooleanValue("ok"))
        assertEquals(2, calls.size)
        assertEquals(original, calls[0])
        assertNotEquals(original, calls[1])
        assertFalse(calls[1].contains("w_rid=old"))
        assertFalse(calls[1].contains("wts=1&"))
        Mockito.verify(api, Mockito.times(1)).generateBilibiliWebSign()
        assertEquals(0, submitted.size)
    }

    @Test
    fun `fresh WBI is not force refreshed and unresolved 352 is preserved`() {
        ReflectionTestUtils.setField(api, "webSignUpdatedAtMillis", AtomicLong(System.currentTimeMillis()))
        Mockito.`when`(pipeline.get(Mockito.anyString(), Mockito.anyMap(), Mockito.anyString())).thenReturn(response(-352))
        val error = assertThrows(ResponseCodeException::class.java) {
            api.requestBilibiliApi(original, "GET", emptyMap(), emptyMap())
        }
        assertEquals(-352, error.code)
        Mockito.verify(api, Mockito.never()).generateBilibiliWebSign()
        assertEquals(1, submitted.size)
    }
}
