package com.starlwr.bot.bilibili.report

import com.starlwr.bot.core.factory.StarBotCommonPainterFactory
import com.starlwr.bot.core.plugin.StarBotComponent
import java.awt.*
import java.awt.image.BufferedImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

@StarBotComponent
class LiveReportPainter(private val factory: StarBotCommonPainterFactory) {
    fun paint(snapshot: LiveReportSnapshot, config: LiveReportTargetConfig): String {
        val painter = factory.create(1000, 300, true).setRowSpace(18)
        painter.drawChapter("直播报告").drawTip("${snapshot.uname} (${snapshot.roomId})")
        config.logo?.let { path -> runCatching { javax.imageio.ImageIO.read(java.nio.file.Path.of(path).toFile()) }.getOrNull()?.let {
            painter.drawImage(resize(it, 160), Point(800, 20))
        } }
        if (config.section("time")) {
            val end = snapshot.endedAt ?: System.currentTimeMillis()
            painter.drawSection("直播时间")
            painter.drawText("${time(snapshot.startedAt)} ~ ${time(end)}  (${duration(end - snapshot.startedAt)})")
        }
        if (config.section("fans") || config.section("fans_medal")) {
            painter.drawSection("基础数据")
            if (config.section("fans")) drawChange(painter, "粉丝", snapshot.metadata["before_fans"], snapshot.metadata["after_fans"])
            if (config.section("fans_medal")) drawChange(painter, "粉丝团", snapshot.metadata["before_fans_medal"], snapshot.metadata["after_fans_medal"])
            drawChange(painter, "大航海", snapshot.metadata["before_guard"], snapshot.metadata["after_guard"])
        }
        painter.drawSection("直播数据")
        ReportMetric.entries.forEach { metric ->
            val key = metric.name.lowercase(); if (!config.section(key)) return@forEach
            val count = snapshot.counts[key] ?: 0; val value = snapshot.values[key] ?: 0.0
            val users = snapshot.users[key]?.size ?: 0
            when (metric) {
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
        if (config.chart("box_profit")) snapshot.buckets["box_profit"]?.takeIf { it.isNotEmpty() }?.let {
            painter.drawSection("盲盒盈亏曲线").drawImageWithBorder(lineChart(it.toSortedMap().runningTotals(), 900, 360))
        }
        ReportMetric.entries.forEach { metric -> drawRanking(painter, snapshot, metric, config.top(metric.name.lowercase())) }
        ReportMetric.entries.forEach { metric ->
            if (config.chart(metric.name.lowercase())) snapshot.buckets[metric.name.lowercase()]?.takeIf { it.isNotEmpty() }?.let {
                painter.drawSection("${title(metric)}互动曲线").drawImageWithBorder(lineChart(it, 900, 360))
            }
        }
        if (config.wordCloud && snapshot.danmuTexts.isNotEmpty()) {
            painter.drawSection("弹幕词云").drawImageWithBorder(wordCloud(snapshot.danmuTexts, config, 900, 420))
        }
        painter.drawCopyright(25)
        return painter.base64().orElseThrow { IllegalStateException("直播报告图片编码失败") }
    }

    fun text(snapshot: LiveReportSnapshot, config: LiveReportTargetConfig): String = buildString {
        appendLine("${snapshot.uname} 直播报告")
        ReportMetric.entries.filter { config.section(it.name.lowercase()) }.forEach {
            val key = it.name.lowercase(); appendLine("${title(it)}: ${snapshot.counts[key] ?: 0}，金额 ${fmt(snapshot.values[key] ?: 0.0)}")
        }
    }.trim()

    private fun drawRanking(p: com.starlwr.bot.core.painter.CommonPainter, s: LiveReportSnapshot, metric: ReportMetric, top: Int) {
        if (top <= 0) return
        val key = metric.name.lowercase(); val users = s.users[key]?.entries?.sortedByDescending {
            if (metric == ReportMetric.BOX) it.value.profit else if (metric == ReportMetric.DANMU) it.value.count.toDouble() else it.value.value
        }?.take(top).orEmpty()
        if (users.isEmpty()) return
        p.drawSection("${title(metric)}排行 (Top ${users.size})")
        users.forEachIndexed { index, entry ->
            val stat = entry.value; val score = when (metric) { ReportMetric.DANMU -> "${stat.count} 条"; ReportMetric.BOX -> "${fmt(stat.profit)} 元"; else -> "${fmt(stat.value)} 元" }
            p.drawText("${index + 1}. ${stat.uname.ifBlank { entry.key }}  $score")
        }
    }
    private fun drawChange(p: com.starlwr.bot.core.painter.CommonPainter, title: String, before: Long?, after: Long?) {
        if (before == null || after == null) return
        val diff = after - before; p.drawText("$title: $before → $after (${if (diff >= 0) "+" else ""}$diff)")
    }
    private fun Map<Long, Double>.runningTotals(): Map<Long, Double> {
        var total = 0.0
        return entries.associate { entry -> total += entry.value; entry.key to total }
    }

    private fun lineChart(data: Map<Long, Double>, width: Int, height: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB); val g = image.createGraphics()
        quality(g); g.color = Color.WHITE; g.fillRect(0, 0, width, height); g.color = Color(230,230,230)
        repeat(6) { val y = 25 + it * (height - 50) / 5; g.drawLine(50, y, width - 25, y) }
        val points = data.toSortedMap().values.toList(); val maxValue = max(1.0, points.maxOf { kotlin.math.abs(it) })
        g.color = Color(251, 114, 153); g.stroke = BasicStroke(3f)
        points.zipWithNext().forEachIndexed { i, (a,b) ->
            val x1 = 50 + i * (width - 75) / max(1, points.size - 1); val x2 = 50 + (i+1) * (width - 75) / max(1, points.size - 1)
            val y1 = height / 2 - (a / maxValue * (height / 2 - 30)).toInt(); val y2 = height / 2 - (b / maxValue * (height / 2 - 30)).toInt()
            g.drawLine(x1,y1,x2,y2)
        }; g.dispose(); return image
    }

    private fun wordCloud(texts: List<String>, config: LiveReportTargetConfig, width: Int, height: Int): BufferedImage {
        val counts = HashMap<String, Int>()
        val customWords = readLines(config.dictionary)
        val stopWords = STOP_WORDS + readLines(config.stopWords)
        texts.asSequence().flatMap { Regex("[\\p{IsHan}]{2,}|[A-Za-z0-9_]{2,}").findAll(it).map { m -> m.value.lowercase() } }
            .filterNot { it in stopWords }.forEach { counts.merge(it, 1, Int::plus) }
        customWords.forEach { word -> val n=texts.sumOf { text -> Regex(Regex.escape(word)).findAll(text).count() }; if(n>0) counts[word]=n }
        val words = counts.entries.sortedByDescending { it.value }.take(config.maxWords)
        val image = BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB); val g=image.createGraphics(); quality(g)
        g.color=Color.WHITE; g.fillRect(0,0,width,height); var x=20; var y=45
        val maxCount = words.firstOrNull()?.value?.coerceAtLeast(1) ?: 1
        words.forEachIndexed { i, e ->
            val size = 18 + e.value * 42 / maxCount; g.font=Font("SansSerif",Font.PLAIN,size); val w=g.fontMetrics.stringWidth(e.key)
            if (x+w>width-20) { x=20; y+=size+16 }; if (y<height-15) { g.color=COLORS[i%COLORS.size]; g.drawString(e.key,x,y); x+=w+18 }
        }; g.dispose(); return image
    }
    private fun quality(g: Graphics2D) { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON) }
    private fun resize(source:BufferedImage,width:Int):BufferedImage { val height=(source.height*(width.toDouble()/source.width)).toInt().coerceAtLeast(1); val out=BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB); val g=out.createGraphics(); quality(g); g.drawImage(source,0,0,width,height,null); g.dispose(); return out }
    private fun readLines(path:String?):Set<String> = path?.let { runCatching { java.nio.file.Files.readAllLines(java.nio.file.Path.of(it)).map(String::trim).filter(String::isNotBlank).toSet() }.getOrDefault(emptySet()) } ?: emptySet()
    private fun time(ms:Long)=FORMAT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    private fun duration(ms:Long)="${ms/3_600_000} 小时 ${(ms/60_000)%60} 分钟 ${(ms/1000)%60} 秒"
    private fun fmt(v:Double)=java.text.DecimalFormat("0.##").format(v)
    private fun title(m:ReportMetric)=mapOf(ReportMetric.DANMU to "弹幕",ReportMetric.BOX to "盲盒",ReportMetric.GIFT to "礼物",ReportMetric.SC to "SC",ReportMetric.GUARD to "大航海").getValue(m)
    companion object { val FORMAT:DateTimeFormatter=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"); val COLORS=listOf(Color(251,114,153),Color(0,161,214),Color(126,87,194),Color(76,175,80)); val STOP_WORDS=setOf("这个","那个","就是","然后","但是","可以","不是","一个") }
}
