package com.starlwr.bot.bilibili.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.HexFormat
import java.util.zip.DeflaterOutputStream

class DanmakuPacketCodecTest {
    private val codec = DanmakuPacketCodec()

    @Test
    fun `heartbeat and concatenated frames match protocol`() {
        assertEquals(
            DanmakuPacketCodec.BROWSER_OBJECT_HEARTBEAT_HEX,
            HexFormat.of().formatHex(codec.heartbeat()),
        )
        val first = codec.encode(5, "{\"cmd\":\"A\"}".toByteArray(), sequence = 7)
        val second = codec.encode(3, byteArrayOf(0, 0, 0, 42), sequence = 8)
        val frames = codec.decode(first + second)
        assertEquals(listOf(5, 3), frames.map { it.operation })
        assertEquals(42L, frames[1].popularity)
    }

    @Test
    fun `zlib nested frames preserve outer sequence and reject truncation`() {
        val inner = codec.encode(5, "{\"cmd\":\"DANMU_MSG\"}".toByteArray(), sequence = 9)
        val compressed = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(inner) }
        }.toByteArray()
        val frame = codec.decode(codec.encode(5, compressed, version = 2, sequence = 99)).single()
        assertEquals(99L, frame.outerSequence)
        assertEquals("DANMU_MSG", frame.json?.getString("cmd"))
        assertThrows(IllegalArgumentException::class.java) { codec.decode(inner.copyOf(inner.size - 1)) }
    }
}
