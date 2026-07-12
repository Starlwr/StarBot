package com.starlwr.bot.bilibili.report

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.core.event.StarBotExternalBaseEvent
import com.starlwr.bot.core.event.live.common.RandomGiftEvent
import com.starlwr.bot.core.model.GiftInfo
import com.starlwr.bot.core.model.LiveStreamerInfo
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** v2 blind-box accounting port. The purchased box and opened gift are deliberately kept distinct. */
object BlindBoxStatsStore {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sessions = ConcurrentHashMap<String, RoomStats>()
    private val seenEvents = ConcurrentHashMap.newKeySet<Int>()
    private val money = DecimalFormat("0.##")
    private val recordFile: Path = Path.of("blindbox-stats", "records.jsonl")

    @JvmStatic fun reset(event: StarBotExternalBaseEvent) { sessions.remove(roomKey(event)) }

    @JvmStatic fun record(event: RandomGiftEvent) {
        if (event.source == null || !seenEvents.add(System.identityHashCode(event))) return
        sessions.computeIfAbsent(roomKey(event)) { RoomStats(event.source) }.record(event)
        append(event)
        if (seenEvents.size > 4096) seenEvents.clear()
    }

    @JvmStatic fun snapshot(event: StarBotExternalBaseEvent): RoomStats? = sessions[roomKey(event)]?.copy()

    @JvmStatic fun query(uids: Collection<Long>, start: LocalDate, end: LocalDate): RoomStats {
        val result = RoomStats(LiveStreamerInfo(null, "关联直播间", null))
        if (uids.isEmpty() || !Files.exists(recordFile)) return result
        Files.newBufferedReader(recordFile, StandardCharsets.UTF_8).useLines { lines ->
            lines.filter(String::isNotBlank).forEach { line ->
                runCatching {
                    val json = JSON.parseObject(line)
                    val date = LocalDate.parse(json.getString("date"))
                    if (json.getLong("uid") in uids && date in start..end) result.add(json)
                }.onFailure { log.warn("跳过无法解析的盲盒统计记录: {}", line, it) }
            }
        }
        return result
    }

    @JvmStatic fun format(value: Double): String = synchronized(money) { money.format(value) }

    private fun roomKey(event: StarBotExternalBaseEvent): String =
        "${event.platform}:${event.source.uid}:${event.source.roomId}"

    @Synchronized private fun append(event: RandomGiftEvent) {
        runCatching {
            Files.createDirectories(recordFile.parent)
            Files.writeString(recordFile, values(event).toJson(event).toJSONString() + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }.onFailure { log.warn("写入盲盒统计记录失败", it) }
    }

    private fun values(event: RandomGiftEvent): Values {
        // Core contract: randomGiftInfo = purchased box, giftInfo = opened result.
        val box = event.randomGiftInfo
        val gift = event.giftInfo
        val boxCount = box.countOrOne()
        val giftCount = gift.countOrOne()
        val cost = event.price ?: box.total(boxCount)
        val profit = event.profit ?: (gift.total(giftCount) - cost)
        return Values(box, gift, boxCount, giftCount, cost, cost + profit, profit)
    }

    private data class Values(
        val box: GiftInfo?, val gift: GiftInfo?, val boxCount: Int, val giftCount: Int,
        val cost: Double, val value: Double, val profit: Double
    ) {
        fun toJson(event: RandomGiftEvent) = JSONObject().apply {
            val time = java.time.Instant.ofEpochMilli(event.timestamp)
            put("time", time.toString()); put("date", LocalDate.ofInstant(time, ZoneId.systemDefault()).toString())
            put("platform", event.platform); put("uid", event.source.uid); put("roomId", event.source.roomId)
            put("uname", event.source.uname); put("senderUid", event.sender?.uid); put("senderName", event.sender?.uname)
            put("boxName", box?.name); put("boxCount", boxCount); put("giftName", gift?.name); put("giftCount", giftCount)
            put("cost", cost); put("value", value); put("profit", profit)
        }
    }

    class RoomStats internal constructor(source: LiveStreamerInfo) {
        val uid: Long? = source.uid
        val roomId: Long? = source.roomId
        val uname: String? = source.uname
        var boxCount: Long = 0; private set
        var cost: Double = 0.0; private set
        var value: Double = 0.0; private set
        var profit: Double = 0.0; private set
        private val gifts = ConcurrentHashMap<String, Long>()
        private val users = ConcurrentHashMap.newKeySet<String>()

        @Synchronized internal fun record(event: RandomGiftEvent) {
            val v = values(event)
            boxCount += v.boxCount; cost += v.cost; value += v.value; profit += v.profit
            v.gift?.name?.takeIf(String::isNotBlank)?.let { gifts.merge(it, v.giftCount.toLong(), Long::plus) }
            event.sender?.let { (it.uid?.toString() ?: it.uname)?.takeIf(String::isNotBlank)?.let(users::add) }
        }

        @Synchronized internal fun add(json: JSONObject) {
            boxCount += json.getIntValue("boxCount", 1).coerceAtLeast(1)
            cost += json.getDoubleValue("cost"); value += json.getDoubleValue("value"); profit += json.getDoubleValue("profit")
            json.getString("giftName")?.takeIf(String::isNotBlank)?.let {
                gifts.merge(it, json.getIntValue("giftCount", 1).coerceAtLeast(1).toLong(), Long::plus)
            }
            (json.getString("senderUid")?.takeIf(String::isNotBlank) ?: json.getString("senderName"))
                ?.takeIf(String::isNotBlank)?.let(users::add)
        }

        @Synchronized internal fun copy() = RoomStats(LiveStreamerInfo(uid, uname, roomId)).also {
            it.boxCount = boxCount; it.cost = cost; it.value = value; it.profit = profit
            it.gifts.putAll(gifts); it.users.addAll(users)
        }

        val userCount: Int get() = users.size
        fun topGifts(limit: Int): String = gifts.entries.sortedByDescending { it.value }.take(limit.coerceAtLeast(1))
            .joinToString(", ") { "${it.key} x${it.value}" }.ifEmpty { "无" }
    }

    private fun GiftInfo?.countOrOne() = this?.count?.takeIf { it > 0 } ?: 1
    private fun GiftInfo?.total(count: Int) = (this?.price ?: 0.0) * count
}
