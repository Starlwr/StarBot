package com.starlwr.bot.bilibili.browser

import com.alibaba.fastjson2.JSON
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeoutException

class CdpConnectionTest {
    private fun pending(connection: CdpConnection): Map<*, *> {
        val field = CdpConnection::class.java.getDeclaredField("pending").apply { isAccessible = true }
        return field.get(connection) as Map<*, *>
    }
    @Test fun `send failures clean pending even without a listener error`() {
        val socket = mock(WebSocket::class.java)
        val connection = CdpConnection(socket)
        `when`(socket.sendText(anyString(), eq(true))).thenReturn(CompletableFuture.failedFuture(IllegalStateException("send failed")))
        repeat(10) { assertThrows(CompletionException::class.java) { connection.call("Runtime.enable") } }
        assertTrue(pending(connection).isEmpty())
        `when`(socket.sendText(anyString(), eq(true))).thenThrow(IllegalStateException("synchronous send failed"))
        assertThrows(IllegalStateException::class.java) { connection.call("Runtime.enable") }
        assertTrue(pending(connection).isEmpty())
    }
    @Test fun `timeout and responses leave no pending requests`() {
        val socket = mock(WebSocket::class.java)
        val connection = CdpConnection(socket)
        `when`(socket.sendText(anyString(), eq(true))).thenReturn(CompletableFuture.completedFuture(socket))
        assertThrows(TimeoutException::class.java) { connection.call("Runtime.enable", timeoutSeconds = 0) }
        assertTrue(pending(connection).isEmpty())
        `when`(socket.sendText(anyString(), eq(true))).thenAnswer {
            val id = JSON.parseObject(it.getArgument<String>(0)).getLongValue("id")
            connection.receive("""{"id":$id,"result":{"ok":true}}""")
            CompletableFuture.completedFuture(socket)
        }
        assertTrue(connection.call("Runtime.enable").getBooleanValue("ok"))
        assertTrue(pending(connection).isEmpty())
    }
}
