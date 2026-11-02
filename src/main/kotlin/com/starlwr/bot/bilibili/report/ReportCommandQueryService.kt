package com.starlwr.bot.bilibili.report

import com.starlwr.bot.core.message.PushTargetType
import com.starlwr.bot.core.push.datasource.AbstractDataSource
import com.starlwr.bot.core.plugin.StarBotComponent
import java.time.LocalDate

/**
 * 直播报告/盲盒统计查询服务：供 OneBot HTTP 快速回复端点与 Core 命令（StarBotCommand）共用。
 */
@StarBotComponent
class ReportCommandQueryService(private val dataSource: AbstractDataSource, private val driver: LiveReportDataDriver,
                                private val painter: LiveReportPainter) {

    /** 解析会话关联的主播 UID 列表（按推送目标匹配）。 */
    fun resolveUids(platform: String, number: String, type: PushTargetType): List<String> =
        dataSource.allUsers.asSequence().flatMap { user ->
            (user.targets ?: emptyList()).asSequence().filter { it.platform == platform && it.num == number && it.type == type }
                .map { user.uid }
        }.distinct().toList()

    /** 直播报告文本查询，返回可直接回复的文本。 */
    fun liveReportText(platform: String, number: String, type: PushTargetType): String {
        val uids = resolveUids(platform, number, type)
        if (uids.isEmpty()) return "当前会话没有关联直播间，无法查询直播报告"
        val reports = uids.mapNotNull { driver.recent(it, 1).firstOrNull() }
        if (reports.isEmpty()) return "暂无已完成的直播报告"
        return reports.joinToString("\n\n") { painter.text(it, LiveReportTargetConfig(output = "text")) }
    }

    sealed class BlindBoxResult {
        data class Help(val text: String) : BlindBoxResult()
        data class Stats(val text: String) : BlindBoxResult()
    }

    /** 盲盒统计文本查询；参数为空/help/帮助时返回用法说明。 */
    fun blindBoxStats(platform: String, number: String, type: PushTargetType, argumentText: String): BlindBoxResult {
        val range = parseRange(argumentText.trim()) ?: return BlindBoxResult.Help(HELP)
        val uids = resolveUids(platform, number, type)
        if (uids.isEmpty()) return BlindBoxResult.Stats("当前会话没有关联直播间，无法查询盲盒统计")
        val snapshots = uids.flatMap { driver.recent(it, 100) }.filter {
            val date = java.time.Instant.ofEpochMilli(it.startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            date in range.start..range.end
        }
        val boxes = snapshots.sumOf { it.counts["box"] ?: 0 }
        if (boxes == 0L) return BlindBoxResult.Stats("盲盒统计 ${range.label}\n暂无记录")
        // 金额经 BigDecimal 累加与展示，避免 Double 聚合误差（对齐 Java painter 精度行为）
        val cost = snapshots.fold(java.math.BigDecimal.ZERO) { acc, s ->
            acc.add(java.math.BigDecimal.valueOf(s.values["box"] ?: 0.0)).subtract(java.math.BigDecimal.valueOf(s.profits["box"] ?: 0.0))
        }
        val value = snapshots.fold(java.math.BigDecimal.ZERO) { acc, s -> acc.add(java.math.BigDecimal.valueOf(s.values["box"] ?: 0.0)) }
        val profit = snapshots.fold(java.math.BigDecimal.ZERO) { acc, s -> acc.add(java.math.BigDecimal.valueOf(s.profits["box"] ?: 0.0)) }
        val participants = snapshots.flatMap { it.users["box"].orEmpty().keys }.toSet().size
        val gifts = snapshots.flatMap { it.labels["box"].orEmpty().entries }.groupingBy { it.key }.fold(0L) { acc, entry -> acc + entry.value }
            .entries.sortedByDescending { it.value }.take(10).joinToString(", ") { "${it.key} x${it.value}" }.ifEmpty { "无" }
        return BlindBoxResult.Stats("盲盒统计 ${range.label}\n盲盒次数: $boxes\n盲盒成本: ${format(cost)}" +
            "\n开出价值: ${format(value)}\n盈亏: ${format(profit)}" +
            "\n参与人数: $participants\nTOP礼物: $gifts")
    }

    private fun parseRange(body: String): Range? {
        val today = LocalDate.now()
        if (body.isBlank() || body.equals("help", true) || body == "帮助") return null
        if (body in setOf("一周", "周", "7天")) return Range(today.minusDays(6), today, "最近一周")
        if (body in setOf("一月", "一个月", "月", "30天")) return Range(today.minusDays(29), today, "最近一个月")
        return runCatching {
            val dates = body.split(Regex("\\s+")).map(LocalDate::parse)
            val first = dates.first(); val last = dates.getOrElse(1) { first }
            val start = minOf(first, last); val end = maxOf(first, last)
            Range(start, end, if (start == end) "$start" else "$start 至 $end")
        }.getOrNull()
    }

    private data class Range(val start: LocalDate, val end: LocalDate, val label: String)

    private fun format(value: java.math.BigDecimal): String =
        value.setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    companion object {
        const val HELP = "盲盒统计命令:\n盲盒统计 一周\n盲盒统计 一月\n盲盒统计 2026-07-10\n盲盒统计 2026-07-01 2026-07-10"
    }
}
