package com.starlwr.bot.bilibili.browser

import com.alibaba.fastjson2.JSON
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class BrowserRequestObserverTest {
    @Test fun `verification lifecycle remains active after network becomes quiet and resets on navigation`() {
        observer().use { o ->
            o.accept(JSON.parseObject("""{"method":"Network.requestWillBeSent","params":{"requestId":"captcha","request":{"url":"https://api.bilibili.com/x/gaia-vgate/v2/register"}}}"""))
            assertTrue(o.officialVerificationStarted()); assertTrue(o.captchaLoading())
            o.accept(JSON.parseObject("""{"method":"Network.loadingFinished","params":{"requestId":"captcha"}}"""))
            assertFalse(o.captchaLoading()); assertTrue(o.officialVerificationStarted())
            o.accept(JSON.parseObject("""{"method":"Page.frameNavigated","params":{"frame":{"id":"main","url":"https://live.bilibili.com/123"}}}"""))
            assertFalse(o.officialVerificationStarted()); assertEquals(1L,o.documentGeneration())
        }
    }
    private val connection = mock(CdpConnection::class.java)
    private val page = BrowserPage("test", connection)

    private fun observer(): BrowserRequestObserver {
        `when`(connection.onEvent(any<java.util.function.Consumer<com.alibaba.fastjson2.JSONObject>>() ?: java.util.function.Consumer {})).thenReturn(AutoCloseable {})
        `when`(connection.call("Network.getResponseBody", mapOf("requestId" to "r"), 20))
            .thenReturn(JSON.parseObject("""{"body":"{\"code\":0,\"data\":{\"token\":\"exact-response\"}}"}"""))
        return BrowserRequestObserver(page, "123")
    }

    private fun request(o: BrowserRequestObserver) = o.accept(JSON.parseObject("""
        {"method":"Network.requestWillBeSent","params":{"requestId":"r","request":{
          "url":"https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id=123",
          "headers":{"User-Agent":"actual-chrome"}}}}
    """))
    private fun extra(o: BrowserRequestObserver) = o.accept(JSON.parseObject("""
        {"method":"Network.requestWillBeSentExtraInfo","params":{"requestId":"r","headers":{
          "Cookie":"buvid3=sent-buvid; DedeUserID=42; bili_jct=sent-jct","User-Agent":"actual-chrome","Referer":"https://live.bilibili.com/123"}}}
    """))
    private fun finished(o: BrowserRequestObserver) = o.accept(JSON.parseObject("""
        {"method":"Network.loadingFinished","params":{"requestId":"r"}}
    """))
    private fun awaitResponse(o: BrowserRequestObserver): BrowserRequestObserver.Response {
        val deadline = System.nanoTime() + 3_000_000_000
        while (System.nanoTime() < deadline) { o.poll()?.let { return it }; Thread.sleep(5) }
        fail<Nothing>("observer response timeout")
    }
    @Test fun `identity is frozen from exact extra headers and same response`() {
        observer().use { o ->
            request(o); extra(o); finished(o)
            val result = awaitResponse(o)
            assertEquals("exact-response", result.envelope.getJSONObject("data").getString("token"))
            assertEquals("sent-buvid", result.identity!!.buvid3)
            assertEquals("42", result.identity.uid)
            assertEquals("actual-chrome", result.identity.headers["User-Agent"])
            verify(connection, times(1)).call("Network.getResponseBody", mapOf("requestId" to "r"), 20)
        }
    }
    @Test fun `extra headers arriving before main event retain request provenance`() {
        observer().use { o -> extra(o); request(o); finished(o); assertEquals("sent-buvid", awaitResponse(o).identity!!.buvid3) }
    }
    @Test fun `missing extra info fails closed instead of borrowing current cookie jar`() {
        observer().use { o -> request(o); finished(o); assertNull(awaitResponse(o).identity) }
    }
}
