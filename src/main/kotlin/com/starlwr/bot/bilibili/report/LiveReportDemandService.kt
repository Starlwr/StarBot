package com.starlwr.bot.bilibili.report

import com.starlwr.bot.core.datasource.AbstractDataSource
import com.starlwr.bot.core.event.datasource.base.StarBotDataSourceChangeEvent
import com.starlwr.bot.core.event.datasource.other.StarBotDataSourceLoadCompleteEvent
import com.starlwr.bot.core.plugin.StarBotComponent
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import java.util.concurrent.ConcurrentHashMap

data class ReportDemand(val enabled: Boolean = false, val sections: Set<String> = emptySet(),
                        val charts: Set<String> = emptySet(), val wordCloud: Boolean = false)

@StarBotComponent
class LiveReportDemandService(private val dataSource: AbstractDataSource) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val demands = ConcurrentHashMap<Long, ReportDemand>()
    @EventListener(StarBotDataSourceLoadCompleteEvent::class)
    fun reload() {
        demands.clear()
        dataSource.allUsers.forEach { user ->
            val configs = user.targets.orEmpty().flatMap { it.messages.orEmpty() }
                .filter { message -> message.enabled != false && listOf(LiveReportPushHandler::class.java.simpleName,
                    BlindBoxLiveOffReportHandler::class.java.simpleName, BlindBoxRecordHandler::class.java.simpleName)
                    .any { message.handler?.endsWith(it) == true } }
                .map { LiveReportTargetConfig.from(it.paramsJsonObject) }.filter { it.enabled }
            demands[user.uid] = ReportDemand(configs.isNotEmpty(),
                configs.flatMap { c -> c.sections.filterValues { it }.keys }.toSet(),
                configs.flatMap { c -> c.charts.filterValues { it }.keys }.toSet(),
                configs.any { it.wordCloud })
        }
        val enabled = demands.filterValues { it.enabled }
        log.info("直播报告采集需求已刷新, 启用主播数={}, UID={}", enabled.size, enabled.keys.sorted())
    }
    @EventListener fun onChange(@Suppress("UNUSED_PARAMETER") event: StarBotDataSourceChangeEvent) = reload()
    fun forUid(uid: Long?) = uid?.let { demands[it] } ?: ReportDemand()
}
