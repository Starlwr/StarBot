package com.starlwr.bot.bilibili.service

import java.io.EOFException

internal data class ProtoTag(val field: Int, val wireType: Int)

internal class ProtobufWireReader(private val bytes: ByteArray) {
    private var offset = 0

    fun end(): Boolean = offset >= bytes.size

    fun tag(): ProtoTag {
        val value = varint()
        val field = (value ushr 3).toInt()
        require(field > 0) { "invalid protobuf field number" }
        return ProtoTag(field, (value and 7).toInt())
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (offset >= bytes.size) throw EOFException("truncated protobuf varint")
            val value = bytes[offset++].toInt() and 0xff
            result = result or ((value.toLong() and 0x7f) shl shift)
            if (value and 0x80 == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("protobuf varint overflow")
    }

    fun bytes(): ByteArray {
        val length = varint()
        require(length in 0..remaining().toLong()) { "invalid protobuf length" }
        val end = offset + length.toInt()
        return bytes.copyOfRange(offset, end).also { offset = end }
    }

    fun string(): String = bytes().toString(Charsets.UTF_8)

    fun skip(wireType: Int) {
        when (wireType) {
            0 -> varint()
            1 -> skipBytes(8)
            2 -> bytes()
            3 -> skipGroup()
            4 -> Unit
            5 -> skipBytes(4)
            else -> throw IllegalArgumentException("unsupported protobuf wire type $wireType")
        }
    }

    private fun skipGroup() {
        while (!end()) {
            val tag = tag()
            if (tag.wireType == 4) return
            skip(tag.wireType)
        }
        throw EOFException("unterminated protobuf group")
    }

    private fun skipBytes(length: Int) {
        require(length <= remaining()) { "truncated protobuf field" }
        offset += length
    }

    private fun remaining(): Int = bytes.size - offset
}
