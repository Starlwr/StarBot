package com.starlwr.bot.bilibili.browser

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.bilibili.credential.BilibiliBrowserIdentity
import com.starlwr.bot.bilibili.credential.BilibiliCredentialFileStore
import com.starlwr.bot.bilibili.credential.BilibiliCredentialProperties
import com.starlwr.bot.bilibili.credential.IdentityProbePersistence
import com.starlwr.bot.bilibili.http.BilibiliHttpProperties
import com.starlwr.bot.bilibili.http.configuredProxySelector
import com.starlwr.bot.core.plugin.StarBotComponent
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

enum class IdentityProbeStatus { PASSED, DEGRADED, FAILED, UNAVAILABLE }

data class IpObservation(
    val endpoint: String,
    val address: String,
    val country: String,
    val province: String,
    val isp: String,
)

data class BrowserIdentityObservation(
    val userAgent: String,
    val platform: String,
    val languages: List<String>,
    val timezone: String,
    val resolution: String,
    val webdriver: Boolean,
    val requestHeaders: Map<String, String>,
)

data class IdentityProbeResult(
    val status: IdentityProbeStatus,
    val jvm: List<IpObservation> = emptyList(),
    val browser: List<IpObservation> = emptyList(),
    val browserIdentity: BrowserIdentityObservation? = null,
    val reason: String = "",
    val checkedAtEpochMillis: Long = System.currentTimeMillis(),
)

/** A harmless, account-level gate for every browser-only resolver/keeper. */
@StarBotComponent
class BrowserIdentityProbe(
    private val browserProperties: BilibiliBrowserProperties,
    private val httpProperties: BilibiliHttpProperties,
    private val accountProperties: BilibiliCredentialProperties,
    private val identity: BilibiliBrowserIdentity,
    private val runtime: BilibiliBrowserRuntime,
    private val credentialStore: BilibiliCredentialFileStore,
    private val navigator: BrowserPageNavigator,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .apply { configuredProxySelector(httpProperties.proxyUri)?.let(::proxy) }
        .build()
    private val lock = Any()
    @Volatile private var cached: IdentityProbeResult? = null
    var cacheSeconds: Long = 300

    @Order(-9_000)
    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        if (!browserProperties.enabled) return
        val result = runCatching { probe(force = true) }.getOrElse { error ->
            IdentityProbeResult(IdentityProbeStatus.FAILED, reason = error.toString()).also {
                cached = it
                persist(it)
            }
        }
        when (result.status) {
            IdentityProbeStatus.PASSED -> log.info("Bilibili 浏览器身份探针通过: jvmIp={}, browserIp={}, resolution={}",
                result.jvm.firstOrNull()?.address, result.browser.firstOrNull()?.address,
                result.browserIdentity?.resolution)
            IdentityProbeStatus.DEGRADED -> log.warn("Bilibili 浏览器身份探针降级通过: {}", result.reason)
            else -> log.warn("Bilibili 浏览器身份探针未通过，浏览器 Resolver/Keeper 将保持关闭: {}", result.reason)
        }
    }

    fun ensurePassed(): Boolean {
        val result = probe(false)
        return result.status == IdentityProbeStatus.PASSED || result.status == IdentityProbeStatus.DEGRADED
    }

    fun current(): IdentityProbeResult? = cached

    fun probe(force: Boolean = false): IdentityProbeResult = synchronized(lock) {
        cached?.takeIf { !force && System.currentTimeMillis() - it.checkedAtEpochMillis < cacheSeconds * 1000 }?.let { return it }
        val configuredUa = identity.headers(accountProperties.userAgent)["User-Agent"] ?: accountProperties.userAgent
        val browserInfo = runtime.start(configuredUa)
            ?: return IdentityProbeResult(IdentityProbeStatus.UNAVAILABLE, reason = "Chrome runtime unavailable").also {
                cached = it; persist(it)
            }
        val jvm = IP_ENDPOINTS.mapNotNull { endpoint -> runCatching { requestJvm(endpoint, configuredUa) }.getOrNull() }
        val page = runtime.createPage()
        val browserResults: List<Pair<IpObservation, Map<String, String>>>
        val browserIdentity: BrowserIdentityObservation
        try {
            browserResults = IP_ENDPOINTS.mapNotNull { endpoint -> runCatching { requestBrowser(page, endpoint) }.getOrNull() }
            browserIdentity = observeBrowser(page, browserResults.lastOrNull()?.second.orEmpty())
        } finally {
            runtime.closePage(page)
        }
        runtime.synchronizeCookiesFromBrowser("identity-probe")
        val result = evaluate(configuredUa, browserInfo, jvm, browserResults.map { it.first }, browserIdentity)
        cached = result
        persist(result)
        result
    }

    private fun evaluate(
        configuredUa: String,
        runtimeInfo: BrowserRuntimeInfo,
        jvm: List<IpObservation>,
        browser: List<IpObservation>,
        observed: BrowserIdentityObservation,
    ): IdentityProbeResult {
        if (jvm.size < 2) return IdentityProbeResult(IdentityProbeStatus.FAILED, jvm, browser, observed, "JVM IP探针成功数不足2")
        if (browser.size < 2) return IdentityProbeResult(IdentityProbeStatus.FAILED, jvm, browser, observed, "Chrome IP探针成功数不足2")
        val jvmAddresses = jvm.map { it.address }.filter { it.isNotBlank() }.toSet()
        val browserAddresses = browser.map { it.address }.filter { it.isNotBlank() }.toSet()
        if (jvmAddresses.size != 1) return IdentityProbeResult(IdentityProbeStatus.FAILED, jvm, browser, observed, "JVM探针出口不一致: $jvmAddresses")
        if (browserAddresses.size != 1) return IdentityProbeResult(IdentityProbeStatus.FAILED, jvm, browser, observed, "Chrome探针出口不一致: $browserAddresses")
        val sameAddress = jvmAddresses.first() == browserAddresses.first()
        if (!sameAddress && !browserProperties.allowAddressFamilySplit) {
            return IdentityProbeResult(IdentityProbeStatus.FAILED, jvm, browser, observed,
                "JVM与Chrome公网出口不一致: ${jvmAddresses.first()} != ${browserAddresses.first()}")
        }
        if (!sameAddress) {
            val j = jvm.first(); val b = browser.first()
            if (j.country != b.country || j.province != b.province || j.isp != b.isp) {
                return IdentityProbeResult(IdentityProbeStatus.FAILED, jvm, browser, observed, "地址族分流且地区/ISP不一致")
            }
        }
        if (observed.resolution != BilibiliBrowserProperties.BROWSER_RESOLUTION) {
            return IdentityProbeResult(IdentityProbeStatus.FAILED, jvm, browser, observed,
                "浏览器分辨率设置未生效: ${observed.resolution}")
        }
        val wireUa = observed.requestHeaders.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        if (wireUa.isNullOrBlank() || wireUa != observed.userAgent) {
            return IdentityProbeResult(IdentityProbeStatus.FAILED, jvm, browser, observed,
                "实际出站UA与navigator不一致: wire=$wireUa navigator=${observed.userAgent}")
        }
        val expectedMajor = CHROME_MAJOR.find(configuredUa)?.groupValues?.get(1)
        val actualMajor = CHROME_MAJOR.find(observed.userAgent)?.groupValues?.get(1)
        if (expectedMajor != null && actualMajor != null && expectedMajor != actualMajor) {
            return IdentityProbeResult(IdentityProbeStatus.DEGRADED, jvm, browser, observed,
                "配置Chrome major=$expectedMajor，实际浏览器major=$actualMajor；采用实际浏览器身份 (${runtimeInfo.product})")
        }
        if (!sameAddress) {
            val j = jvm.first(); val b = browser.first()
            return IdentityProbeResult(IdentityProbeStatus.DEGRADED, jvm, browser, observed,
                "IPv4/IPv6 出口地址不同但归属信息一致: ${j.address} / ${b.address}, ${j.country}/${j.province}/${j.isp}")
        }
        return IdentityProbeResult(IdentityProbeStatus.PASSED, jvm, browser, observed)
    }

    private fun requestJvm(endpoint: String, userAgent: String): IpObservation {
        val builder = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(15)).GET()
        identity.headers(userAgent).forEach(builder::header)
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
        return parseIp(endpoint, response.body())
    }

    private fun requestBrowser(page: BrowserPage, endpoint: String): Pair<IpObservation, Map<String, String>> {
        val connection = page.connection
        connection.call("Emulation.setDeviceMetricsOverride", mapOf(
            "width" to 1920, "height" to 1080, "screenWidth" to 1920, "screenHeight" to 1080,
            "deviceScaleFactor" to 1, "mobile" to false
        ))
        val document = navigator.navigate(page, endpoint, 20)
        require(document.status in 200..299) { "HTTP ${document.status}" }
        return parseIp(endpoint, document.body.toString(Charsets.UTF_8)) to document.requestHeaders
    }

    private fun observeBrowser(page: BrowserPage, requestHeaders: Map<String, String>): BrowserIdentityObservation {
        val script = """
            (() => ({
              userAgent: navigator.userAgent,
              platform: navigator.platform,
              languages: Array.from(navigator.languages || []),
              timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || '',
              resolution: String(screen.width) + 'x' + String(screen.height),
              webdriver: Boolean(navigator.webdriver)
            }))()
        """.trimIndent()
        val value = evaluate(page.connection, script)
        val json = when (value) {
            is JSONObject -> value
            else -> JSON.parseObject(JSON.toJSONString(value))
        }
        return BrowserIdentityObservation(
            json.getString("userAgent").orEmpty(), json.getString("platform").orEmpty(),
            json.getJSONArray("languages")?.toJavaList(String::class.java).orEmpty(),
            json.getString("timezone").orEmpty(), json.getString("resolution").orEmpty(),
            json.getBooleanValue("webdriver"), requestHeaders
        )
    }

    private fun evaluateString(connection: CdpConnection, expression: String): String = evaluate(connection, expression)?.toString().orEmpty()
    private fun evaluate(connection: CdpConnection, expression: String): Any? {
        val result = connection.call("Runtime.evaluate", mapOf(
            "expression" to expression, "returnByValue" to true, "awaitPromise" to true
        )).getJSONObject("result") ?: return null
        result.getJSONObject("exceptionDetails")?.let { error("Browser evaluate failed: ${it.toJSONString()}") }
        return result["value"]
    }

    private fun parseIp(endpoint: String, body: String): IpObservation {
        val root = JSON.parseObject(body)
        require(root.getIntValue("code", -1) == 0) { "business code=${root.getIntValue("code", -1)}" }
        val data = root.getJSONObject("data") ?: error("IP endpoint omitted data")
        return IpObservation(endpoint, data.getString("addr").orEmpty(), data.getString("country").orEmpty(),
            data.getString("province").orEmpty(), data.getString("isp").orEmpty())
    }

    private fun persist(result: IdentityProbeResult) {
        credentialStore.update(critical = true) { envelope ->
            envelope.identityProbe = IdentityProbePersistence(
                result.status.name, result.checkedAtEpochMillis,
                result.jvm.firstOrNull()?.address.orEmpty(), result.browser.firstOrNull()?.address.orEmpty(),
                result.browser.firstOrNull()?.country.orEmpty(), result.browser.firstOrNull()?.province.orEmpty(),
                result.browser.firstOrNull()?.isp.orEmpty(), result.reason
            )
            result.browserIdentity?.let { observed ->
                envelope.effectiveBrowserProfile.userAgent = observed.userAgent
                envelope.effectiveBrowserProfile.platform = observed.platform
                envelope.effectiveBrowserProfile.clientHints = observed.requestHeaders
                    .filterKeys { it.lowercase(Locale.ROOT).startsWith("sec-ch-ua") }.toMutableMap()
                envelope.effectiveBrowserProfile.observedAtEpochMillis = result.checkedAtEpochMillis
            }
        }
    }

    companion object {
        private val IP_ENDPOINTS = listOf(
            "https://api.bilibili.com/x/web-interface/zone",
            "https://api.live.bilibili.com/xlive/web-room/v1/index/getIpInfo",
            "https://app.bilibili.com/x/resource/ip",
        )
        private val CHROME_MAJOR = Regex("Chrome/(\\d+)", RegexOption.IGNORE_CASE)
    }
}
