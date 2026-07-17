package com.starlwr.bot.bilibili.risk

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.bilibili.browser.BilibiliBrowserRuntime
import com.starlwr.bot.bilibili.browser.BrowserIdentityProbe
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

    fun resolve412(request: BilibiliHttpRequest): BilibiliHttpResponse? {
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
                if (navigation.status != 412) return@singleFlight navigation.toResponse(request, started)
                runtime.synchronizeCookiesFromBrowser("http412-challenge")
                val challengeCookie = credentialStore.snapshot()?.cookies
                    ?.filter { it.name.equals("X-BILI-SEC-TOKEN", true) && it.transportScope == "browser" }
                    ?.filter { request.uri.host.equals(it.domain.trimStart('.'), true) }
                    ?.maxByOrNull { it.expiresAtEpochSeconds ?: Long.MAX_VALUE }
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
                navigation.toResponse(request, started)
            } finally { runtime.closePage(page) }
        }
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
