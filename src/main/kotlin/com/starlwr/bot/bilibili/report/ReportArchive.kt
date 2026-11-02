package com.starlwr.bot.bilibili.report

import com.alibaba.fastjson2.JSON
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Versioned protobuf-TLV envelope. JSON payload keeps driver-independent snapshots readable. */
object ReportArchive {
    private const val FORMAT_VERSION = 1
    fun write(snapshot: LiveReportSnapshot, output: OutputStream) {
        val payload = JSON.toJSONBytes(snapshot); val checksum = MessageDigest.getInstance("SHA-256").digest(payload)
        val size = CodedOutputStream.computeUInt32Size(1, FORMAT_VERSION) +
            CodedOutputStream.computeUInt32Size(2, snapshot.schemaVersion) +
            CodedOutputStream.computeByteArraySize(3, payload) + CodedOutputStream.computeByteArraySize(4, checksum)
        val coded = CodedOutputStream.newInstance(output); coded.writeUInt32NoTag(size)
        coded.writeUInt32(1, FORMAT_VERSION); coded.writeUInt32(2, snapshot.schemaVersion)
        coded.writeByteArray(3, payload); coded.writeByteArray(4, checksum); coded.flush()
    }
    fun read(input: InputStream): Sequence<LiveReportSnapshot> = sequence {
        val coded = CodedInputStream.newInstance(input)
        while (!coded.isAtEnd) {
            val length = coded.readUInt32(); val old = coded.pushLimit(length); var format = 0; var schema = 0
            var payload = ByteArray(0); var checksum = ByteArray(0)
            while (coded.bytesUntilLimit > 0) when (val tag = coded.readTag()) {
                8 -> format = coded.readUInt32(); 16 -> schema = coded.readUInt32()
                26 -> payload = coded.readByteArray(); 34 -> checksum = coded.readByteArray()
                else -> if (!coded.skipField(tag)) break
            }
            coded.popLimit(old); require(format == FORMAT_VERSION); require(schema <= LiveReportSnapshot.CURRENT_SCHEMA)
            require(MessageDigest.isEqual(checksum, MessageDigest.getInstance("SHA-256").digest(payload))) { "Archive checksum mismatch" }
            yield(LiveReportSchemaMigration.migrate(JSON.parseObject(payload, LiveReportSnapshot::class.java)))
        }
    }
}
