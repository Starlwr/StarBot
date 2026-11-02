package com.starlwr.bot.bilibili.risk

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.bilibili.browser.BilibiliBrowserRuntime
import com.starlwr.bot.bilibili.browser.BrowserIdentityProbe
import com.starlwr.bot.bilibili.browser.captureRequestIdentity
import com.starlwr.bot.bilibili.browser.BrowserPageNavigator
import com.starlwr.bot.bilibili.browser.BrowserPage
import com.starlwr.bot.bilibili.credential.BilibiliCredentialFileStore
import com.starlwr.bot.bilibili.credential.StoredCookie
import com.starlwr.bot.bilibili.http.BilibiliHttpRequest
import com.starlwr.bot.bilibili.http.BilibiliHttpResponse
import com.starlwr.bot.core.plugin.StarBotComponent
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class BrowserNavigationResult(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
)

@StarBotComponent
class BrowserRiskExecutor(
    private val gate: BrowserIdentityProbe,
    private val runtime: BilibiliBrowserRuntime,
    private val resolver: Http412Resolver,
    private val credentialStore: BilibiliCredentialFileStore,
    private val navigator: BrowserPageNavigator,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val requestPageLock = Any()
    private var requestPage: BrowserPage? = null
    private var requestOriginReady = false

    fun resolve412(request: BilibiliHttpRequest): BilibiliHttpResponse? {
        runtime.enterResolverIdentity()
        if (!request.method.equals("GET", true) || !gate.ensurePassed()) return null
        val snapshot = credentialStore.snapshot() ?: return null
        val partition = listOf(
            snapshot.account.dedeUserId, request.uri.host,
            snapshot.effectiveBrowserProfile.proxyProfileId, "browser"
        ).joinToString("|")
        return resolver.singleFlight(partition) {
            val started = System.nanoTime()
            val page = runtime.createPage()
            try {
                var navigation = navigate(page, request.uri.toString())
                if (navigation.status != 412) return@singleFlight navigation.toResponse(request.copy(identity = captureRequestIdentity(runtime, page,
                    request.uri.toString(), "http412-response")), started)
                runtime.synchronizeCookiesFromBrowser("http412-challenge")
                val challengeCookie = runtime.cookiesForRequest(request.uri)
                    .filter { it.name.equals("X-BILI-SEC-TOKEN", true) && it.transportScope == "browser" }
                    .filter { request.uri.host.equals(it.domain.trimStart('.'), true) }
                    .maxByOrNull { it.expiresAtEpochSeconds ?: Long.MAX_VALUE }
                    ?: return@singleFlight null
                val challenge = resolver.parse("X-BILI-SEC-TOKEN=${challengeCookie.value}; Path=${challengeCookie.path}")
                    ?: return@singleFlight null
                val solution = resolver.solve(challenge)
                log.info("Bilibili HTTP 412 PoW 已求解: host={}, result={}, elapsedMs={}",
                    request.uri.host, solution.result, solution.elapsedMillis)
                val passValue = submitCheck(page, challenge.token, solution.result) ?: return@singleFlight null
                val passChallenge = resolver.parse("X-BILI-SEC-TOKEN=$passValue; Path=/") ?: return@singleFlight null
                require(passChallenge.verity == 1) { "Bilibili 412 check returned an unverified pass token" }
                runtime.setCookie(StoredCookie(
                    name = "X-BILI-SEC-TOKEN", value = passValue, domain = request.uri.host,
                    path = "/", hostOnly = true, secure = true,
                    expiresAtEpochSeconds = passChallenge.expiresAt, transportScope = "browser", source = "http412-pass"
                ))
                navigation = navigate(page, request.uri.toString())
                runtime.synchronizeCookiesFromBrowser("http412-retry")
                navigation.toResponse(request.copy(identity = captureRequestIdentity(runtime, page,
                    request.uri.toString(), "http412-response")), started)
            } finally { runtime.closePage(page) }
        }
    }

    /**
     * 在 CfT 浏览器内执行一次 GET 并返回响应（Chrome TLS 指纹 + 浏览器 cookie 通道）。
     * 用于 Gaia -352 验证通过后的重试: token 绑定浏览器环境, 经 JVM 通道重发仍会被拒。
     * 浏览器不可用时返回 null, 调用方回落 JVM 通道。
     */
    @JvmOverloads
    /** 只读当前浏览器 jar 中对该 URL 生效的 cookie(名称→值); 不做任何持久化或身份改写。 */
    fun currentBrowserCookies(url: String): Map<String, String> = runCatching {
        runtime.cookiesForRequest(URI.create(url)).associate { it.name to it.value }
    }.onFailure { log.warn("读取浏览器 cookie 失败: {}", it.toString()) }.getOrDefault(emptyMap())

    fun executeGetInBrowser(url: String, sourceChallengeId: String = "browser-response"): BilibiliHttpResponse? = synchronized(requestPageLock) {
        runtime.enterResolverIdentity()
        if (!gate.ensurePassed()) return null
        return runCatching {
            val page = requestPage?.takeIf { it.connection.isOpen } ?: runtime.createPage().also {
                requestPage = it; requestOriginReady = false
            }
            try {
                if (URI.create(url).host == "api.live.bilibili.com") {
                    // Preserve the live-page Origin/Referer and browser request
                    // context, rather than navigating a new tab to an API URL.
                    val room = Regex("[?&]id=([0-9]+)").find(url)?.groupValues?.get(1)
                    if (!requestOriginReady) {
                        navigator.navigate(page, "https://live.bilibili.com/${room.orEmpty()}")
                        requestOriginReady = true
                    }
                    val observer = com.starlwr.bot.bilibili.browser.BrowserRequestObserver(page, room.orEmpty(), "starbot-browser-get.js")
                    try {
                        observer.enable()
                        val navigation = fetchInPage(page, url)
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                        var observed = observer.poll()
                        while (observed == null && System.nanoTime() < deadline) {
                            Thread.sleep(10); observed = observer.poll()
                        }
                        val captured = observed?.takeIf { it.url == url }?.identity
                            ?: error("Browser request omitted exact CDP identity")
                        val identity = com.starlwr.bot.bilibili.credential.RequestIdentitySnapshot(captured.headers,
                            captured.buvid3, captured.uid, sourceChallengeId)
                        navigation.toResponse(BilibiliHttpRequest("GET", URI.create(url), identity = identity), System.nanoTime())
                    } finally { observer.close() }
                } else {
                    val navigation = navigate(page, url)
                    requestOriginReady = false
                    navigation.toResponse(BilibiliHttpRequest("GET", URI.create(url),
                        identity = captureRequestIdentity(runtime, page, url, sourceChallengeId)), System.nanoTime())
                }
            } catch (error: Exception) {
                requestPage = null; requestOriginReady = false
                runCatching { runtime.closePage(page) }
                throw error
            }
        }.onFailure { log.warn("浏览器内执行 GET 失败: endpoint={}, reason={}", url.substringBefore('?'), it.javaClass.simpleName) }
            .getOrNull()
    }

    @jakarta.annotation.PreDestroy
    fun shutdown() = synchronized(requestPageLock) {
        requestPage?.let { runCatching { runtime.closePage(it) } }
        requestPage = null; requestOriginReady = false
    }

    internal fun fetchInPage(page: BrowserPage, url: String): BrowserNavigationResult {
        val raw = evaluate(page.connection, """
            (async () => {
              const response = await fetch(${JSON.toJSONString(url)}, {credentials: 'include'});
              return JSON.stringify({status: response.status,
                headers: Object.fromEntries(response.headers.entries()), body: await response.text()});
            })()
            //# sourceURL=starbot-browser-get.js
        """.trimIndent())
        val result = JSON.parseObject(raw)
        val headers = result.getJSONObject("headers")?.mapValues { listOf(it.value.toString()) }.orEmpty()
        return BrowserNavigationResult(result.getIntValue("status"), headers,
            result.getString("body").orEmpty().toByteArray(StandardCharsets.UTF_8))
    }

    private fun navigate(page: BrowserPage, url: String): BrowserNavigationResult {
        val document = navigator.navigate(page, url)
        return BrowserNavigationResult(document.status, document.responseHeaders, document.body)
    }

    private fun submitCheck(page: BrowserPage, token: String, result: Int): String? {
        val tokenJson = JSON.toJSONString(token)
        val script = """
            (async () => {
              const body = new URLSearchParams();
              body.set('token', $tokenJson);
              body.set('result', String($result));
              const r = await fetch('https://security.bilibili.com/th/captcha/cc/check', {
                method: 'POST', credentials: 'include',
                headers: {'content-type':'application/x-www-form-urlencoded'}, body
              });
              return JSON.stringify({status:r.status, text:await r.text()});
            })()
        """.trimIndent()
        val raw = evaluate(page.connection, script)
        val wrapper = JSON.parseObject(raw)
        if (wrapper.getIntValue("status") !in 200..299) return null
        val body = JSON.parseObject(wrapper.getString("text"))
        if (body.getIntValue("code", -1) != 0) {
            log.warn("Bilibili HTTP 412 check失败: code={}, message={}", body.getIntValue("code", -1), body.getString("message"))
            return null
        }
        return body.getString("message")
    }

    private fun evaluate(connection: com.starlwr.bot.bilibili.browser.CdpConnection, expression: String): String {
        val result = connection.call("Runtime.evaluate", mapOf(
            "expression" to expression, "returnByValue" to true, "awaitPromise" to true
        ), 30).getJSONObject("result") ?: error("Browser evaluate omitted result")
        return result.getString("value") ?: result["value"]?.toString().orEmpty()
    }

    private fun BrowserNavigationResult.toResponse(request: BilibiliHttpRequest, started: Long) = BilibiliHttpResponse(
        request.copy(transport = "browser"), status, headers, body, 1,
        (System.nanoTime() - started) / 1_000_000
    )
}
