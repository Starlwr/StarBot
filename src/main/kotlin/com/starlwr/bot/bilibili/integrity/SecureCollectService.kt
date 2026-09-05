package com.starlwr.bot.bilibili.integrity

import com.alibaba.fastjson2.JSON
import com.starlwr.bot.bilibili.browser.BilibiliBrowserRuntime
import com.starlwr.bot.bilibili.browser.BrowserIdentityProbe
import com.starlwr.bot.bilibili.browser.BrowserPage
import com.starlwr.bot.bilibili.browser.BrowserPageNavigator
import com.starlwr.bot.core.plugin.StarBotComponent
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import java.util.concurrent.TimeUnit

@ConfigurationProperties("starbot.bilibili.integrity.secure-collect")
class SecureCollectProperties {
    var enabled: Boolean = false
    var scriptUrl: String = "https://s1.hdslb.com/bfs/seed/jinkela/short/minntaki-wasm-sdk/bili-sc-sdk.umd.js"
    var bootstrapUrl: String = "https://www.bilibili.com/robots.txt"
    var expectedVersion: String = "0.1.15"
    var operationTimeoutSeconds: Long = 60
}

/** Keeps one real browser environment for the account/device, never one per room. */
@StarBotComponent
@EnableConfigurationProperties(SecureCollectProperties::class)
class SecureCollectService(
    private val properties: SecureCollectProperties,
    private val gate: BrowserIdentityProbe,
    private val runtime: BilibiliBrowserRuntime,
    private val navigator: BrowserPageNavigator,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = Any()
    private var page: BrowserPage? = null

    @Order(-8_000)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!properties.enabled) {
            log.info("SecureCollect 未启动: starbot.bilibili.integrity.secure-collect.enabled=false")
            return
        }
        if (!gate.ensurePassed()) {
            log.warn("SecureCollect 未启动: BrowserIdentityProbe 未通过")
            return
        }
        synchronized(lock) {
            if (page != null) return
            val browserPage = runtime.createPage()
            try {
                val bootstrap = navigator.navigate(browserPage, properties.bootstrapUrl, properties.operationTimeoutSeconds)
                require(bootstrap.status in 200..399) { "SecureCollect bootstrap HTTP ${bootstrap.status}" }
                runtime.hydrateWebStorage(browserPage)
                browserPage.connection.call("Page.enable")
                browserPage.connection.call("Runtime.enable")
                val scriptUrl = JSON.toJSONString(properties.scriptUrl)
                val expression = """
                    (async () => {
                      try { await fetch('https://data.bilibili.com/v/', {credentials:'include', mode:'no-cors'}); } catch (_) {}
                      if (!globalThis.SecureCollectSDK) {
                        await new Promise((resolve, reject) => {
                          const s = document.createElement('script');
                          s.src = $scriptUrl; s.onload = resolve; s.onerror = () => reject(new Error('secure collect script load failed'));
                          document.head.appendChild(s);
                        });
                      }
                      if (!globalThis.SecureCollectSDK) throw new Error('SecureCollectSDK unavailable');
                      await globalThis.SecureCollectSDK.init();
                      await globalThis.SecureCollectSDK.collect('starbot');
                      return JSON.stringify({ok:true, version:globalThis.SecureCollectSDK.version || ''});
                    })()
                """.trimIndent()
                val result = browserPage.connection.call("Runtime.evaluate", mapOf(
                    "expression" to expression, "returnByValue" to true, "awaitPromise" to true
                ), properties.operationTimeoutSeconds).getJSONObject("result")
                val value = result?.getString("value").orEmpty()
                require(value.contains("\"ok\":true")) { "SecureCollect bootstrap failed: $value" }
                runtime.synchronizeCookiesFromBrowser("secure-collect")
                runtime.synchronizeWebStorageFromPage(browserPage, "secure-collect")
                page = browserPage
                log.info("SecureCollect 浏览器环境已启动；SDK expectedVersion={}, browser periodic interval由官方SDK管理", properties.expectedVersion)
            } catch (error: Exception) {
                runtime.closePage(browserPage)
                log.warn("SecureCollect 初始化失败，仅该完整性组件保持关闭: {}", error.toString())
                log.debug("SecureCollect initialization detail", error)
            }
        }
    }

    @PreDestroy
    override fun close() = synchronized(lock) {
        page?.let { browserPage ->
            runCatching {
                browserPage.connection.call("Runtime.evaluate", mapOf(
                    "expression" to "globalThis.SecureCollectSDK && globalThis.SecureCollectSDK.stopPeriodicReport()",
                    "returnByValue" to true
                ), 5)
            }
            runCatching { runtime.synchronizeCookiesFromBrowser("secure-collect-shutdown") }
            runCatching { runtime.synchronizeWebStorageFromPage(browserPage, "secure-collect-shutdown") }
            runtime.closePage(browserPage)
        }
        page = null
    }
}
