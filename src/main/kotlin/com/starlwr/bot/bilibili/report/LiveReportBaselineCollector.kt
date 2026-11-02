package com.starlwr.bot.bilibili.report

import com.starlwr.bot.bilibili.api.BilibiliApiService
import com.starlwr.bot.core.event.live.common.LiveOnEvent
import com.starlwr.bot.core.plugin.StarBotComponent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async

@StarBotComponent
class LiveReportBaselineCollector(private val api: BilibiliApiService, private val collector: LiveReportCollector,
                                  private val demandService: LiveReportDemandService) {
    @Async("bilibiliLiveReportThreadPool") @EventListener
    fun before(event: LiveOnEvent) {
        if (collector.shouldCollectBaseline(event) && needsBaseline(event.source.uid))
            collect(event.source.uid, event.source.roomId)?.let { collector.recordMetadata(event, "before", it) }
    }

    private fun needsBaseline(uid: String?) = demandService.forUid(uid).sections.any { it in setOf("fans", "fans_medal", "guard") }

    private fun collect(uid: String?, roomId: String?): Map<String, Long>? {
        if (uid == null || roomId == null) return null
        val json = api.getLiveReportBaseStats(uid, roomId)
        return listOf("fans", "fans_medal", "guard").mapNotNull { key ->
            if (json.containsKey(key)) key to json.getLongValue(key) else null
        }.toMap()
    }
}
