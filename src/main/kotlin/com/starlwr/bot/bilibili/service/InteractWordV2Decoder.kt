package com.starlwr.bot.bilibili.service

import java.util.Base64

class InteractWordV2Decoder {
    data class Medal(val targetUid: Long, val level: Int, val name: String, val lighted: Boolean) {
        fun isPresent() = targetUid != 0L
    }

    data class Result(
        val uid: Long,
        val name: String,
        val face: String,
        val messageType: Int,
        val timestamp: Long,
        val medal: Medal?,
        val spread: Boolean,
        val spreadDescription: String,
        val privilegeType: Int,
        val wealthLevel: Int?,
        val raw: ByteArray,
    )

    fun decode(base64: String): Result = decode(Base64.getDecoder().decode(base64))

    fun decode(raw: ByteArray): Result {
        val reader = ProtobufWireReader(raw)
        var uid = 0L
        var name = ""
        var face = ""
        var messageType = 0
        var timestamp = 0L
        var medal: Medal? = null
        var spread = false
        var spreadDescription = ""
        var privilegeType = 0
        var wealthLevel: Int? = null
        while (!reader.end()) {
            val tag = reader.tag()
            when (tag.field) {
                1 -> uid = reader.varint()
                2 -> name = reader.string()
                5 -> messageType = reader.varint().toInt()
                7 -> timestamp = reader.varint()
                9 -> medal = parseMedal(reader.bytes()).takeIf(Medal::isPresent)
                10 -> spread = reader.varint() != 0L
                13 -> spreadDescription = reader.string()
                16 -> privilegeType = reader.varint().toInt()
                22 -> parseUserInfo(reader.bytes()).also {
                    if (it.first.isNotBlank()) face = it.first
                    wealthLevel = it.second
                }
                else -> reader.skip(tag.wireType)
            }
        }
        return Result(uid, name, face, messageType, timestamp, medal, spread,
            spreadDescription, privilegeType, wealthLevel, raw.copyOf())
    }

    private fun parseMedal(raw: ByteArray): Medal {
        val reader = ProtobufWireReader(raw)
        var targetUid = 0L
        var level = 0
        var name = ""
        var lighted = false
        while (!reader.end()) {
            val tag = reader.tag()
            when (tag.field) {
                1 -> targetUid = reader.varint()
                2 -> level = reader.varint().toInt()
                3 -> name = reader.string()
                8 -> lighted = reader.varint() != 0L
                else -> reader.skip(tag.wireType)
            }
        }
        return Medal(targetUid, level, name, lighted)
    }

    private fun parseUserInfo(raw: ByteArray): Pair<String, Int?> {
        val reader = ProtobufWireReader(raw)
        var face = ""
        var wealthLevel: Int? = null
        while (!reader.end()) {
            val tag = reader.tag()
            when (tag.field) {
                2 -> face = parseBaseFace(reader.bytes())
                4 -> wealthLevel = parseWealth(reader.bytes())
                else -> reader.skip(tag.wireType)
            }
        }
        return face to wealthLevel
    }

    private fun parseBaseFace(raw: ByteArray): String {
        val reader = ProtobufWireReader(raw)
        var face = ""
        while (!reader.end()) {
            val tag = reader.tag()
            if (tag.field == 2) face = reader.string() else reader.skip(tag.wireType)
        }
        return face
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
