package com.starlwr.bot.bilibili.report

import com.starlwr.bot.core.command.CommandContext
import com.starlwr.bot.core.command.CommandPermission
import com.starlwr.bot.core.command.CommandScope
import com.starlwr.bot.core.command.StarBotCommand
import com.starlwr.bot.core.plugin.StarBotComponent

/**
 * 直播报告查询命令（Kotlin/K2 维护）
 * <p>唤醒词「直播报告」（别名「直播報告」），查询会话关联主播最近一场已完成的直播报告文本。</p>
 * <p>默认仅主人可用，可通过 starbot.core.command.owners 配置主人账号。</p>
 */
@StarBotComponent
class LiveReportQueryCommand(private val queries: ReportCommandQueryService) : StarBotCommand {
    override fun getName(): String = "直播报告"

    override fun getAliases(): List<String> = listOf("直播報告")

    override fun getDescription(): String = "查询会话关联直播间最近一场已完成的直播报告"

    override fun getUsage(): String = "直播报告"

    override fun getArgumentCount(): Int = 0

    override fun getPermission(): CommandPermission = CommandPermission.OWNER

    override fun getScope(): CommandScope = CommandScope.ALL

    override fun execute(context: CommandContext) {
        context.reply(queries.liveReportText(context.platform, context.targetId, context.type))
    }
}
