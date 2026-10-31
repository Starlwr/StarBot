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
