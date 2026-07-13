package com.starlwr.bot.bilibili.report

import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.core.datasource.AbstractDataSource
import com.starlwr.bot.core.enums.PushTargetType
import com.starlwr.bot.core.plugin.StarBotComponent
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/** OneBot HTTP adapter retained from the community edition, with its broken encoding repaired. */
@RestController
@StarBotComponent
class BlindBoxCommandController(private val dataSource: AbstractDataSource, private val driver: LiveReportDataDriver,
                                private val painter: LiveReportPainter) {
    @PostMapping("/blindbox/onebot")
    fun onOneBotEvent(@RequestBody event: JSONObject): JSONObject {
        val operation = JSONObject()
        if (event.getString("post_type") != "message") return operation
        val raw = event.getString("raw_message")?.trim() ?: return operation
        val type = if (event.getString("message_type") == "private") PushTargetType.FRIEND else PushTargetType.GROUP
        val number = if (type == PushTargetType.GROUP) event.getLong("group_id") else event.getLong("user_id")
        val uids = dataSource.allUsers.asSequence().flatMap { user ->
            (user.targets ?: emptyList()).asSequence().filter { it.platform == PLATFORM && it.num == number && it.type == type }
                .map { user.uid }
        }.distinct().toList()
        if (raw.startsWith(REPORT_COMMAND)) {
            val reports = uids.mapNotNull { driver.recent(it, 1).firstOrNull() }
            val reply = if (uids.isEmpty()) "当前会话没有关联直播间，无法查询直播报告"
                else if (reports.isEmpty()) "暂无已完成的直播报告"
                else reports.joinToString("\n\n") { painter.text(it, LiveReportTargetConfig(output = "text")) }
            return operation.apply { put("reply", reply); put("auto_escape", false); put("at_sender", false) }
        }
        if (!raw.startsWith(COMMAND)) return operation
        val range = parseRange(raw.removePrefix(COMMAND).trim()) ?: return operation.apply {
            put("reply", HELP); put("auto_escape", false)
        }
        val snapshots = uids.flatMap { driver.recent(it, 100) }.filter {
            val date = java.time.Instant.ofEpochMilli(it.startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            date in range.start..range.end
        }
        val boxes = snapshots.sumOf { it.counts["box"] ?: 0 }
        val cost = snapshots.sumOf { (it.values["box"] ?: 0.0) - (it.profits["box"] ?: 0.0) }
        val value = snapshots.sumOf { it.values["box"] ?: 0.0 }
        val profit = snapshots.sumOf { it.profits["box"] ?: 0.0 }
        val participants = snapshots.flatMap { it.users["box"].orEmpty().keys }.toSet().size
        val gifts = snapshots.flatMap { it.labels["box"].orEmpty().entries }.groupingBy { it.key }.fold(0L) { acc, entry -> acc + entry.value }
            .entries.sortedByDescending { it.value }.take(10).joinToString(", ") { "${it.key} x${it.value}" }.ifEmpty { "无" }
        val reply = if (uids.isEmpty()) "当前会话没有关联直播间，无法查询盲盒统计"
        else if (boxes == 0L) "盲盒统计 ${range.label}\n暂无记录"
        else "盲盒统计 ${range.label}\n盲盒次数: $boxes\n盲盒成本: ${format(cost)}" +
            "\n开出价值: ${format(value)}\n盈亏: ${format(profit)}" +
            "\n参与人数: $participants\nTOP礼物: $gifts"
        return operation.apply { put("reply", reply); put("auto_escape", false); put("at_sender", false) }
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
    private fun format(value: Double) = java.text.DecimalFormat("0.##").format(value)
    private companion object {
        const val COMMAND = "盲盒统计"
        const val REPORT_COMMAND = "直播报告"
        const val PLATFORM = "qq-onebot"
        const val HELP = "盲盒统计命令:\n盲盒统计 一周\n盲盒统计 一月\n盲盒统计 2026-07-10\n盲盒统计 2026-07-01 2026-07-10"
    }
}
