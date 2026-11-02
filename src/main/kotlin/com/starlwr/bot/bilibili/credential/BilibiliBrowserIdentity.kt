package com.starlwr.bot.bilibili.credential

import com.starlwr.bot.core.plugin.StarBotComponent
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/** Keeps the declared Chrome UA and Chrome client-hint integrity headers consistent. */
@StarBotComponent
class BilibiliBrowserIdentity(private val properties: BilibiliCredentialProperties) {
    fun headers(fallbackUserAgent: String? = null): Map<String, String> {
        val type = properties.uaType.trim()
        if (type.equals("generic", true)) {
            return mapOf("User-Agent" to (fallbackUserAgent?.takeIf { it.isNotBlank() } ?: properties.userAgent))
        }
        val match = CHROME_TYPE.matchEntire(type)
            ?: error("Unsupported starbot.bilibili.account.ua-type '$type'; use Generic or Chrome/<year>")
        val year = match.groupValues[1].toInt()
        val userAgent = properties.chromeUserAgent?.takeIf { it.isNotBlank() }
            ?: when (year) {
                2026 -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
                else -> error("No built-in Chrome UA for year $year; configure chrome-user-agent explicitly")
            }
        val apiKey = properties.browserValidationApiKey?.takeIf { it.isNotBlank() } ?: platformApiKey(userAgent)
        return linkedMapOf(
            "User-Agent" to userAgent,
            "X-Browser-Channel" to "stable",
            "X-Browser-Copyright" to "Copyright $year Google LLC. All rights reserved.",
            "X-Browser-Validation" to validationHeader(userAgent, apiKey),
            "X-Browser-Year" to year.toString()
        )
    }

    internal fun validationHeader(userAgent: String, apiKey: String): String = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-1").digest((apiKey + userAgent).toByteArray(Charsets.UTF_8))
    )

    private fun platformApiKey(userAgent: String): String {
        val ua = userAgent.lowercase(Locale.ROOT)
        return when {
            "windows" in ua -> WINDOWS_API_KEY
            "linux" in ua -> LINUX_API_KEY
            "macintosh" in ua || "mac os x" in ua -> MACOS_API_KEY
            else -> error("Cannot infer X-Browser-Validation platform from UA; configure browser-validation-api-key")
        }
    }

    companion object {
        private val CHROME_TYPE = Regex("chrome/(\\d{4})", RegexOption.IGNORE_CASE)
        private const val WINDOWS_API_KEY = "AIzaSyA2KlwBX3mkFo30om9LUFYQhpqLoa_BNhE"
        private const val LINUX_API_KEY = "AIzaSyBqJZh-7pA44blAaAkH6490hUFOwX0KCYM"
        private const val MACOS_API_KEY = "AIzaSyDr2UxVnv_U85AbhhY8XSHSIavUW0DC-sY"
    }
}
