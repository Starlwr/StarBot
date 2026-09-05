package com.starlwr.bot.bilibili.report

import com.alibaba.fastjson2.JSON
import com.starlwr.bot.core.datasource.AbstractDataSource
import com.starlwr.bot.core.plugin.StarBotComponent
import io.lettuce.core.RedisClient
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

@StarBotComponent
class LegacyReportMigrator(
    private val driver: LiveReportDataDriver,
    private val properties: LiveReportStorageProperties,
    private val dataSource: AbstractDataSource
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)
    override fun run(args: ApplicationArguments) {
        if (!properties.migrateLegacy) return
        runCatching { migrateCommunity() }.onFailure { log.warn("社区盲盒 JSONL 迁移失败，源文件未修改", it) }
        if (!properties.type.equals("redis", true) || properties.v2RedisUri != properties.redisUri) {
            runCatching { migrateV2Redis() }.onFailure { log.warn("v2 Redis 数据迁移失败，源数据未修改", it) }
        }
    }

    private fun migrateCommunity() {
        val candidates = listOfNotNull(properties.communityJsonl?.let(Path::of),
            Path.of("blindbox-stats", "records.jsonl"), Path.of("..", "StarBot_v3_NoGit_CommunityThirdparty", "blindbox-stats", "records.jsonl"))
        val path = candidates.firstOrNull(Files::isRegularFile) ?: return
        var imported = 0
        Files.newBufferedReader(path).useLines { lines -> lines.filter(String::isNotBlank).forEach { line ->
            val j = JSON.parseObject(line); val uid = j.getLongValue("uid"); val room = j.getLongValue("roomId")
            val time = runCatching { Instant.parse(j.getString("time")).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
            val session = ReportSession("legacy-community:$uid:${time / 86_400_000}", "bilibili", uid, room, j.getString("uname") ?: "", time)
            driver.createOrResume(session)
            val user = ReportUserDelta(j.getString("senderUid") ?: j.getString("senderName") ?: "unknown",
                j.getString("senderName") ?: "", count = j.getLongValue("boxCount"), value = j.getDoubleValue("value"), profit = j.getDoubleValue("profit"))
            if (driver.apply(session, "community:${sha(line)}", ReportDelta(ReportMetric.BOX,
                    j.getLongValue("boxCount"), j.getDoubleValue("value"), j.getDoubleValue("profit"), user, time,
                    label = j.getString("giftName")))) imported++
            driver.complete(session.sessionId, time)
        } }
        log.info("已幂等导入社区盲盒记录 {} 条: {}", imported, path)
    }

    private fun migrateV2Redis() {
        val client = RedisClient.create(properties.v2RedisUri); val connection = client.connect()
        try {
            val redis = connection.sync(); if (redis.ping() != "PONG") return
            val users = dataSource.allUsers.associateBy { it.roomId }
            val roomIds = listOf("RoomBoxTotal","RoomBoxCount","RoomDanmuTotal","RoomDanmuCount","RoomGiftTotal",
                "RoomGiftProfit","RoomScTotal","RoomScProfit","RoomCaptainTotal","RoomCaptainCount","RoomCommanderTotal",
                "RoomCommanderCount","RoomGovernorTotal","RoomGovernorCount").flatMap(redis::hkeys).toSet()
            roomIds.forEach { roomText ->
                val room = roomText.toLongOrNull() ?: return@forEach; val up = users[room]
                val uid = up?.uid ?: room; val now = System.currentTimeMillis()
                val session = ReportSession("legacy-v2:$uid:$room", "bilibili", uid, room, up?.uname ?: "legacy-v2", now)
                driver.createOrResume(session)
                val count = (redis.hget("RoomBoxTotal", roomText)?.toLongOrNull() ?: 0) + (redis.hget("RoomBoxCount", roomText)?.toLongOrNull() ?: 0)
                val profit = (redis.hget("RoomBoxProfitTotal", roomText)?.toDoubleOrNull() ?: 0.0) + (redis.hget("RoomBoxProfit", roomText)?.toDoubleOrNull() ?: 0.0)
                driver.apply(session, "v2:room:$room", ReportDelta(ReportMetric.BOX, count, profit, profit, occurredAt = now))
                val danmu = hsum(redis, roomText, "RoomDanmuTotal", "RoomDanmuCount").toLong()
                val gift = hsum(redis, roomText, "RoomGiftTotal", "RoomGiftProfit")
                val sc = hsum(redis, roomText, "RoomScTotal", "RoomScProfit")
                if (danmu != 0L) driver.apply(session, "v2:danmu:$room", ReportDelta(ReportMetric.DANMU, danmu, occurredAt = 0))
                if (gift != 0.0) driver.apply(session, "v2:gift:$room", ReportDelta(ReportMetric.GIFT, value = gift, occurredAt = 0))
                if (sc != 0.0) driver.apply(session, "v2:sc:$room", ReportDelta(ReportMetric.SC, value = sc, occurredAt = 0))
                listOf("captain","commander","governor").forEach { level ->
                    val title = level.replaceFirstChar(Char::uppercase); val guard = hsum(redis, roomText, "Room${title}Total", "Room${title}Count").toLong()
                    if (guard != 0L) driver.apply(session, "v2:$level:$room", ReportDelta(ReportMetric.GUARD, guard, occurredAt = 0, label = level))
                }
                driver.complete(session.sessionId, now)
            }
            log.info("已扫描 v2 Redis 盲盒累计数据，房间数 {}", roomIds.size)
        } finally { connection.close(); client.shutdown() }
    }
    private fun hsum(redis: io.lettuce.core.api.sync.RedisCommands<String,String>, field: String, vararg keys: String) =
        keys.sumOf { redis.hget(it, field)?.toDoubleOrNull() ?: 0.0 }
    private fun sha(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}
