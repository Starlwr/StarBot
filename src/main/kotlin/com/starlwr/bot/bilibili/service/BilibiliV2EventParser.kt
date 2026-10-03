package com.starlwr.bot.bilibili.service

import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.bilibili.event.live.BilibiliEnterRoomEvent
import com.starlwr.bot.bilibili.event.live.BilibiliFollowEvent
import com.starlwr.bot.bilibili.event.live.BilibiliFreeGiftEvent
import com.starlwr.bot.bilibili.event.live.BilibiliPaidGiftEvent
import com.starlwr.bot.bilibili.event.live.BilibiliRandomGiftEvent
import com.starlwr.bot.bilibili.event.live.BilibiliShareEvent
import com.starlwr.bot.bilibili.model.BilibiliUserInfo
import com.starlwr.bot.bilibili.model.FansMedal
import com.starlwr.bot.bilibili.model.Guard
import com.starlwr.bot.bilibili.util.BilibiliApiUtil
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent
import com.starlwr.bot.core.model.GiftInfo
import com.starlwr.bot.core.model.LiveStreamerInfo
import com.starlwr.bot.core.plugin.StarBotComponent
import org.slf4j.LoggerFactory
import java.time.Instant

@StarBotComponent
class BilibiliV2EventParser(
    private val bilibili: BilibiliApiUtil,
    private val giftService: BilibiliGiftService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val giftDecoder = SendGiftV2Decoder()
    private val interactDecoder = InteractWordV2Decoder()

    fun parseGifts(data: JSONObject, source: LiveStreamerInfo, completeEvent: Boolean): List<StarBotBaseLiveEvent> {
        val payload = extractPayload(data) ?: return emptyList()
        val decoded = runCatching { giftDecoder.decode(payload) }.getOrElse {
            log.warn("解析直播间 {} 的 SEND_GIFT_V2 失败: {}", source.roomId, it.toString())
            return emptyList()
        }
        if (decoded.switchEnabled == false) {
            log.debug("SEND_GIFT_V2 switch=false 仍按兼容模式处理: room={}, gifts={}",
                source.roomId, decoded.gifts.size)
        }
        val sender = buildGiftSender(decoded, source, completeEvent)
        return decoded.gifts.mapIndexedNotNull { index, gift ->
            if (gift.count <= 0 || gift.count > Int.MAX_VALUE) {
                log.warn("忽略数量无法表示的 SEND_GIFT_V2 礼物: room={}, giftId={}, count={}",
                    source.roomId, gift.id, gift.count)
                return@mapIndexedNotNull null
            }
            val count = gift.count.toInt()
            val timestamp = eventTime(gift.timestamp, data)
            val giftInfo = GiftInfo(gift.id, gift.name, coinValue(gift.discountPrice), count, gift.image)
            val blindInfo = decoded.blind?.let { blind ->
                val image = if (completeEvent) {
                    giftService.getGiftImageUrl(blind.id)
                } else null
                GiftInfo(blind.id, blind.name, coinValue(blind.price), count, image)
            }
            val event = when (gift.coinType.lowercase()) {
                "silver" -> BilibiliFreeGiftEvent(source, sender, giftInfo, timestamp)
                "gold" -> if (blindInfo == null) {
                    BilibiliPaidGiftEvent(source, sender, giftInfo, timestamp)
                } else {
                    BilibiliRandomGiftEvent(source, sender, blindInfo, giftInfo, timestamp)
                }
                else -> {
                    log.warn("忽略未知币种的 SEND_GIFT_V2 礼物: room={}, giftId={}, coinType={}",
                        source.roomId, gift.id, gift.coinType)
                    null
                }
            }
            event?.let { com.starlwr.bot.bilibili.report.ReportEventIdentity.bind(it,
                gift.transactionId.takeIf { tid -> tid.isNotBlank() && tid != "0" }
                    ?.let { tid -> "SEND_GIFT_V2:$tid:${gift.id}:$index" }) }
        }
    }

    fun parseInteraction(data: JSONObject, source: LiveStreamerInfo, completeEvent: Boolean): List<StarBotBaseLiveEvent> {
        val payload = extractPayload(data) ?: return emptyList()
        val decoded = runCatching { interactDecoder.decode(payload) }.getOrElse {
            log.warn("解析直播间 {} 的 INTERACT_WORD_V2 失败: {}", source.roomId, it.toString())
            return emptyList()
        }
        val medal = decoded.medal?.let { buildMedal(it.targetUid, it.name, it.level, it.lighted, source, completeEvent) }
        val guard = decoded.privilegeType.takeIf { it != 0 }?.let(::Guard)
        val sender = BilibiliUserInfo(decoded.uid, decoded.name, decoded.face, medal, guard, decoded.wealthLevel)
        val timestamp = eventTime(decoded.timestamp, data)
        return when (decoded.messageType) {
            1 -> listOf(BilibiliEnterRoomEvent(source, sender, decoded.spread,
                decoded.spreadDescription.takeIf(String::isNotBlank), timestamp))
            2 -> listOf(BilibiliFollowEvent(source, sender, timestamp))
            3 -> listOf(BilibiliShareEvent(source, sender, timestamp))
            else -> {
                log.debug("忽略未知 INTERACT_WORD_V2 类型: room={}, msgType={}",
                    source.roomId, decoded.messageType)
                emptyList()
            }
        }
    }

    private fun buildGiftSender(
        decoded: SendGiftV2Decoder.Result,
        source: LiveStreamerInfo,
        completeEvent: Boolean,
    ): BilibiliUserInfo {
        val medalInfo = decoded.senderMedal
        val medal = medalInfo?.let {
            buildMedal(it.targetUid, it.name, it.level, it.lighted, source, completeEvent)
        }
        val guard = decoded.guardLevel.takeIf { it != 0 }?.let {
            Guard(it, medalInfo?.guardIcon)
        }
        return BilibiliUserInfo(decoded.senderUid, decoded.senderName, decoded.senderFace,
            medal, guard, decoded.wealthLevel)
    }

    private fun buildMedal(
        targetUid: Long,
        name: String,
        level: Int,
        lighted: Boolean,
        source: LiveStreamerInfo,
        completeEvent: Boolean,
    ): FansMedal {
        if (!completeEvent) {
            return FansMedal(targetUid, null, null, name, level, lighted)
        }
        val uname = if (targetUid == source.uid) source.uname else bilibili.getUnameByUid(targetUid).orElse(null)
        val roomId = if (targetUid == source.uid) source.roomId else bilibili.getRoomIdByUid(targetUid).orElse(null)
        val face = if (targetUid == source.uid) source.face else bilibili.getFaceByUid(targetUid).orElse(null)
        return FansMedal(targetUid, uname, roomId, face, name, level, lighted)
    }

    private fun extractPayload(data: JSONObject): String? {
        val envelope = data.getJSONObject("data") ?: return null
        return envelope.getString("pb")?.takeIf(String::isNotBlank)
            ?: envelope.getJSONObject("data")?.getString("pb")?.takeIf(String::isNotBlank)
    }

    private fun eventTime(seconds: Long, data: JSONObject): Instant {
        if (seconds > 0) return Instant.ofEpochSecond(seconds)
        val fallback = data.getLong("send_time") ?: data.getLong("timestamp")
        return when {
            fallback == null || fallback <= 0 -> Instant.now()
            fallback >= 100_000_000_000L -> Instant.ofEpochMilli(fallback)
            else -> Instant.ofEpochSecond(fallback)
        }
    }

    private fun coinValue(value: Long): Double = value.toBigDecimal().movePointLeft(3).toDouble()
}
