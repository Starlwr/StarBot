package com.starlwr.bot.bilibili.service

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.util.Base64

/** Minimal protobuf decoder for bilibili.live.gift.v1.SendGiftBroadcast.
 * It deliberately decodes only fields used by the event pipeline and skips
 * unknown wire fields so newer server fields cannot invalidate a gift batch.
 */
class SendGiftV2Decoder {
    data class Gift(
        val id: Long,
        val name: String,
        val count: Long,
        val discountPrice: Long,
        val totalCoin: Long,
        val coinType: String,
        val timestamp: Long,
        val image: String,
    )

    data class Blind(val id: Long, val name: String, val price: Long)

    data class Result(
        val senderUid: Long,
        val senderName: String,
        val senderFace: String,
        val gifts: List<Gift>,
        val blind: Blind?,
        val raw: ByteArray,
        val unknownFieldCount: Int,
    )

    fun decode(base64: String): Result {
        val raw = Base64.getDecoder().decode(base64)
        return decode(raw)
    }

    fun decode(raw: ByteArray): Result {
        val root = Reader(raw)
        var uid = 0L
        var name = ""
        var face = ""
        var senderUid = 0L
        var senderName = ""
        var senderFace = ""
        var blind: Blind? = null
        val gifts = mutableListOf<Gift>()
        var unknown = 0
        while (!root.end()) {
            val tag = root.varint()
            when ((tag ushr 3).toInt()) {
                1 -> uid = root.varint()
                2 -> name = root.string()
                3 -> face = root.string()
                9 -> blind = parseBlind(root.bytes())
                10 -> gifts += parseGift(root.bytes())
                15 -> {
                    val sender = parseSender(root.bytes())
                    senderUid = sender.first; senderName = sender.second; senderFace = sender.third
                }
                8 -> root.skip((tag and 7).toInt())
                else -> { root.skip((tag and 7).toInt()); unknown++ }
            }
        }
        if (senderUid == 0L) senderUid = uid
        if (senderName.isBlank()) senderName = name
        if (senderFace.isBlank()) senderFace = face
        return Result(senderUid, senderName, senderFace, gifts, blind, raw.copyOf(), unknown)
    }

    private fun parseGift(raw: ByteArray): Gift {
        val r = Reader(raw)
        var id = 0L; var name = ""; var count = 0L; var discount = 0L; var total = 0L
        var coin = ""; var timestamp = 0L; var image = ""
        while (!r.end()) {
            val tag = r.varint(); when ((tag ushr 3).toInt()) {
                1 -> id = r.varint(); 2 -> name = r.string(); 3 -> count = r.varint()
                6 -> discount = r.varint(); 7 -> total = r.varint(); 8 -> coin = r.string()
                10 -> timestamp = r.varint(); 35 -> image = parseEffect(r.bytes())
                else -> r.skip((tag and 7).toInt())
            }
        }
        return Gift(id, name, count, discount, total, coin, timestamp, image)
    }

    private fun parseEffect(raw: ByteArray): String {
        val r = Reader(raw); var image = ""
        while (!r.end()) { val tag = r.varint(); if ((tag ushr 3).toInt() == 1) image = r.string() else r.skip((tag and 7).toInt()) }
        return image
    }

    private fun parseBlind(raw: ByteArray): Blind {
        val r = Reader(raw); var id = 0L; var name = ""; var price = 0L
        while (!r.end()) { val tag = r.varint(); when ((tag ushr 3).toInt()) {
            2 -> id = r.varint(); 3 -> name = r.string(); 6 -> price = r.varint(); else -> r.skip((tag and 7).toInt())
        } }
        return Blind(id, name, price)
    }

    private fun parseSender(raw: ByteArray): Triple<Long, String, String> {
        val r = Reader(raw); var uid = 0L; var name = ""; var face = ""
        while (!r.end()) { val tag = r.varint(); when ((tag ushr 3).toInt()) {
            1 -> uid = r.varint(); 2 -> { val b = Reader(r.bytes()); while (!b.end()) { val t = b.varint(); if ((t ushr 3).toInt() == 1) name = b.string() else b.skip((t and 7).toInt()) } }
            else -> r.skip((tag and 7).toInt())
        } }
        return Triple(uid, name, face)
    }

    private class Reader(private val input: ByteArrayInputStream) {
        constructor(bytes: ByteArray) : this(ByteArrayInputStream(bytes))
        fun end() = input.available() == 0
        fun varint(): Long { var result = 0L; var shift = 0; while (shift < 64) { val b = input.read(); if (b < 0) throw EOFException(); result = result or ((b.toLong() and 0x7f) shl shift); if (b and 0x80 == 0) return result; shift += 7 }; throw IllegalArgumentException("protobuf varint overflow") }
        fun bytes(): ByteArray { val n = varint(); require(n in 0..input.available().toLong()) { "invalid protobuf length" }; return input.readNBytes(n.toInt()) }
        fun string() = bytes().toString(Charsets.UTF_8)
        fun skip(wire: Int) { when (wire) {
            0 -> varint(); 1 -> input.skipNBytes(8); 2 -> bytes(); 3 -> { while (!end()) { val tag = varint(); if ((tag and 7).toInt() == 4) break; skip((tag and 7).toInt()) } }
            4 -> Unit; 5 -> input.skipNBytes(4); else -> throw IllegalArgumentException("unsupported protobuf wire type $wire")
        } }
    }
}
