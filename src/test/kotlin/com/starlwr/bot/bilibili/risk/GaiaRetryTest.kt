package com.starlwr.bot.bilibili.risk

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties
import com.starlwr.bot.bilibili.credential.BilibiliBrowserIdentity
import com.starlwr.bot.bilibili.credential.BilibiliCredentialFileStore
import com.starlwr.bot.bilibili.http.BilibiliHttpPipeline
import com.starlwr.bot.bilibili.http.BilibiliHttpRequest
import com.starlwr.bot.bilibili.http.BilibiliHttpResponse
import com.starlwr.bot.bilibili.log.BilibiliNetworkLogger
import com.starlwr.bot.bilibili.api.BilibiliApiService
import com.starlwr.bot.core.support.http.HttpService
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
            override fun submit(challenge: GaiaChallenge): GaiaResolution {
                captured = challenge
                return GaiaResolution(vtoken = "verified token")
            }
        }
        val properties = StarBotBilibiliProperties()
        val api = BilibiliApiService(
            properties, Mockito.mock(HttpService::class.java), Mockito.mock(BilibiliBrowserIdentity::class.java),
            BilibiliNetworkLogger(properties), pipeline, Mockito.mock(BilibiliCredentialFileStore::class.java),
            BilibiliRiskProperties(), provider,
            Mockito.mock(BrowserRiskExecutor::class.java),
        )
        api.init()
        org.springframework.test.util.ReflectionTestUtils.setField(api, "webSignUpdatedAtMillis",
            java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis()))

        val result = api.requestBilibiliApi(original.uri.toString(), "GET", emptyMap(), emptyMap())

        assertTrue(result.getBooleanValue("ok"))
        assertEquals("voucher-1", captured?.voucher)
        assertEquals(-352, captured?.businessCode)
        Mockito.verify(pipeline, Mockito.times(2)).get(Mockito.anyString(), Mockito.anyMap(), Mockito.anyString())
        assertTrue(requestedUrls[1].contains("gaia_vtoken=verified+token"))
    }

    @Test
    fun `wbi signed requests are re-signed with gaia_vtoken in params`() {
        val pipeline = Mockito.mock(BilibiliHttpPipeline::class.java)
        val original = BilibiliHttpRequest("GET", URI.create(
            "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id=27438739&type=0&wts=1792866579&w_rid=24a4a33d1ec11045e564eb54be9d34fc"))
        val first = BilibiliHttpResponse(original, 200,
            mapOf("x-bili-gaia-vvoucher" to listOf("voucher-wbi")),
            """{"code":-352,"message":"risk"}""".toByteArray(), 1, 1)
        val second = BilibiliHttpResponse(original, 200, emptyMap(),
            """{"code":0,"data":{"token":"danmu-token","host_list":[]}}""".toByteArray(), 1, 1)
        val requestedUrls = mutableListOf<String>()
        Mockito.doAnswer { invocation ->
            requestedUrls += invocation.getArgument<String>(0)
            if (requestedUrls.size == 1) first else second
        }.`when`(pipeline)
            .get(Mockito.anyString(), Mockito.anyMap(), Mockito.anyString())
        val provider = object : GaiaChallengeProvider {
            override fun submit(challenge: GaiaChallenge): GaiaResolution = GaiaResolution(vtoken = "verifiedtoken")
        }
        val properties = StarBotBilibiliProperties()
        val store = Mockito.mock(BilibiliCredentialFileStore::class.java)
        val api = BilibiliApiService(
            properties, Mockito.mock(HttpService::class.java), Mockito.mock(BilibiliBrowserIdentity::class.java),
            BilibiliNetworkLogger(properties), pipeline, store,
            BilibiliRiskProperties(), provider,
            Mockito.mock(BrowserRiskExecutor::class.java),
        )
        api.init()
        // 注入 WBI 状态以启用重签路径
        val webSign = com.starlwr.bot.bilibili.api.sign.WebSign(
            "ticket", (System.currentTimeMillis() / 1000 + 3600).toInt(),
            "0123456789abcdef0123456789abcdef", "fedcba9876543210fedcba9876543210")
        org.springframework.test.util.ReflectionTestUtils.setField(api, "sign", webSign)
        org.springframework.test.util.ReflectionTestUtils.setField(api, "webSignUpdatedAtMillis",
            java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis()))
        // 模拟浏览器同步回来的 vtoken cookie
        val vtokenCookie = com.starlwr.bot.bilibili.credential.StoredCookie(
            name = "x-bili-gaia-vtoken", value = "14802565fed5410c9c9c1d5f6d7500b2",
            domain = ".bilibili.com", path = "/", hostOnly = false, secure = true,
            transportScope = "browser", source = "gaia-vtoken")
        val envelope = Mockito.mock(com.starlwr.bot.bilibili.credential.CredentialEnvelope::class.java)
        Mockito.`when`(envelope.cookies).thenReturn(mutableListOf(vtokenCookie))
        Mockito.`when`(store.snapshot()).thenReturn(envelope)

        val result = api.requestBilibiliApi(original.uri.toString(), "GET", emptyMap(), emptyMap())

        println("DEBUG urls=" + requestedUrls)
        assertTrue(result.containsKey("token"), "重试应返回 getDanmuInfo 的 data 本体")
        val retryUrl = requestedUrls[1]
        assertTrue(retryUrl.contains("w_rid="), "重试请求必须重新经过 WBI 签名")
        assertTrue(retryUrl.contains("gaia_vtoken=verifiedtoken"), "gaia_vtoken 必须参与签名参数")
        assertTrue(!retryUrl.contains("wts=1792866579"), "旧 wts 必须被替换")
    }
}
