package com.starlwr.bot.bilibili.report

import com.starlwr.bot.core.command.CommandContext
import com.starlwr.bot.core.command.CommandPermission
import com.starlwr.bot.core.command.StarBotCommand
import com.starlwr.bot.core.plugin.StarBotComponent

/**
 * 盲盒统计查询命令（Kotlin/K2 维护）
 * <p>唤醒词「盲盒统计」，支持 一周/一月/ISO 日期（单个或范围）参数；无参数或 help 时回复用法说明。</p>
 * <p>默认仅主人可用，可通过 starbot.core.command.owners 配置主人账号。</p>
 */
@StarBotComponent
class BlindBoxStatsCommand(private val queries: ReportCommandQueryService) : StarBotCommand {
    override fun getName(): String = "盲盒统计"

    override fun getDescription(): String = "查询会话关联直播间的盲盒统计数据"

    override fun getUsage(): String = "盲盒统计 [一周|一月|YYYY-MM-DD [YYYY-MM-DD]]"

    override fun getPermission(): CommandPermission = CommandPermission.OWNER

    override fun execute(context: CommandContext) {
        when (val result = queries.blindBoxStats(context.platform, context.targetId, context.type, context.argumentText)) {
            is ReportCommandQueryService.BlindBoxResult.Help -> context.reply(result.text)
            is ReportCommandQueryService.BlindBoxResult.Stats -> context.reply(result.text)
        }
    }
}
