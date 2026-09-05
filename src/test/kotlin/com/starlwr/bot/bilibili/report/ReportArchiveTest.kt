package com.starlwr.bot.bilibili.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ReportArchiveTest {
    @Test fun `protobuf TLV archive round trips multiple snapshots`() {
        val output = ByteArrayOutputStream()
        repeat(2) { ReportArchive.write(ReportSession("s$it", "bilibili", it.toLong(), 3, "u$it", 4).snapshot(), output) }
        val result = ReportArchive.read(ByteArrayInputStream(output.toByteArray())).toList()
        assertEquals(listOf("s0", "s1"), result.map { it.sessionId })
    }
}
