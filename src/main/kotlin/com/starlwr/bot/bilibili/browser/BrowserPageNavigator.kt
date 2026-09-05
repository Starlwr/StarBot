package com.starlwr.bot.bilibili.browser

import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.core.plugin.StarBotComponent
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class BrowserDocument(
    val status: Int,
    val responseHeaders: Map<String, List<String>>,
    val requestHeaders: Map<String, String>,
    val body: ByteArray,
    val finalUrl: String,
    val contentType: String,
)

/** Captures the main CDP Document request and its raw response without mixing subresource events. */
@StarBotComponent
class BrowserPageNavigator {
    fun navigate(page: BrowserPage, url: String, timeoutSeconds: Long = 30): BrowserDocument {
        val connection = page.connection
        connection.call("Network.enable")
        connection.call("Page.enable")
        val mainRequestId = AtomicReference<String>()
        val finished = CompletableFuture<Unit>()
        val responseHeaders = linkedMapOf<String, MutableList<String>>()
        val requestHeaders = linkedMapOf<String, String>()
        var status = 0
        var finalUrl = url
        var contentType = ""
        val registration = connection.onEvent { event ->
            val params = event.getJSONObject("params") ?: return@onEvent
            val requestId = params.getString("requestId")
            when (event.getString("method")) {
                "Network.requestWillBeSent" -> {
                    val request = params.getJSONObject("request") ?: return@onEvent
                    if (params.getString("type") == "Document" &&
                        (mainRequestId.get() == null || requestId == mainRequestId.get())) {
                        mainRequestId.set(requestId)
                        finalUrl = request.getString("url") ?: finalUrl
                        copyHeaders(request.getJSONObject("headers"), requestHeaders)
                    }
                }
                "Network.requestWillBeSentExtraInfo" -> if (requestId == mainRequestId.get()) {
                    copyHeaders(params.getJSONObject("headers"), requestHeaders)
                }
                "Network.responseReceived" -> if (requestId == mainRequestId.get()) {
                    val response = params.getJSONObject("response") ?: return@onEvent
                    status = response.getIntValue("status")
                    finalUrl = response.getString("url") ?: finalUrl
                    contentType = response.getString("mimeType").orEmpty()
                    response.getJSONObject("headers")?.forEach { (key, value) ->
                        responseHeaders.computeIfAbsent(key) { mutableListOf() }.add(value.toString())
                    }
                }
                "Network.loadingFinished" -> if (requestId == mainRequestId.get()) finished.complete(Unit)
                "Network.loadingFailed" -> if (requestId == mainRequestId.get()) {
                    finished.completeExceptionally(IllegalStateException(
                        "Browser navigation failed: ${params.getString("errorText").orEmpty()}"
                    ))
                }
            }
        }
        try {
            connection.call("Page.navigate", mapOf("url" to url), timeoutSeconds)
            finished.get(timeoutSeconds, TimeUnit.SECONDS)
            val requestId = mainRequestId.get() ?: error("Browser navigation omitted main request id")
            val raw = connection.call("Network.getResponseBody", mapOf("requestId" to requestId), timeoutSeconds)
            val body = raw.getString("body").orEmpty()
            val bytes = if (raw.getBooleanValue("base64Encoded")) Base64.getDecoder().decode(body)
                else body.toByteArray(StandardCharsets.UTF_8)
            return BrowserDocument(status, responseHeaders, requestHeaders, bytes, finalUrl, contentType)
        } finally {
            registration.close()
        }
    }

    private fun copyHeaders(source: JSONObject?, target: MutableMap<String, String>) {
        source?.forEach { (key, value) -> target[key] = value.toString() }
    }
}
