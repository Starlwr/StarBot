package com.starlwr.bot.bilibili.credential

import com.alibaba.fastjson2.JSON
import com.starlwr.bot.bilibili.live.connection.ConnectInfo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class RequestIdentitySnapshotTest {
    @Test
    fun `JVM identity comes from the exact frozen effective cookie header`() {
        val snapshot = RequestIdentitySnapshot.jvm(mapOf("Cookie" to "buvid3=effective; DedeUserID=42; sid=s"))
        assertEquals("effective", snapshot.buvid3)
        assertEquals("42", snapshot.uid)
        assertFalse(snapshot.isBrowser)
    }

    @Test
    fun `snapshot cannot change with caller headers and never prints credentials`() {
        val original = mutableMapOf("Cookie" to "buvid3=browser-only", "User-Agent" to "test")
        val snapshot = RequestIdentitySnapshot(original, "browser-only", "1", "challenge-1")
        original["Cookie"] = "buvid3=changed"
        assertEquals("buvid3=browser-only", snapshot.headers["Cookie"])
        assertThrows(UnsupportedOperationException::class.java) { snapshot.headers["Cookie"] = "changed" }
        assertFalse(snapshot.toString().contains("browser-only"))
    }

    @Test
    fun `out of order responses retain their own identity and never overwrite another session`() {
        val first = CompletableFuture<ConnectInfo>()
        val second = CompletableFuture<ConnectInfo>()
        val a = RequestIdentitySnapshot(mapOf("Cookie" to "buvid3=a"), "a", "1", "challenge-a")
        val b = RequestIdentitySnapshot(mapOf("Cookie" to "buvid3=b"), "b", "1", "challenge-b")
        second.complete(ConnectInfo("token-b", emptyList()).also { it.identity = b })
        first.complete(ConnectInfo("token-a", emptyList()).also { it.identity = a })
        assertEquals("a", first.join().identity.buvid3)
        assertEquals("b", second.join().identity.buvid3)
        assertSame(a, first.join().identity)
        assertSame(b, second.join().identity)
    }

    @Test
    fun `connect info serialization omits run only identity`() {
        val info = ConnectInfo("token", emptyList()).also {
            it.identity = RequestIdentitySnapshot(mapOf("Cookie" to "secret-cookie"), "secret-buvid", "1", "c")
        }
        val serialized = JSON.toJSONString(info)
        assertFalse(serialized.contains("secret-cookie"))
        assertFalse(serialized.contains("secret-buvid"))
        assertFalse(serialized.contains("identity"))
    }

    @Test
    fun `browser identity is stale when bilibili rotates key cookies`() {
        val snapshot = RequestIdentitySnapshot(
            mapOf("Cookie" to "buvid3=old; buvid4=old4; bili_ticket=old-ticket; bili_jct=old-jct"),
            "old", "42", "challenge-1")

        // 关键字段一致 → 未漂移
        assertFalse(snapshot.keyIdentityDrifted(mapOf(
            "buvid3" to "old", "buvid4" to "old4", "bili_ticket" to "old-ticket", "bili_jct" to "old-jct")))
        // 浏览器自行轮换任一关键字段 → 作废
        assertTrue(snapshot.keyIdentityDrifted(mapOf(
            "buvid3" to "new", "buvid4" to "old4", "bili_ticket" to "old-ticket", "bili_jct" to "old-jct")))
        assertTrue(snapshot.keyIdentityDrifted(mapOf(
            "buvid3" to "old", "buvid4" to "new4", "bili_ticket" to "old-ticket", "bili_jct" to "old-jct")))
        // 登记时缺失的关键凭据在浏览器出现(ticket 首次下发) → 身份基础变化
        val noTicket = RequestIdentitySnapshot(
            mapOf("Cookie" to "buvid3=old; buvid4=old4"),
            "old", "42", "challenge-1")
        assertTrue(noTicket.keyIdentityDrifted(mapOf(
            "buvid3" to "old", "buvid4" to "old4", "bili_ticket" to "fresh", "bili_jct" to "old-jct")))
        // 无法读取浏览器 jar(空) → 无法判定, 保守沿用
        assertFalse(snapshot.keyIdentityDrifted(emptyMap()))
    }
}
