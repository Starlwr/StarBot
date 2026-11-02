package com.starlwr.bot.bilibili.service

import java.util.Base64

class SendGiftV2Decoder {
    data class Gift(
        val id: Long,
        val name: String,
        val count: Long,
        val price: Long,
        val discountPrice: Long,
        val totalCoin: Long,
        val coinType: String,
        val transactionId: String,
        val timestamp: Long,
        val action: String,
        val image: String,
    )

    data class Blind(val id: Long, val name: String, val price: Long) {
        fun isPresent() = id != 0L || name.isNotBlank() || price != 0L
    }

    data class Medal(
        val targetUid: Long,
        val name: String,
        val level: Int,
        val lighted: Boolean,
        val guardIcon: String,
    ) {
        fun isPresent() = targetUid != 0L
    }

    data class Sender(
        val uid: Long,
        val name: String,
        val face: String,
        val medal: Medal?,
    )

    data class Result(
        val uid: Long,
        val name: String,
        val face: String,
        val guardLevel: Int,
        val switchEnabled: Boolean?,
        val wealthLevel: Int?,
        val sender: Sender?,
        val gifts: List<Gift>,
        val blind: Blind?,
        val raw: ByteArray,
        val unknownFieldCount: Int,
    ) {
        val senderUid get() = sender?.uid?.takeIf { it != 0L } ?: uid
        val senderName get() = sender?.name?.takeIf { it.isNotBlank() } ?: name
        val senderFace get() = sender?.face?.takeIf { it.isNotBlank() } ?: face
        val senderMedal get() = sender?.medal
    }

    fun decode(base64: String): Result = decode(Base64.getDecoder().decode(base64))

    fun decode(raw: ByteArray): Result {
        val reader = ProtobufWireReader(raw)
        var uid = 0L
        var name = ""
        var face = ""
        var guardLevel = 0
        var switchEnabled: Boolean? = null
        var wealthLevel: Int? = null
        var sender: Sender? = null
        var rootMedal: Medal? = null
        var blind: Blind? = null
        val gifts = mutableListOf<Gift>()
        var unknown = 0
        while (!reader.end()) {
            val tag = reader.tag()
            when (tag.field) {
                1 -> uid = reader.varint()
                2 -> name = reader.string()
                3 -> face = reader.string()
                5 -> guardLevel = reader.varint().toInt()
                8 -> rootMedal = parseLegacyMedal(reader.bytes())
                9 -> blind = parseBlind(reader.bytes()).takeIf(Blind::isPresent)
                10 -> gifts += parseGift(reader.bytes())
                11 -> switchEnabled = reader.varint() != 0L
                13 -> wealthLevel = parseWealth(reader.bytes())
                15 -> sender = parseSender(reader.bytes())
                else -> {
                    reader.skip(tag.wireType)
                    unknown++
                }
            }
        }
        if (sender?.medal == null && rootMedal?.isPresent() == true) {
            sender = (sender ?: Sender(0, "", "", null)).copy(medal = rootMedal)
        }
        return Result(uid, name, face, guardLevel, switchEnabled, wealthLevel, sender,
            gifts.toList(), blind, raw.copyOf(), unknown)
    }

    private fun parseGift(raw: ByteArray): Gift {
        val reader = ProtobufWireReader(raw)
        var id = 0L
        var name = ""
        var count = 0L
        var price = 0L
        var discountPrice = 0L
        var totalCoin = 0L
        var coinType = ""
        var transactionId = ""
        var timestamp = 0L
        var action = ""
        var image = ""
        while (!reader.end()) {
            val tag = reader.tag()
            when (tag.field) {
                1 -> id = reader.varint()
                2 -> name = reader.string()
                3 -> count = reader.varint()
                5 -> price = reader.varint()
                6 -> discountPrice = reader.varint()
                7 -> totalCoin = reader.varint()
                8 -> coinType = reader.string()
                9 -> transactionId = reader.string()
                10 -> timestamp = reader.varint()
                18 -> action = reader.string()
                35 -> image = parseGiftMaterial(reader.bytes())
                else -> reader.skip(tag.wireType)
            }
        }
        return Gift(id, name, count, price, discountPrice, totalCoin, coinType,
            transactionId, timestamp, action, image)
    }

    private fun parseGiftMaterial(raw: ByteArray): String {
        val reader = ProtobufWireReader(raw)
        var image = ""
        while (!reader.end()) {
            val tag = reader.tag()
            if (tag.field == 1) image = reader.string() else reader.skip(tag.wireType)
        }
        return image
    }

    private fun parseBlind(raw: ByteArray): Blind {
        val reader = ProtobufWireReader(raw)
        var id = 0L
        var name = ""
        var price = 0L
        while (!reader.end()) {
            val tag = reader.tag()
            when (tag.field) {
                2 -> id = reader.varint()
                3 -> name = reader.string()
                6 -> price = reader.varint()
                else -> reader.skip(tag.wireType)
            }
        }
        return Blind(id, name, price)
    }

    private fun parseSender(raw: ByteArray): Sender {
        val reader = ProtobufWireReader(raw)
        var uid = 0L
        var name = ""
        var face = ""
        var medal: Medal? = null
        while (!reader.end()) {
            val tag = reader.tag()
            when (tag.field) {
                1 -> uid = reader.varint()
                2 -> parseUserBase(reader.bytes()).also { name = it.first; face = it.second }
                3 -> medal = parseSenderMedal(reader.bytes()).takeIf(Medal::isPresent)
                else -> reader.skip(tag.wireType)
            }
        }
        return Sender(uid, name, face, medal)
    }

    private fun parseUserBase(raw: ByteArray): Pair<String, String> {
        val reader = ProtobufWireReader(raw)
        var name = ""
        var face = ""
        while (!reader.end()) {
            val tag = reader.tag()
            when (tag.field) {
                1 -> name = reader.string()
                2 -> face = reader.string()
                else -> reader.skip(tag.wireType)
            }
        }
        return name to face
    }

    private fun parseSenderMedal(raw: ByteArray): Medal {
        val reader = ProtobufWireReader(raw)
        var targetUid = 0L
        var name = ""
        var level = 0
        var lighted = false
        var guardIcon = ""
        while (!reader.end()) {
            val tag = reader.tag()
            when (tag.field) {
                1 -> name = reader.string()
                2 -> level = reader.varint().toInt()
                9 -> lighted = reader.varint() != 0L
                10 -> targetUid = reader.varint()
                13 -> guardIcon = reader.string()
                else -> reader.skip(tag.wireType)
            }
        }
        return Medal(targetUid, name, level, lighted, guardIcon)
    }

    private fun parseLegacyMedal(raw: ByteArray): Medal {
        val reader = ProtobufWireReader(raw)
        var targetUid = 0L
        var name = ""
        var level = 0
        var lighted = false
        while (!reader.end()) {
            val tag = reader.tag()
            when (tag.field) {
                1 -> targetUid = reader.varint()
                5 -> level = reader.varint().toInt()
                6 -> name = reader.string()
                8 -> lighted = reader.varint() != 0L
                else -> reader.skip(tag.wireType)
            }
        }
        return Medal(targetUid, name, level, lighted, "")
    }

    private fun parseWealth(raw: ByteArray): Int? {
        val reader = ProtobufWireReader(raw)
        var level: Int? = null
        while (!reader.end()) {
            val tag = reader.tag()
            if (tag.field == 1) level = reader.varint().toInt() else reader.skip(tag.wireType)
        }
        return level
    }
}
