package com.starlwr.bot.bilibili.live.connection

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.WebSocketClient
import java.net.URI
import java.util.concurrent.CompletableFuture

class BilibiliLiveWsClientTest {
    private val uri = URI.create("wss://broadcastlv.chat.bilibili.com:2245/sub")
    private val headers = WebSocketHttpHeaders()
    @Test fun `browser identity forces bridge in jvm mode`() {
        val browser = browserClient(true)
        val jvm = jvmStub()
        val router = BilibiliLiveWsClient(jvm, browser, StarBotBilibiliProperties())
        router.executeForRoom("1", Mockito.mock(WebSocketHandler::class.java), headers, uri, true).join()
        assertTrue(browser.invoked)
        Mockito.verifyNoInteractions(jvm)
    }
    @Test fun `browser identity never falls back to JVM if bridge unavailable`() {
        val jvm = jvmStub()
        val router = BilibiliLiveWsClient(jvm, browserClient(false), StarBotBilibiliProperties())
        assertTrue(router.executeForRoom("1", Mockito.mock(WebSocketHandler::class.java), headers, uri, true).isCompletedExceptionally)
        Mockito.verifyNoInteractions(jvm)
    }

    private fun jvmStub(): WebSocketClient {
        val jvm = Mockito.mock(WebSocketClient::class.java)
        Mockito.`when`(
            jvm.execute(Mockito.any(WebSocketHandler::class.java), Mockito.any(WebSocketHttpHeaders::class.java), Mockito.any(URI::class.java))
        ).thenAnswer { CompletableFuture.completedFuture(Mockito.mock(WebSocketSession::class.java)) }
        return jvm
    }

    private class FakeBrowserClient(private val available: Boolean) :
        BrowserLiveWsClient(null, null, StarBotBilibiliProperties()) {
        var invoked = false
        override fun usable(): Boolean = available
        override fun execute(handler: WebSocketHandler, headers: WebSocketHttpHeaders?, uri: URI): CompletableFuture<WebSocketSession> {
            invoked = true
            return CompletableFuture.completedFuture(Mockito.mock(WebSocketSession::class.java))
        }
    }

    private fun browserClient(usable: Boolean): FakeBrowserClient = FakeBrowserClient(usable)

    private fun router(mode: String, browser: BrowserLiveWsClient?): BilibiliLiveWsClient {
        val properties = StarBotBilibiliProperties()
        properties.getLive().setWsTransport(mode)
        return BilibiliLiveWsClient(jvmStub(), browser, properties)
    }

    private fun connect(router: BilibiliLiveWsClient): WebSocketSession =
        router.executeForRoom("1741667419", Mockito.mock(WebSocketHandler::class.java), headers, uri).join()

    @Test
    fun `jvm mode always uses the jvm client even when the bridge is usable`() {
        val browser = browserClient(usable = true)
        val router = router("jvm", browser)

        assertTrue(connect(router).javaClass.name.contains("Mock"))

        assertFalse(browser.invoked)
    }

    @Test
    fun `browser mode dials through the bridge when usable`() {
        val browser = browserClient(usable = true)
        val router = router("browser", browser)

        connect(router)

        assertTrue(browser.invoked)
    }

    @Test
    fun `browser mode falls back to jvm when the bridge is unavailable`() {
        val browser = browserClient(usable = false)
        val router = router("browser", browser)

        connect(router)

        assertFalse(browser.invoked)
    }

    @Test
    fun `auto mode stays on jvm until a room is promoted`() {
        val browser = browserClient(usable = true)
        val router = router("auto", browser)

        connect(router)

        assertFalse(browser.invoked)

        router.noteTlsAbnormal("1741667419")
        connect(router)

        assertTrue(browser.invoked)
    }

    @Test
    fun `auto mode promotion still falls back when bridge unusable`() {
        val browser = browserClient(usable = false)
        val router = router("auto", browser)

        router.noteTlsAbnormal("1741667419")
        connect(router)

        assertFalse(browser.invoked)
    }

    @Test
    fun `promotion only affects the reported room`() {
        val browser = browserClient(usable = true)
        val router = router("auto", browser)

        router.noteTlsAbnormal("23369901")
        connect(router)

        assertFalse(browser.invoked)
    }

    @Test
    fun `interface execute delegates to jvm client`() {
        val jvm = jvmStub()
        val router = BilibiliLiveWsClient(jvm, browserClient(usable = true), StarBotBilibiliProperties())

        router.execute(Mockito.mock(WebSocketHandler::class.java), "wss://example.invalid/sub")

        Mockito.verify(jvm, Mockito.times(1))
            .execute(Mockito.any(WebSocketHandler::class.java), Mockito.anyString())
    }
}
