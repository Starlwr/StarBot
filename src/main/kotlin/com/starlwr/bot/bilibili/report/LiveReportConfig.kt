package com.starlwr.bot.bilibili.report

import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.bilibili.model.BilibiliLiveReportConfig

data class LiveReportTargetConfig(
    val enabled: Boolean = true,
    /** Renderer for this target: legacy or upstream. Null/blank falls back to application config. */
    val painter: String? = null,
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

    fun usesUpstreamPainter(defaultPainter: String) =
        (painter?.takeIf { it.isNotBlank() } ?: defaultPainter).equals("upstream", true)

    /** Convert the modern session configuration to the merged upstream renderer's module model. */
    fun toUpstreamConfig(): BilibiliLiveReportConfig = BilibiliLiveReportConfig().apply {
        setEnableBasicInfo(true)
        setShowLiveArea(true)
        setShowLiveTitle(true)
        setShowLiveTime(section("time"))
        setEnableChangeInfo(section("fans") || section("fans_medal") || section("guard"))
        setShowFansChange(section("fans"))
        setShowFansMedalChange(section("fans_medal"))
        setShowGuardChange(section("guard"))
        setEnableDanmuAnalysis(section("danmu"))
        setShowDanmuDetails(section("danmu"))
        setDanmuRankingLimit(top("danmu"))
        setShowDanmuGrowthChart(chart("danmu"))
        setShowDanmuInteractionChart(chart("danmu"))
        setShowDanmuTypeDistributionChart(chart("danmu_type"))
        setShowDanmuSenderDistributionChart(chart("danmu_sender"))
        setShowDanmuWordCloud(wordCloud)
        setEnableBoxAnalysis(section("box"))
        setShowBoxDetails(section("box"))
        setShowBoxProfitDetails(section("box") && amount("box"))
        setBoxRankingLimit(top("box"))
        setBoxProfitRankingLimit(top("box_profit"))
        setShowBoxGrowthChart(chart("box"))
        setShowBoxInteractionChart(chart("box"))
        setShowBoxProfitGrowthChart(chart("box_profit"))
        setShowBoxProfitInteractionChart(chart("box_profit"))
        setShowBoxProfitDistributionChart(chart("box_profit_distribution"))
        setShowBoxGiftDistributionChart(chart("box_gift_distribution"))
        setEnableGiftAnalysis(section("gift"))
        setShowGiftDetails(section("gift") && amount("gift"))
        setGiftRankingLimit(top("gift"))
        setShowGiftGrowthChart(chart("gift"))
        setShowGiftInteractionChart(chart("gift"))
        setShowGiftTypeDistributionChart(chart("gift_type"))
        setEnableSuperChatAnalysis(section("sc"))
        setShowSuperChatDetails(section("sc") && amount("sc"))
        setSuperChatRankingLimit(top("sc"))
        setShowSuperChatGrowthChart(chart("sc"))
        setShowSuperChatInteractionChart(chart("sc"))
        setEnableGuardAnalysis(section("guard"))
        setShowGuardDetails(section("guard") && amount("guard"))
        setShowGuardList(section("guard_list"))
        setEnableEnterRoomAnalysis(section("enter_room"))
        setShowEnterRoomDetails(section("enter_room"))
        setShowEnterRoomGrowthChart(chart("enter_room"))
        setShowEnterRoomInteractionChart(chart("enter_room"))
        setEnableLikeAnalysis(section("like"))
        setShowLikeDetails(section("like"))
        setLikeRankingLimit(top("like"))
        setShowLikeGrowthChart(chart("like"))
        setShowLikeInteractionChart(chart("like"))
        setEnableShareAnalysis(section("share"))
        setShowShareDetails(section("share"))
        setShowShareGrowthChart(chart("share"))
        setShowShareInteractionChart(chart("share"))
        setSequence(getSequence().filter { it != "basicInfo" })
    }
    companion object {
        val DEFAULT_SECTIONS = mapOf("time" to true, "danmu" to true, "box" to true,
            "gift" to true, "sc" to true, "guard" to true, "fans" to false, "fans_medal" to false,
            "guard_list" to false, "enter_room" to false, "like" to false, "share" to false)
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
                    "guard_list" -> module("showGuardList") ?: default
                    "enter_room" -> module("enableEnterRoomAnalysis") ?: default
                    "like" -> module("enableLikeAnalysis") ?: default
                    "share" -> module("enableShareAnalysis") ?: default
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
            val rankingKeys = ReportMetric.entries.map { it.name.lowercase() } + "like"
            val rankings = rankingKeys.associateWith { key ->
                val node = rankingsJson?.getJSONObject(key)
                val moduleLimit = modulesJson?.getIntValue("${key}RankingLimit")
                if (node?.getBooleanValue("enabled") == true) node.getIntValue("top", 3).coerceIn(1, 20)
                else moduleLimit?.coerceIn(0, 20) ?: 0
            }
            val chartsJson = params.getJSONObject("charts")
            val charts = (ReportMetric.entries.map { it.name.lowercase() } +
                listOf("box_profit", "danmu_type", "danmu_sender", "gift_type", "box_profit_distribution", "box_gift_distribution",
                    "enter_room", "like", "share"))
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
                        "enter_room" -> module("showEnterRoomGrowthChart") == true || module("showEnterRoomInteractionChart") == true
                        "like" -> module("showLikeGrowthChart") == true || module("showLikeInteractionChart") == true
                        "share" -> module("showShareGrowthChart") == true || module("showShareInteractionChart") == true
                        else -> null
                    }
                    key to (current ?: upstream ?: false)
                }
            val cloud = params.getJSONObject("word_cloud")
            val upstreamCloud = module("showDanmuWordCloud") == true
            return LiveReportTargetConfig(
                enabled = params.getBooleanValue("enabled", true), painter = params.getString("painter"),
                output = params.getString("output") ?: "image",
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
