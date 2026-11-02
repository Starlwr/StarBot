package com.starlwr.bot.bilibili.risk

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.bilibili.browser.*
import com.starlwr.bot.bilibili.credential.BilibiliBrowserIdentity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.nio.file.Path
import java.util.function.Consumer
import java.nio.ByteBuffer
import java.util.Base64

class BrowserGaiaSessionTest {
    @Test fun `success is harvested once and later attempts reuse the same native page`() {
        val runtime = mock(BilibiliBrowserRuntime::class.java)
        val navigator = mock(BrowserPageNavigator::class.java)
        val identity = mock(BilibiliBrowserIdentity::class.java)
        val connection = mock(CdpConnection::class.java)
        val page = BrowserPage("persistent", connection)
        var listener: Consumer<JSONObject>? = null
        `when`(connection.isOpen).thenReturn(true)
        `when`(connection.onEvent(any<Consumer<JSONObject>>() ?: Consumer {})).thenAnswer {
            listener = it.getArgument(0); AutoCloseable {}
        }
        `when`(connection.call(anyString(), anyMap<String, Any>(), anyLong())).thenAnswer {
            if (it.getArgument<String>(0) == "Network.getResponseBody")
                JSON.parseObject("""{"body":"{\"code\":0,\"data\":{\"token\":\"native-token\",\"host_list\":[]}}"}""")
            else JSON.parseObject("""{"result":{"value":"{}"}}""")
        }
        fun emit(id: String) {
            listener!!.accept(JSON.parseObject("""{"method":"Network.requestWillBeSent","params":{"requestId":"$id","request":{"url":"https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id=123","headers":{}}}}"""))
            listener!!.accept(JSON.parseObject("""{"method":"Network.requestWillBeSentExtraInfo","params":{"requestId":"$id","headers":{"Cookie":"buvid3=actual; DedeUserID=42","User-Agent":"Chrome"}}}"""))
            listener!!.accept(JSON.parseObject("""{"method":"Network.loadingFinished","params":{"requestId":"$id"}}"""))
            listener!!.accept(JSON.parseObject("""{"method":"Network.webSocketCreated","params":{"requestId":"socket-$id","url":"wss://broadcast.chat.bilibili.com:2245/sub"}}"""))
            fun frame(op: Int, body: String, direction: String) {
                val bytes = body.toByteArray()
                val packet = ByteBuffer.allocate(16 + bytes.size).putInt(16 + bytes.size).putShort(16.toShort())
                    .putShort(1.toShort()).putInt(op).putInt(1).put(bytes).array()
                listener!!.accept(JSON.parseObject("""{"method":"Network.webSocketFrame$direction","params":{"requestId":"socket-$id","response":{"opcode":2,"payloadData":"${Base64.getEncoder().encodeToString(packet)}"}}}"""))
            }
            frame(7, """{"roomid":123,"key":"native-token"}""", "Sent")
            frame(8, """{"code":0}""", "Received")
        }
        `when`(runtime.createPage()).thenReturn(page)
        `when`(identity.headers(null)).thenReturn(mapOf("User-Agent" to "Chrome"))
        `when`(runtime.start("Chrome")).thenReturn(BrowserRuntimeInfo(BrowserExecutable(Path.of("test"),"test"),
            "Chrome", "1", "Chrome", "1", Path.of("test"), 1))
        doAnswer { emit("first"); null }.`when`(navigator).navigate(page,"https://live.bilibili.com/123",30)
        val provider = BrowserGaiaVtokenProvider(runtime,navigator,BilibiliRiskProperties(),identity)
        try {
            val first = GaiaChallenge(-352,"voucher",null,"https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id=123")
            val a = provider.submit(first)!!
            assertEquals("native-token",a.harvestedEnvelope!!.getJSONObject("data").getString("token"))
            assertEquals(first.id,a.browserIdentity!!.sourceChallengeId)
            assertTrue(a.officialWsAuthenticated)
            emit("second")
            assertNotNull(provider.submit(first.copy(id="second-challenge")))
            verify(runtime,times(1)).createPage()
            verify(navigator,times(1)).navigate(page,"https://live.bilibili.com/123",30)
            verify(runtime,never()).closePage(page)
        } finally { provider.shutdown() }
        verify(runtime,times(1)).closePage(page)
    }
}
