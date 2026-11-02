package com.starlwr.bot.bilibili.report

import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.core.message.PushTargetType
import com.starlwr.bot.core.plugin.StarBotComponent
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * OneBot HTTP 快速回复端点（社区版保留，编码问题已修复）。
 * 查询逻辑由 [ReportCommandQueryService] 提供，与 Core 命令（StarBotCommand）共用。
 */
@RestController
@StarBotComponent
class BlindBoxCommandController(private val queries: ReportCommandQueryService) {
    @PostMapping("/blindbox/onebot")
    fun onOneBotEvent(@RequestBody event: JSONObject): JSONObject {
        val operation = JSONObject()
        if (event.getString("post_type") != "message") return operation
        val raw = event.getString("raw_message")?.trim() ?: return operation
        val type = if (event.getString("message_type") == "private") PushTargetType.FRIEND else PushTargetType.GROUP
        val number = if (type == PushTargetType.GROUP) event.getString("group_id") else event.getString("user_id")

        if (raw.startsWith(REPORT_COMMAND)) {
            val reply = queries.liveReportText(PLATFORM, number, type)
            return operation.apply { put("reply", reply); put("auto_escape", false); put("at_sender", false) }
        }
        if (!raw.startsWith(COMMAND)) return operation
        return when (val result = queries.blindBoxStats(PLATFORM, number, type, raw.removePrefix(COMMAND))) {
            is ReportCommandQueryService.BlindBoxResult.Help ->
                operation.apply { put("reply", result.text); put("auto_escape", false) }
            is ReportCommandQueryService.BlindBoxResult.Stats ->
                operation.apply { put("reply", result.text); put("auto_escape", false); put("at_sender", false) }
        }
    }

    private companion object {
        const val COMMAND = "盲盒统计"
        const val REPORT_COMMAND = "直播报告"
        const val PLATFORM = "qq-onebot"
    }
}
