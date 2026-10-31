package com.starlwr.bot.bilibili.risk

import com.starlwr.bot.bilibili.api.BilibiliApiService
import com.starlwr.bot.bilibili.api.exception.ResponseCodeException
import com.starlwr.bot.bilibili.api.sign.WebSign
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties
import com.starlwr.bot.bilibili.credential.*
import com.starlwr.bot.bilibili.http.*
import com.starlwr.bot.bilibili.log.BilibiliNetworkLogger
import com.starlwr.bot.core.support.http.HttpService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.test.util.ReflectionTestUtils
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

class BrowserOverlayRoutingTest {
    @Test fun `sticky overlay rejection never immediately retries refused JVM identity`() {
        jvm(0); browser(-352)
        assertThrows(ResponseCodeException::class.java) { api.getLiveRoomConnectInfo("1", true) }
        verify(pipeline, never()).getWithIdentity(anyString(), (any(RequestIdentitySnapshot::class.java) ?: overlay), anyString())
        assertNull(registry.lastVerified())
    }
    @Test fun `pending manual verification resumes without another JVM request`() {
        `when`(provider.hasPendingBrowserVerification()).thenReturn(true)
        `when`(provider.submit(any(GaiaChallenge::class.java) ?: GaiaChallenge(-352,null,null,"test"))).thenReturn(GaiaResolution(
            harvestedEnvelope = com.alibaba.fastjson2.JSON.parseObject("""{"code":0,"data":{"token":"official-already-used"}}"""),
            browserIdentity = overlay, officialWsAuthenticated = true))
        browser(0)
        val info = api.getLiveRoomConnectInfo("1", false)
        assertEquals("browser-token", info.token, "must use independently issued room token")
        verify(pipeline, never()).getWithIdentity(anyString(), (any(RequestIdentitySnapshot::class.java) ?: overlay), anyString())
        verify(browser, times(1)).executeGetInBrowser(anyString(), anyString())
    }
    private val pipeline = mock(BilibiliHttpPipeline::class.java)
    private val browser = mock(BrowserRiskExecutor::class.java)
    private val provider = mock(GaiaChallengeProvider::class.java)
    private val registry = BrowserIdentityRegistry()
    private val headers = mapOf("Cookie" to "buvid3=jvm; DedeUserID=42", "User-Agent" to "test")
    private val overlay = RequestIdentitySnapshot(mapOf("Cookie" to "buvid3=browser; DedeUserID=42"), "browser", "42", "verified")
    private val properties = StarBotBilibiliProperties().also { it.network.apiRetryInterval = 1 }
    private val api = BilibiliApiService(properties, mock(HttpService::class.java),
        mock(BilibiliBrowserIdentity::class.java), BilibiliNetworkLogger(properties), pipeline,
        mock(BilibiliCredentialFileStore::class.java), BilibiliRiskProperties().also { it.gaiaEnabled = false }, provider, browser, registry)
    init {
        api.init()
        ReflectionTestUtils.setField(api, "sign", WebSign("ticket", Int.MAX_VALUE,
            "0123456789abcdef0123456789abcdef", "fedcba9876543210fedcba9876543210"))
        ReflectionTestUtils.setField(api, "webSignUpdatedAtMillis", AtomicLong(System.currentTimeMillis()))
        `when`(pipeline.snapshotJvmHeaders(anyString(), anyMap())).thenReturn(headers)
        `when`(browser.currentBrowserCookies(anyString())).thenReturn(mapOf("buvid3" to "browser", "DedeUserID" to "42"))
        registry.recordVerified(overlay)
    }
    private fun response(code: Int, identity: RequestIdentitySnapshot, transport: String) = BilibiliHttpResponse(
        BilibiliHttpRequest("GET", URI.create("https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id=1"), identity = identity, transport = transport),
        200, emptyMap(), """{"code":$code,"data":{"token":"$transport-token","host_list":[{"host":"example.invalid","wss_port":443}]}}""".toByteArray(), 1, 1)
    private fun jvm(code: Int) {
        `when`(pipeline.getWithIdentity(anyString(), (any(RequestIdentitySnapshot::class.java) ?: overlay), anyString()))
            .thenReturn(response(code, RequestIdentitySnapshot.jvm(headers), "jvm"))
    }
    private fun browser(code: Int) {
        `when`(browser.executeGetInBrowser(anyString(), anyString())).thenReturn(response(code, overlay, "browser"))
    }
    @Test fun `registered overlay does not hijack a normal healthy JVM request`() {
        jvm(0); browser(0)
        val info = api.getLiveRoomConnectInfo("1", false)
        assertEquals("jvm-token", info.token); assertFalse(info.identity.isBrowser)
        verify(browser, never()).executeGetInBrowser(anyString(), anyString())
    }
    @Test fun `only explicit JVM 352 allows overlay without opening another CAPTCHA page`() {
        jvm(-352); browser(0)
        val info = api.getLiveRoomConnectInfo("1", false)
        assertTrue(info.identity.isBrowser); assertEquals("browser", info.identity.buvid3)
        verify(browser, times(1)).executeGetInBrowser(anyString(), anyString())
        verify(provider, never()).submit(any(GaiaChallenge::class.java) ?: GaiaChallenge(-352,null,null,"test"))
    }
    @Test fun `overlay 352 clears the overlay and returns to circuit management`() {
        jvm(-352); browser(-352)
        val failure = assertThrows(ResponseCodeException::class.java) { api.getLiveRoomConnectInfo("1", false) }
        assertEquals(-352, failure.code); assertNull(registry.lastVerified())
        verify(provider, never()).submit(any(GaiaChallenge::class.java) ?: GaiaChallenge(-352,null,null,"test"))
    }
    @Test fun `sticky successful session uses its still current overlay`() {
        jvm(0); browser(0)
        assertTrue(api.getLiveRoomConnectInfo("1", true).identity.isBrowser)
        verify(pipeline, never()).getWithIdentity(anyString(), (any(RequestIdentitySnapshot::class.java) ?: overlay), anyString())
    }
    @Test fun `rotated browser identity makes sticky reconnect try JVM first`() {
        jvm(0); browser(0)
        `when`(browser.currentBrowserCookies(anyString())).thenReturn(mapOf("buvid3" to "rotated", "DedeUserID" to "42"))
        assertFalse(api.getLiveRoomConnectInfo("1", true).identity.isBrowser)
        assertNull(registry.lastVerified())
        verify(browser, never()).executeGetInBrowser(anyString(), anyString())
    }
}
