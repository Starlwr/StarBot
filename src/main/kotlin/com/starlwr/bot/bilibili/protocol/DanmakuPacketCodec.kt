package com.starlwr.bot.bilibili.protocol

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.core.plugin.StarBotComponent
import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream

data class DanmakuCodecLimits(
    val maximumPacketBytes: Int = 8 * 1024 * 1024,
    val maximumDecompressedBytes: Int = 32 * 1024 * 1024,
    val maximumDepth: Int = 4,
    val maximumFrames: Int = 10_000,
)

data class DanmakuFrame(
    val packetLength: Int,
    val headerLength: Int,
    val version: Int,
    val operation: Int,
    val sequence: Long,
    val outerSequence: Long,
    val body: ByteArray,
    val json: JSONObject? = null,
    val popularity: Long? = null,
)

@StarBotComponent
class DanmakuPacketCodec {
    @JvmOverloads
    fun encode(operation: Int, body: ByteArray = ByteArray(0), version: Int = 1, sequence: Long = 1): ByteArray {
        require(operation >= 0 && version >= 0 && sequence in 0..0xffff_ffffL)
        val result = ByteBuffer.allocate(16 + body.size).order(ByteOrder.BIG_ENDIAN)
        result.putInt(16 + body.size).putShort(16).putShort(version.toShort())
            .putInt(operation).putInt(sequence.toInt()).put(body)
        return result.array()
    }

    fun heartbeat(): ByteArray = encode(2, BilibiliHeartbeatPayload.bytes())

    @JvmOverloads
    fun decode(input: ByteArray, limits: DanmakuCodecLimits = DanmakuCodecLimits()): List<DanmakuFrame> {
        val output = mutableListOf<DanmakuFrame>()
        decodeInto(input, limits, 0, null, output)
        return output
    }

    private fun decodeInto(
        bytes: ByteArray,
        limits: DanmakuCodecLimits,
        depth: Int,
        inheritedOuterSequence: Long?,
        output: MutableList<DanmakuFrame>,
    ) {
        require(depth <= limits.maximumDepth) { "maximum danmaku packet nesting depth exceeded" }
        var offset = 0
        while (offset < bytes.size) {
            require(bytes.size - offset >= 16) { "truncated danmaku packet header" }
            val header = ByteBuffer.wrap(bytes, offset, 16).order(ByteOrder.BIG_ENDIAN)
            val packetLength = header.int
            val headerLength = header.short.toInt() and 0xffff
            val version = header.short.toInt() and 0xffff
            val operation = header.int
            val sequence = header.int.toLong() and 0xffff_ffffL
            require(headerLength >= 16 && packetLength >= headerLength) { "invalid danmaku packet/header length" }
            require(packetLength <= limits.maximumPacketBytes) { "danmaku packet exceeds configured limit" }
            require(offset + packetLength <= bytes.size) { "truncated danmaku packet body" }
            val body = bytes.copyOfRange(offset + headerLength, offset + packetLength)
            val outerSequence = inheritedOuterSequence ?: sequence
            if (version == 2 || version == 3) {
                val decompressed = decompress(body, version, limits.maximumDecompressedBytes)
                decodeInto(decompressed, limits, depth + 1, outerSequence, output)
            } else {
                val json = if (operation == 5 || operation == 8) parseJson(body) else null
                val popularity = if (operation == 3 && body.size >= 4) {
                    ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffff_ffffL
                } else null
                output += DanmakuFrame(packetLength, headerLength, version, operation, sequence,
                    outerSequence, body, json, popularity)
                require(output.size <= limits.maximumFrames) { "danmaku frame count exceeds configured limit" }
            }
            offset += packetLength
        }
    }

    private fun decompress(body: ByteArray, version: Int, limit: Int): ByteArray {
        val input = if (version == 3) BrotliInputStream(ByteArrayInputStream(body))
        else InflaterInputStream(ByteArrayInputStream(body))
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                require(output.size() + read <= limit) { "decompressed danmaku data exceeds configured limit" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun parseJson(body: ByteArray): JSONObject? {
        if (body.isEmpty()) return null
        return runCatching {
            val value = JSON.parse(String(body, StandardCharsets.UTF_8))
            when (value) {
                is JSONObject -> value
                else -> JSONObject().fluentPut("value", value)
            }
        }.getOrNull()
    }

    companion object {
        const val BROWSER_OBJECT_HEARTBEAT_HEX =
            "0000001f0010000100000002000000015b6f626a656374204f626a6563745d"
    }
}
