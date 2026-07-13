package com.starlwr.bot.bilibili.report

import com.alibaba.fastjson2.JSONObject

data class LiveReportTargetConfig(
    val enabled: Boolean = true,
    val output: String = "image",
    val textFallback: Boolean = true,
    val onlyWhenNonEmpty: Boolean = false,
    val atAll: Boolean = false,
    val sections: Map<String, Boolean> = DEFAULT_SECTIONS,
    val rankings: Map<String, Int> = emptyMap(),
    val charts: Map<String, Boolean> = emptyMap(),
    val wordCloud: Boolean = false,
    val maxWords: Int = 80,
    val dictionary: String? = null,
    val stopWords: String? = null,
    val logo: String? = null,
    val saveImage: Boolean = false,
    val saveDirectory: String = "report"
) {
    fun section(name: String) = sections[name] == true
    fun top(name: String) = rankings[name]?.coerceIn(0, 20) ?: 0
    fun chart(name: String) = charts[name] == true
    companion object {
        val DEFAULT_SECTIONS = mapOf("time" to true, "danmu" to true, "box" to true,
            "gift" to true, "sc" to true, "guard" to true, "fans" to false, "fans_medal" to false)
        fun from(params: JSONObject?): LiveReportTargetConfig {
            if (params == null) return LiveReportTargetConfig()
            val sectionsJson = params.getJSONObject("sections")
            val sections = DEFAULT_SECTIONS.mapValues { (key, default) ->
                if (sectionsJson?.containsKey(key) == true) sectionsJson.getBooleanValue(key) else default
            }
            val rankingsJson = params.getJSONObject("rankings")
            val rankings = ReportMetric.entries.associate { metric ->
                val key = metric.name.lowercase(); val node = rankingsJson?.getJSONObject(key)
                key to if (node?.getBooleanValue("enabled") == true) node.getIntValue("top", 3).coerceIn(1, 20) else 0
            }
            val chartsJson = params.getJSONObject("charts")
            val charts = (ReportMetric.entries.map { it.name.lowercase() } + "box_profit").associate { key -> key to
                (chartsJson?.getJSONObject(key)?.getBooleanValue("enabled") == true) }
            val cloud = params.getJSONObject("word_cloud")
            return LiveReportTargetConfig(
                enabled = params.getBooleanValue("enabled", true), output = params.getString("output") ?: "image",
                textFallback = params.getBooleanValue("text_fallback", true),
                onlyWhenNonEmpty = params.getBooleanValue("only_when_non_empty", false),
                atAll = params.getBooleanValue("at_all", false), sections = sections, rankings = rankings, charts = charts,
                wordCloud = cloud?.getBooleanValue("enabled") == true,
                maxWords = cloud?.getIntValue("max_words", 80)?.coerceIn(10, 300) ?: 80,
                dictionary = cloud?.getString("dictionary"), stopWords = cloud?.getString("stop_words"),
                logo = params.getString("logo"), saveImage = params.getBooleanValue("save_image", false),
                saveDirectory = params.getString("save_directory") ?: "report")
        }
    }
}
