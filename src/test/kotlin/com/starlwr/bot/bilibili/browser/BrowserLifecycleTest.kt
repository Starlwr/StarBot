package com.starlwr.bot.bilibili.browser

import com.starlwr.bot.bilibili.credential.BilibiliCredentialFileStore
import com.starlwr.bot.bilibili.http.BilibiliHttpProperties
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties
import com.starlwr.bot.bilibili.live.connection.BrowserLiveWsClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import java.nio.file.Path
import java.net.InetSocketAddress
import java.net.Socket

class BrowserLifecycleTest {
    @TempDir lateinit var temporary: Path

    private fun browserProperties(): BilibiliBrowserProperties {
        val executable = System.getProperty("starbot.test.browser")
        assumeTrue(!executable.isNullOrBlank(), "Set starbot.test.browser to run the isolated Chrome smoke test")
        return BilibiliBrowserProperties().apply {
            enabled = true
            this.executable = executable
            browserHome = temporary.resolve("Browser").toString()
            profileDirectory = temporary.resolve("Profile").toString()
            headless = true
            downloadEnabled = false
            allowSystemChrome = false
            allowSystemChromium = false
        }
    }

    @Test fun `occupied profile does not blacklist executable or close its existing owner`() {
        val properties = browserProperties()
        val http = BilibiliHttpProperties()
        val owner = BilibiliBrowserRuntime(properties, http, ChromeForTestingManager(properties, http),
            mock(BilibiliCredentialFileStore::class.java))
        val manager = ChromeForTestingManager(properties, http)
        val contender = BilibiliBrowserRuntime(properties, http, manager, mock(BilibiliCredentialFileStore::class.java))
        try {
            assertNotNull(owner.start("Chrome/152.0.7923.0"))
            assertNull(contender.start("Chrome/152.0.7923.0"))
            assertNotNull(manager.resolve("Chrome/152.0.7923.0"), "Transient startup failure must not blacklist Chrome")
            contender.close()
            assertNotNull(owner.browser!!.call("Browser.getVersion").getString("product"))
        } finally {
            contender.close()
            owner.close()
        }
    }

    @Test fun `real Chrome starts with isolated profile and closes its endpoint`() {
        val properties = browserProperties()
        val http = BilibiliHttpProperties()
        val runtime = BilibiliBrowserRuntime(properties, http, ChromeForTestingManager(properties, http),
            mock(BilibiliCredentialFileStore::class.java))
        var port = 0
        var owned: List<ProcessHandle> = emptyList()
        try {
            val info = runtime.start("Mozilla/5.0 Chrome/152.0.7923.0 Safari/537.36")
            assertNotNull(info)
            port = info!!.port
            val processField = BilibiliBrowserRuntime::class.java.getDeclaredField("process").apply { isAccessible = true }
            val process = processField.get(runtime) as Process
            owned = listOf(process.toHandle()) + process.toHandle().descendants().toList()
            assertNotNull(runtime.browser!!.call("Browser.getVersion").getString("product"))
            val page = runtime.createPage()
            val bridge = BrowserLiveWsClient(runtime, BrowserPageNavigator(), StarBotBilibiliProperties())
            try {
                assertEquals("null", bridge.evaluateOnPage(page, "location.origin"))
                val companion = BrowserLiveWsClient::class.java.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
                val script = companion.javaClass.getDeclaredMethod("getBRIDGE_SCRIPT").apply { isAccessible = true }.invoke(companion) as String
                assertEquals("installed", bridge.evaluateOnPage(page, script))
                assertEquals("ready", bridge.evaluateOnPage(page, script))
                val rejected = bridge.evaluateOnPage(page, "window.__starbotWsBridge.dial(1, 'invalid-url')") as com.alibaba.fastjson2.JSONObject
                assertFalse(rejected.getBooleanValue("ok"))
                // A manual CAPTCHA can take minutes: dispatch must return at
                // once, while its eventual SDK promise result stays observable.
                val provider = com.starlwr.bot.bilibili.risk.BrowserGaiaVtokenProvider(runtime, BrowserPageNavigator(),
                    com.starlwr.bot.bilibili.risk.BilibiliRiskProperties(),
                    mock(com.starlwr.bot.bilibili.credential.BilibiliBrowserIdentity::class.java))
                val challenge = com.starlwr.bot.bilibili.risk.GaiaChallenge(-352, "test-voucher", null,
                    "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id=1")
                bridge.evaluateOnPage(page, "window.CaptchaLoader = {load: async () => async (params) => params.riskParams.v_voucher + '-result'}")
                assertEquals("started", bridge.evaluateOnPage(page, provider.buildNativeCaptchaScript(challenge)))
                assertEquals("test-voucher-result", provider.currentChallengeToken(page, challenge, null))
                // Real Network ExtraInfo and Runtime.evaluate initiator identity,
                // exercised against loopback without contacting Bilibili.
                val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
                server.createContext("/") { exchange ->
                    val api = exchange.requestURI.path.contains("getDanmuInfo")
                    val body = if (api) """{"code":0,"data":{"token":"local-token"}}""" else "<html><body>local audit</body></html>"
                    exchange.responseHeaders.add("Content-Type", if (api) "application/json" else "text/html")
                    if (!api) exchange.responseHeaders.add("Set-Cookie","buvid3=loopback-request; Path=/")
                    exchange.sendResponseHeaders(200,body.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                }
                server.start()
                try {
                    val origin = "http://127.0.0.1:${server.address.port}"
                    BrowserPageNavigator().navigate(page,"$origin/")
                    val readiness = com.starlwr.bot.bilibili.risk.BrowserGaiaVtokenProvider.NATIVE_READINESS_SCRIPT
                    bridge.evaluateOnPage(page,"window._loadLogicPromise_ = new Promise(r => window.__resolveNativeTest = r)")
                    val pending = com.alibaba.fastjson2.JSON.parseObject(bridge.evaluateOnPage(page,readiness) as String)
                    assertTrue(pending.getBooleanValue("bootstrapPending"))
                    bridge.evaluateOnPage(page,"window.__resolveNativeTest()")
                    val settled = com.alibaba.fastjson2.JSON.parseObject(bridge.evaluateOnPage(page,readiness) as String)
                    assertFalse(settled.getBooleanValue("bootstrapPending"))
                    bridge.evaluateOnPage(page,"document.body.insertAdjacentHTML('beforeend','<div class=geetest_panel style=display:none></div>')")
                    val hidden = com.alibaba.fastjson2.JSON.parseObject(bridge.evaluateOnPage(page,readiness) as String)
                    assertTrue(hidden.getBooleanValue("panelPresent"))
                    assertFalse(hidden.getBooleanValue("panelVisible"))
                    val endpoint = java.net.URI.create("$origin/xlive/web-room/v1/index/getDanmuInfo")
                    BrowserRequestObserver(page,"1","starbot-browser-get.js",endpoint).use { observer ->
                        observer.enable()
                        page.connection.call("Runtime.evaluate",mapOf("expression" to
                            "fetch('$endpoint?id=1',{credentials:'include'}).then(r=>r.json())\n//# sourceURL=starbot-browser-get.js",
                            "awaitPromise" to true,"returnByValue" to true))
                        val until = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                        var observed = observer.poll()
                        while (observed == null && System.nanoTime()<until) { Thread.sleep(10); observed=observer.poll() }
                        assertNotNull(observed,"Real CDP initiator marker must identify our fetch")
                        assertEquals("loopback-request",observed!!.identity!!.buvid3)
                        assertEquals("local-token",observed.envelope.getJSONObject("data").getString("token"))
                    }
                } finally { server.stop(0) }
            } finally {
                bridge.shutdown()
                runtime.closePage(page)
            }
        } finally {
            runtime.close()
        }
        assertTrue(owned.none { it.isAlive }, "Chrome process tree must exit")
        assertThrows(java.io.IOException::class.java) { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) } }
        assertNull(runtime.runtimeInfo())
        assertNull(runtime.start("Chrome/152.0.7923.0"))
    }
}
