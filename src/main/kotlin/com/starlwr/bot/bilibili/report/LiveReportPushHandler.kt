package com.starlwr.bot.bilibili.report

import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.core.enums.PushTargetType
import com.starlwr.bot.core.event.StarBotExternalBaseEvent
import com.starlwr.bot.core.handler.StarBotEventHandler
import com.starlwr.bot.core.model.Message
import com.starlwr.bot.core.model.PushMessage
import com.starlwr.bot.core.plugin.StarBotComponent
import com.starlwr.bot.core.sender.StarBotMessageSender
import org.slf4j.LoggerFactory
import com.starlwr.bot.bilibili.util.BilibiliApiUtil
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@StarBotComponent
class LiveReportPushHandler(
    private val collector: LiveReportCollector,
    private val painter: LiveReportPainter,
    private val sender: StarBotMessageSender,
    private val api: BilibiliApiUtil,
    @param:Qualifier("bilibiliLiveReportThreadPool") private val executor: ThreadPoolTaskExecutor
) : StarBotEventHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    private val afterStats = java.util.concurrent.ConcurrentHashMap<String, Map<String, Long>>()
    override fun handle(event: StarBotExternalBaseEvent, pushMessage: PushMessage) {
        try { executor.execute { handleAsync(event, pushMessage) } }
        catch (e: java.util.concurrent.RejectedExecutionException) { log.error("直播报告队列已满，拒绝生成报告", e) }
    }
    private fun handleAsync(event: StarBotExternalBaseEvent, pushMessage: PushMessage) {
        val config = LiveReportTargetConfig.from(pushMessage.paramsJsonObject)
        if (!config.enabled) return
        if (config.sections.any { it.value && it.key in setOf("fans", "fans_medal", "guard") }) {
            val uid=event.source.uid; val room=event.source.roomId
            if(uid!=null && room!=null) {
                val cacheKey="${event.platform}:$uid:${event.timestamp}"
                val values=afterStats.computeIfAbsent(cacheKey) {
                    val json=api.getLiveReportBaseStats(uid,room); listOf("fans","fans_medal","guard").mapNotNull {
                        key -> if(json.containsKey(key)) key to json.getLongValue(key) else null }.toMap()
                }
                if(afterStats.size>2048) afterStats.clear()
                collector.recordMetadata(event,"after",values)
            }
        }
        val snapshot = collector.completed(event) ?: return
        if (config.onlyWhenNonEmpty && snapshot.counts.values.sum() == 0L) return
        val target = pushMessage.target
        val content = try {
            if (config.output.equals("text", true)) painter.text(snapshot, config)
            else {
                val base64 = painter.paint(snapshot, config)
                if (config.saveImage) saveImage(snapshot, config, base64)
                "{image_base64=$base64}"
            }
        } catch (e: Exception) {
            log.error("生成直播报告失败, session={}", snapshot.sessionId, e)
            if (!config.textFallback) return else painter.text(snapshot, config)
        }
        val prefix = if (config.atAll && target.type == PushTargetType.GROUP) "{at=all}{next}" else ""
        val messages = Message.create(target.platform, target.type, target.num, prefix + content)
        messages.forEach(sender::send)
        log.info("直播报告已生成并加入发送队列, session={}, target={}:{}, output={}, messages={}",
            snapshot.sessionId, target.platform, target.num, config.output, messages.size)
    }

    override fun getDefaultParams() = JSONObject.parseObject("""{
      "enabled":true,"output":"image","text_fallback":true,"only_when_non_empty":false,"at_all":false,
      "sections":{"time":true,"danmu":true,"box":true,"gift":true,"sc":true,"guard":true,"fans":false,"fans_medal":false},
      "rankings":{"danmu":{"enabled":false,"top":3},"box":{"enabled":false,"top":3},"gift":{"enabled":false,"top":3},"sc":{"enabled":false,"top":3},"guard":{"enabled":false,"top":3}},
      "charts":{"danmu":{"enabled":false},"box":{"enabled":false},"box_profit":{"enabled":false},"gift":{"enabled":false},"sc":{"enabled":false},"guard":{"enabled":false}},
      "word_cloud":{"enabled":false,"max_words":80,"max_font_size":200,"dictionary":null,"stop_words":null},
      "logo":null,"save_image":false,"save_directory":"report"
    }""")
    private fun saveImage(snapshot: LiveReportSnapshot, config: LiveReportTargetConfig, base64: String) {
        val dir = java.nio.file.Path.of(config.saveDirectory).toAbsolutePath(); java.nio.file.Files.createDirectories(dir)
        val safe = snapshot.uname.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(60)
        java.nio.file.Files.write(dir.resolve("${safe}_${snapshot.roomId}_${snapshot.startedAt}.png"), java.util.Base64.getDecoder().decode(base64))
    }
}
