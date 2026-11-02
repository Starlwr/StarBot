package com.starlwr.bot.bilibili.risk

import com.alibaba.fastjson2.JSON
import com.starlwr.bot.bilibili.browser.*
import com.starlwr.bot.bilibili.credential.BilibiliBrowserIdentity
import com.starlwr.bot.bilibili.credential.StoredCookie
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.net.URI

class BrowserGaiaVtokenProviderTest {
    private val runtime = mock(BilibiliBrowserRuntime::class.java)
    private val connection = mock(CdpConnection::class.java)
    private val page = BrowserPage("test", connection)
    private val url = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id=1"
    private val challenge = GaiaChallenge(-352, "voucher", null, url)
    private val provider = BrowserGaiaVtokenProvider(runtime, mock(BrowserPageNavigator::class.java),
        BilibiliRiskProperties(), mock(BilibiliBrowserIdentity::class.java))

    private fun state(value: String) {
        `when`(connection.call(anyString(), anyMap<String, Any>(), eq(25L)))
            .thenReturn(JSON.parseObject("""{"result":{"value":${JSON.toJSONString(value)}}}"""))
    }

    private fun cookie(value: String) {
        `when`(runtime.cookiesForRequest(URI.create(url))).thenReturn(listOf(
            StoredCookie(name = "x-bili-gaia-vtoken", value = value, domain = ".bilibili.com")))
    }

    @Test fun `existing cookie is not evidence of completing this challenge`() {
        state("{}")
        cookie("old-token")
        assertNull(provider.currentChallengeToken(page, challenge, "old-token"))
        cookie("new-token")
        assertEquals("new-token", provider.currentChallengeToken(page, challenge, "old-token"))
    }

    @Test fun `SDK promise result must belong to current challenge`() {
        state("""{"id":"another-challenge","status":"resolved","token":"wrong"}""")
        assertNull(provider.currentChallengeToken(page, challenge, null))
        state("""{"id":"${challenge.id}","status":"resolved","token":"sdk-result"}""")
        assertEquals("sdk-result", provider.currentChallengeToken(page, challenge, null))
    }

    @Test fun `one browser fetch returns the exact envelope without a second probe`() {
        state("""{"code":0,"data":{"token":"danmu-token","host_list":[]}}""")
        assertEquals("danmu-token", provider.fetchGetDanmuEnvelopeInPage(page, url)!!.getJSONObject("data").getString("token"))
        verify(connection, times(1)).call(anyString(), anyMap<String, Any>(), eq(25L))
    }
}
