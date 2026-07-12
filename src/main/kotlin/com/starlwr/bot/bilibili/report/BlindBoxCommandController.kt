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
class BlindBoxCommandController(private val dataSource: AbstractDataSource) {
    @PostMapping("/blindbox/onebot")
    fun onOneBotEvent(@RequestBody event: JSONObject): JSONObject {
        val operation = JSONObject()
        if (event.getString("post_type") != "message") return operation
        val raw = event.getString("raw_message")?.trim() ?: return operation
        if (!raw.startsWith(COMMAND)) return operation
        val range = parseRange(raw.removePrefix(COMMAND).trim()) ?: return operation.apply {
            put("reply", HELP); put("auto_escape", false)
        }
        val type = if (event.getString("message_type") == "private") PushTargetType.FRIEND else PushTargetType.GROUP
        val number = if (type == PushTargetType.GROUP) event.getLong("group_id") else event.getLong("user_id")
        val uids = dataSource.allUsers.asSequence().flatMap { user ->
            (user.targets ?: emptyList()).asSequence().filter { it.platform == PLATFORM && it.num == number && it.type == type }
                .map { user.uid }
        }.distinct().toList()
        val stats = BlindBoxStatsStore.query(uids, range.start, range.end)
        val reply = if (uids.isEmpty()) "当前会话没有关联直播间，无法查询盲盒统计"
        else if (stats.boxCount == 0L) "盲盒统计 ${range.label}\n暂无记录"
        else "盲盒统计 ${range.label}\n盲盒次数: ${stats.boxCount}\n盲盒成本: ${BlindBoxStatsStore.format(stats.cost)}" +
            "\n开出价值: ${BlindBoxStatsStore.format(stats.value)}\n盈亏: ${BlindBoxStatsStore.format(stats.profit)}" +
            "\n参与人数: ${stats.userCount}\nTOP礼物: ${stats.topGifts(10)}"
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
    private companion object {
        const val COMMAND = "盲盒统计"
        const val PLATFORM = "qq-onebot"
        const val HELP = "盲盒统计命令:\n盲盒统计 一周\n盲盒统计 一月\n盲盒统计 2026-07-10\n盲盒统计 2026-07-01 2026-07-10"
    }
}
