package com.starlwr.bot.bilibili.report

import com.starlwr.bot.core.factory.StarBotCommonPainterFactory
import com.starlwr.bot.core.model.TextWithStyle
import com.starlwr.bot.core.painter.CommonPainter
import com.starlwr.bot.core.plugin.StarBotComponent
import java.awt.*
import java.awt.image.BufferedImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@StarBotComponent
class LiveReportPainter(private val factory: StarBotCommonPainterFactory) {
    fun paint(snapshot: LiveReportSnapshot, config: LiveReportTargetConfig): String {
        val painter = factory.create(1000, 300, true).setRowSpace(18).setPos(50, 50)
        painter.drawChapter("直播报告").drawTip("${snapshot.uname} (${snapshot.roomId})")
        config.logo?.let { path -> runCatching { javax.imageio.ImageIO.read(java.nio.file.Path.of(path).toFile()) }.getOrNull()?.let {
            painter.drawImage(resize(it, 160), Point(800, 20))
        } }
        if (config.section("time")) {
            val end = snapshot.endedAt ?: System.currentTimeMillis()
            val start = snapshot.reportStartedAt()
            if (snapshot.baselineType == ReportBaselineType.PARTIAL) {
                painter.drawSection("统计时间（中途接入）")
                painter.drawText("接入 ${time(start)} ~ 下播 ${time(end)}  (${duration(end - start)})")
                painter.drawTip("本报告仅统计 StarBot 接入后的互动数据")
            } else {
                painter.drawSection("直播时间")
                painter.drawText("${time(start)} ~ ${time(end)}  (${duration(end - start)})")
            }
        }
        val hasBaseData = snapshot.baselineType == ReportBaselineType.FULL && listOf("fans", "fans_medal", "guard").any {
            snapshot.metadata.containsKey("before_$it") && snapshot.metadata.containsKey("after_$it")
        }
        if (hasBaseData && (config.section("fans") || config.section("fans_medal") || config.section("guard"))) {
            painter.drawSection("基础数据")
            if (config.section("fans")) drawChange(painter, "粉丝", snapshot.metadata["before_fans"], snapshot.metadata["after_fans"])
            if (config.section("fans_medal")) drawChange(painter, "粉丝团", snapshot.metadata["before_fans_medal"], snapshot.metadata["after_fans_medal"])
            if (config.section("guard")) drawChange(painter, "大航海", snapshot.metadata["before_guard"], snapshot.metadata["after_guard"])
        }
        val visibleMetrics = ReportMetric.entries.filter { metric ->
            val key = metric.name.lowercase()
            config.section(key) && ((snapshot.counts[key] ?: 0L) != 0L || (snapshot.values[key] ?: 0.0) != 0.0)
        }
        if (visibleMetrics.isNotEmpty()) painter.drawSection("直播数据")
        visibleMetrics.forEach { metric ->
            val key = metric.name.lowercase()
            val count = snapshot.counts[key] ?: 0; val value = snapshot.values[key] ?: 0.0
            val users = snapshot.users[key]?.size ?: 0
            if (!config.amount(key) && metric != ReportMetric.DANMU) {
                painter.drawText(metricSummaryWithoutAmount(metric, users))
            } else when (metric) {
                ReportMetric.DANMU -> painter.drawText("弹幕: $count 条 ($users 人)")
                ReportMetric.BOX -> painter.drawText("盲盒: $count 个 ($users 人)，价值 ${fmt(value)} 元，盈亏 ${fmt(snapshot.profits[key] ?: 0.0)} 元")
                ReportMetric.GIFT -> painter.drawText("礼物: ${fmt(value)} 元 ($users 人)")
                ReportMetric.SC -> painter.drawText("SC: ${fmt(value)} 元 ($users 人)")
                ReportMetric.GUARD -> painter.drawText("大航海: $count 个，${fmt(value)} 元 ($users 人)")
            }
        }
        if (config.section("guard")) snapshot.labels["guard"]?.takeIf { it.isNotEmpty() }?.let { levels ->
            painter.drawTip("舰长 ${levels["captain"] ?: 0} / 提督 ${levels["commander"] ?: 0} / 总督 ${levels["governor"] ?: 0}")
        }
        ReportMetric.entries.forEach { metric -> drawRanking(painter, snapshot, metric, config.top(metric.name.lowercase()), config) }
        val end = snapshot.endedAt ?: System.currentTimeMillis()
        if (config.chart("box_profit")) snapshot.buckets["box_profit"]?.takeIf { it.isNotEmpty() }?.let {
            painter.drawSection("盲盒盈亏折线图")
                .drawImage(V2InteractionChartRenderer.render(it, snapshot.reportStartedAt(), end, cumulative = true,
                    showNumericAxis = config.amount("box")))
        }
        ReportMetric.entries.forEach { metric ->
            if (config.chart(metric.name.lowercase())) snapshot.buckets[metric.name.lowercase()]?.takeIf { it.isNotEmpty() }?.let {
                painter.drawSection("${title(metric)}互动曲线图")
                painter.drawTip(interactionTip(metric))
                painter.drawImage(V2InteractionChartRenderer.render(it, snapshot.reportStartedAt(), end,
                    showNumericAxis = config.amount(metric.name.lowercase())))
            }
        }
        if (config.wordCloud && snapshot.danmuTexts.isNotEmpty()) {
            painter.drawSection("弹幕词云")
            painter.drawImage(V2WordCloudRenderer.render(snapshot.danmuTexts, config))
        }
        val pluginVersion = javaClass.`package`.implementationVersion ?: "dev"
        val pluginCopyright = TextWithStyle(
            "Designed by starbot-bilibili-plugin v$pluginVersion",
            CommonPainter.TEXT_FONT_SIZE,
            Color(251, 114, 153)
        )
        painter.drawCopyright(listOf(listOf(pluginCopyright)), emptyList(), 50)
        // CommonPainter grows in large chunks. Finalizing the background both
        // makes dark text visible and crops the canvas to the actual draw cursor.
        painter.createSolidRoundedRectangleBackground(Color.WHITE, 35)
        return painter.base64().orElseThrow { IllegalStateException("直播报告图片编码失败") }
    }

    fun text(snapshot: LiveReportSnapshot, config: LiveReportTargetConfig): String = if (LiveReportTargetConfig.DEFAULT_AMOUNTS.keys.all { config.amount(it) }) buildString {
        appendLine("${snapshot.uname} 直播报告")
        if (snapshot.baselineType == ReportBaselineType.PARTIAL) appendLine("统计区间: 中途接入至下播")
        ReportMetric.entries.filter { config.section(it.name.lowercase()) }.forEach {
            val key = it.name.lowercase(); appendLine("${title(it)}: ${snapshot.counts[key] ?: 0}，金额 ${fmt(snapshot.values[key] ?: 0.0)}")
        }
    }.trim() else textWithoutAmounts(snapshot, config)

    private fun metricSummaryWithoutAmount(metric: ReportMetric, users: Int): String =
        "${title(metric)}: ${users}人"

    private fun textWithoutAmounts(snapshot: LiveReportSnapshot, config: LiveReportTargetConfig): String = buildString {
        appendLine("${snapshot.uname} 直播报告")
        if (snapshot.baselineType == ReportBaselineType.PARTIAL) appendLine("统计区间: 中途接入至下播")
        ReportMetric.entries.filter { config.section(it.name.lowercase()) }.forEach { metric ->
            val key = metric.name.lowercase()
            val count = snapshot.counts[key] ?: 0
            val users = snapshot.users[key]?.size ?: 0
            if (metric == ReportMetric.DANMU) {
                appendLine("${title(metric)}: $count ($users)")
            } else if (config.amount(key)) {
                appendLine("${title(metric)}: ${fmt(snapshot.values[key] ?: 0.0)} ($users)")
            } else {
                appendLine(metricSummaryWithoutAmount(metric, users))
            }
        }
    }.trim()

    private fun drawRanking(p: com.starlwr.bot.core.painter.CommonPainter, s: LiveReportSnapshot, metric: ReportMetric, top: Int,
                            config: LiveReportTargetConfig) {
        if (top <= 0) return
        val key = metric.name.lowercase(); val users = s.users[key]?.entries?.sortedByDescending {
            if (metric == ReportMetric.BOX) it.value.profit else if (metric == ReportMetric.DANMU) it.value.count.toDouble() else it.value.value
        }?.take(top).orEmpty()
        if (users.isEmpty()) return
        p.drawSection("${title(metric)}排行 (Top ${users.size})")
        users.forEachIndexed { index, entry ->
            val stat = entry.value; val score = when (metric) { ReportMetric.DANMU -> "${stat.count} 条"; ReportMetric.BOX -> "${fmt(stat.profit)} 元"; else -> "${fmt(stat.value)} 元" }
            if (metric != ReportMetric.DANMU && !config.amount(metric.name.lowercase())) {
                p.drawText("${index + 1}. ${stat.uname.ifBlank { entry.key }}")
            } else {
                p.drawText("${index + 1}. ${stat.uname.ifBlank { entry.key }}  $score")
            }
        }
    }
    private fun drawChange(p: com.starlwr.bot.core.painter.CommonPainter, title: String, before: Long?, after: Long?) {
        if (before == null || after == null) return
        val diff = after - before; p.drawText("$title: $before → $after (${if (diff >= 0) "+" else ""}$diff)")
    }
    private fun quality(g: Graphics2D) { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON) }
    private fun resize(source:BufferedImage,width:Int):BufferedImage { val height=(source.height*(width.toDouble()/source.width)).toInt().coerceAtLeast(1); val out=BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB); val g=out.createGraphics(); quality(g); g.drawImage(source,0,0,width,height,null); g.dispose(); return out }
    private fun time(ms:Long)=FORMAT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    private fun duration(ms:Long):String = buildString {
        val seconds = ms.coerceAtLeast(0) / 1_000
        val hours = seconds / 3_600; val minutes = seconds % 3_600 / 60; val remaining = seconds % 60
        if (hours > 0) append("$hours 小时 ")
        if (minutes > 0) append("$minutes 分钟 ")
        if (remaining > 0 || isEmpty()) append("$remaining 秒")
    }.trim()
    private fun fmt(v:Double)=java.text.DecimalFormat("0.##").format(v)
    private fun title(m:ReportMetric)=mapOf(ReportMetric.DANMU to "弹幕",ReportMetric.BOX to "盲盒",ReportMetric.GIFT to "礼物",ReportMetric.SC to "SC",ReportMetric.GUARD to "大航海").getValue(m)
    private fun interactionTip(metric: ReportMetric) = when (metric) {
        ReportMetric.DANMU -> "收获弹幕数量在本场直播中的分布情况"
        ReportMetric.BOX -> "收获盲盒数量在本场直播中的分布情况"
        ReportMetric.GIFT -> "收获礼物价值在本场直播中的分布情况"
        ReportMetric.SC -> "收获 SC（醒目留言）价值在本场直播中的分布情况"
        ReportMetric.GUARD -> "收获大航海开通数量在本场直播中的分布情况"
    }
    companion object { val FORMAT:DateTimeFormatter=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss") }
}
