package com.starlwr.bot.bilibili.risk

import com.alibaba.fastjson2.JSON
import com.starlwr.bot.bilibili.browser.*
import com.starlwr.bot.bilibili.credential.BilibiliCredentialFileStore
import com.starlwr.bot.bilibili.credential.StoredCookie
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.net.URI
import java.util.function.Consumer
import com.alibaba.fastjson2.JSONObject

class BrowserRiskExecutorTest {
    @Test fun `live API retry uses live origin fetch and preserves business failure`() {
        val runtime = mock(BilibiliBrowserRuntime::class.java)
        val gate = mock(BrowserIdentityProbe::class.java)
        val navigator = mock(BrowserPageNavigator::class.java)
        val connection = mock(CdpConnection::class.java)
        val page = BrowserPage("test", connection)
        var listener: Consumer<JSONObject>? = null
        `when`(connection.isOpen).thenReturn(true)
        `when`(connection.onEvent(any<Consumer<JSONObject>>() ?: Consumer {})).thenAnswer { listener = it.getArgument(0); AutoCloseable {} }
        val url = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id=123&gaia_vtoken=fresh"
        `when`(gate.ensurePassed()).thenReturn(true)
        `when`(runtime.createPage()).thenReturn(page)
        `when`(runtime.cookiesForRequest(URI.create(url))).thenReturn(listOf(
            StoredCookie(name = "buvid3", value = "browser-buvid", domain = ".bilibili.com")))
        `when`(connection.call(anyString(), anyMap<String, Any>(), anyLong())).thenAnswer {
            if (it.getArgument<String>(0) == "Network.getResponseBody") return@thenAnswer JSON.parseObject("""{"body":"{\"code\":-352}"}""")
            if (it.getArgument<String>(0) != "Runtime.evaluate") return@thenAnswer JSONObject()
            val params = it.getArgument<Map<String, Any>>(1)
            val expression = params["expression"] as String
            val value = if (expression == "navigator.userAgent") "Chrome/152.0.0.0" else {
                assertTrue(expression.contains("fetch("))
                assertTrue(expression.contains("credentials: 'include'"))
                listener!!.accept(JSON.parseObject("""{"method":"Network.requestWillBeSent","params":{"requestId":"r","initiator":{"stack":{"callFrames":[{"url":"starbot-browser-get.js"}]}},"request":{"url":${JSON.toJSONString(url)},"headers":{}}}}"""))
                listener!!.accept(JSON.parseObject("""{"method":"Network.requestWillBeSentExtraInfo","params":{"requestId":"r","headers":{"User-Agent":"Chrome/152","Cookie":"buvid3=browser-buvid; DedeUserID=42"}}}"""))
                listener!!.accept(JSON.parseObject("""{"method":"Network.loadingFinished","params":{"requestId":"r"}}"""))
                """{"status":200,"headers":{},"body":"{\"code\":-352}"}"""
            }
            JSON.parseObject("""{"result":{"value":${JSON.toJSONString(value)}}}""")
        }
        val executor = BrowserRiskExecutor(gate, runtime, mock(Http412Resolver::class.java),
            mock(BilibiliCredentialFileStore::class.java), navigator)
        val response = executor.executeGetInBrowser(url, "challenge-123")!!
        assertEquals(200, response.status)
        assertEquals(-352, response.json().getIntValue("code"))
        assertEquals("browser", response.request.transport)
        assertEquals("challenge-123", response.request.identity!!.sourceChallengeId)
        verify(navigator).navigate(page, "https://live.bilibili.com/123", 30)
        verify(runtime, never()).closePage(page)
        executor.shutdown()
        verify(runtime).closePage(page)
    }
}
