package com.starlwr.bot.bilibili.report

import com.alibaba.fastjson2.JSONObject

data class LiveReportTargetConfig(
    val enabled: Boolean = true,
    val output: String = "image",
    val textFallback: Boolean = true,
    val onlyWhenNonEmpty: Boolean = false,
    val atAll: Boolean = false,
    val sections: Map<String, Boolean> = DEFAULT_SECTIONS,
    val amounts: Map<String, Boolean> = DEFAULT_AMOUNTS,
    val rankings: Map<String, Int> = emptyMap(),
    val charts: Map<String, Boolean> = emptyMap(),
    val wordCloud: Boolean = false,
    val maxWords: Int = 80,
    val maxFontSize: Int = 200,
    val dictionary: String? = null,
    val stopWords: String? = null,
    val logo: String? = null,
    val saveImage: Boolean = false,
    val saveDirectory: String = "report"
) {
    fun section(name: String) = sections[name] == true
    /** Whether monetary values for a metric may be shown in a rendered report. */
    fun amount(name: String) = amounts[name] != false
    fun top(name: String) = rankings[name]?.coerceIn(0, 20) ?: 0
    fun chart(name: String) = charts[name] == true
    companion object {
        val DEFAULT_SECTIONS = mapOf("time" to true, "danmu" to true, "box" to true,
            "gift" to true, "sc" to true, "guard" to true, "fans" to false, "fans_medal" to false)
        val DEFAULT_AMOUNTS = mapOf("box" to true, "gift" to true, "sc" to true, "guard" to true)
        fun from(params: JSONObject?): LiveReportTargetConfig {
            if (params == null) return LiveReportTargetConfig()
            val sectionsJson = params.getJSONObject("sections")
            val modulesJson = params.getJSONObject("modules")
            fun module(name: String): Boolean? = modulesJson?.getBooleanValue(name)
            val sections = DEFAULT_SECTIONS.mapValues { (key, default) ->
                if (sectionsJson?.containsKey(key) == true) sectionsJson.getBooleanValue(key) else when (key) {
                    "fans", "fans_medal", "guard" -> module("enableChangeInfo") ?: default
                    "danmu" -> module("enableDanmuAnalysis") ?: default
                    "box" -> module("enableBoxAnalysis") ?: default
                    "gift" -> module("enableGiftAnalysis") ?: default
                    "sc" -> module("enableSuperChatAnalysis") ?: default
                    else -> default
                }
            }
            val rankingsJson = params.getJSONObject("rankings")
            val amountsJson = params.getJSONObject("amounts")
            val amounts = DEFAULT_AMOUNTS.mapValues { (key, default) ->
                if (amountsJson?.containsKey(key) == true) amountsJson.getBooleanValue(key) else when (key) {
                    "box" -> module("showBoxProfitDetails") ?: default
                    "gift" -> module("showGiftDetails") ?: default
                    "sc" -> module("showSuperChatDetails") ?: default
                    "guard" -> module("showGuardDetails") ?: default
                    else -> default
                }
            }
            val rankings = ReportMetric.entries.associate { metric ->
                val key = metric.name.lowercase(); val node = rankingsJson?.getJSONObject(key)
                val moduleLimit = modulesJson?.getIntValue("${key}RankingLimit")
                key to if (node?.getBooleanValue("enabled") == true) node.getIntValue("top", 3).coerceIn(1, 20)
                else moduleLimit?.coerceIn(0, 20) ?: 0
            }
            val chartsJson = params.getJSONObject("charts")
            val charts = (ReportMetric.entries.map { it.name.lowercase() } +
                listOf("box_profit", "danmu_type", "danmu_sender", "gift_type", "box_profit_distribution", "box_gift_distribution"))
                .associate { key ->
                    val current = chartsJson?.getJSONObject(key)?.getBooleanValue("enabled")
                    val upstream = when (key) {
                        "danmu" -> module("showDanmuGrowthChart") == true || module("showDanmuInteractionChart") == true
                        "danmu_type" -> module("showDanmuTypeDistributionChart")
                        "danmu_sender" -> module("showDanmuSenderDistributionChart")
                        "box" -> module("showBoxGrowthChart") == true || module("showBoxInteractionChart") == true
                        "box_profit" -> module("showBoxProfitGrowthChart") == true || module("showBoxProfitInteractionChart") == true
                        "box_profit_distribution" -> module("showBoxProfitDistributionChart")
                        "box_gift_distribution" -> module("showBoxGiftDistributionChart")
                        "gift" -> module("showGiftGrowthChart") == true || module("showGiftInteractionChart") == true
                        "gift_type" -> module("showGiftTypeDistributionChart")
                        "sc" -> module("showSuperChatGrowthChart") == true || module("showSuperChatInteractionChart") == true
                        else -> null
                    }
                    key to (current ?: upstream ?: false)
                }
            val cloud = params.getJSONObject("word_cloud")
            val upstreamCloud = module("showDanmuWordCloud") == true
            return LiveReportTargetConfig(
                enabled = params.getBooleanValue("enabled", true), output = params.getString("output") ?: "image",
                textFallback = params.getBooleanValue("text_fallback", true),
                onlyWhenNonEmpty = params.getBooleanValue("only_when_non_empty", false),
                atAll = params.getBooleanValue("at_all", false), sections = sections, amounts = amounts,
                rankings = rankings, charts = charts,
                wordCloud = cloud?.getBooleanValue("enabled") ?: upstreamCloud,
                maxWords = cloud?.getIntValue("max_words", 80)?.coerceIn(10, 300)
                    ?: modulesJson?.getIntValue("wordCloudLimit")?.coerceIn(10, 300) ?: 80,
                maxFontSize = cloud?.getIntValue("max_font_size", 200)?.coerceIn(36, 240) ?: 200,
                dictionary = cloud?.getString("dictionary"), stopWords = cloud?.getString("stop_words"),
                logo = params.getString("logo"), saveImage = params.getBooleanValue("save_image", false),
                saveDirectory = params.getString("save_directory") ?: "report")
        }
    }
}
