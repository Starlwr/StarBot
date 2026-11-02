package com.starlwr.bot.bilibili.browser

import com.starlwr.bot.bilibili.credential.RequestIdentitySnapshot
import java.net.URI

/** Read-only, run-only response provenance. This does not assert that a CAPTCHA was solved. */
internal fun captureRequestIdentity(runtime: BilibiliBrowserRuntime, page: BrowserPage,
                                    url: String, sourceChallengeId: String): RequestIdentitySnapshot? {
    val values = linkedMapOf<String, String>()
    runtime.cookiesForRequest(URI.create(url)).forEach { values.putIfAbsent(it.name, it.value) }
    val buvid = values["buvid3"]?.takeIf { it.isNotBlank() } ?: return null
    val result = page.connection.call("Runtime.evaluate", mapOf(
        "expression" to "navigator.userAgent", "returnByValue" to true))
    val ua = result.getJSONObject("result")?.getString("value")?.takeIf { it.isNotBlank() } ?: return null
    return RequestIdentitySnapshot(mapOf("User-Agent" to ua, "Referer" to "https://live.bilibili.com/",
        "Cookie" to values.entries.joinToString("; ") { "${it.key}=${it.value}" }),
        buvid, values["DedeUserID"]?.takeIf { it.isNotBlank() } ?: "0", sourceChallengeId)
}
