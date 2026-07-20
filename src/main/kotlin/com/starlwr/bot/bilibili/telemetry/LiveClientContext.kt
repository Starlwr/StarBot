package com.starlwr.bot.bilibili.telemetry

import com.starlwr.bot.bilibili.http.BilibiliHttpPipeline
import com.starlwr.bot.core.plugin.StarBotComponent
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

data class PlayUrlLease(
    val roomId: Long,
    val anchorUid: Long,
    val areaId: Long,
    val parentAreaId: Long,
    val url: String,
    val obtainedAtEpochMillis: Long,
    val expiresAtEpochSeconds: Long,
) {
    fun needsRefresh(now: Instant = Instant.now()) = now.epochSecond >= expiresAtEpochSeconds - 60
}

data class LiveClientContext(
    val displayRoomId: Long,
    val roomId: Long,
    val anchorUid: Long,
    val areaId: Long,
    val parentAreaId: Long,
    val liveStartedAtEpochMillis: Long,
    val identityRevision: Long,
    val lease: PlayUrlLease,
)

@StarBotComponent
class PlayUrlProvider(
    private val http: BilibiliHttpPipeline,
) {
    private val cache = ConcurrentHashMap<Long, PlayUrlLease>()
    private val inFlight = ConcurrentHashMap<Long, CompletableFuture<PlayUrlLease>>()

    fun current(roomId: Long): PlayUrlLease? = cache[roomId]?.takeUnless { it.needsRefresh() }

    fun get(roomId: Long, force: Boolean = false): PlayUrlLease {
        if (!force) current(roomId)?.let { return it }
        val candidate = CompletableFuture<PlayUrlLease>()
        val existing = inFlight.putIfAbsent(roomId, candidate)
        if (existing != null) return existing.join()
        try {
            return fetch(roomId).also(candidate::complete)
        } catch (error: Throwable) {
            candidate.completeExceptionally(error)
            throw error
        } finally {
            inFlight.remove(roomId, candidate)
        }
    }

    private fun fetch(roomId: Long): PlayUrlLease {
        val params = linkedMapOf(
            "room_id" to roomId.toString(), "platform" to "web", "ptype" to "16",
            "protocol" to "0,1", "format" to "0,2", "codec" to "0,1", "qn" to "10000"
        )
        val query = params.entries.joinToString("&") { "${it.key}=${encode(it.value)}" }
        val response = http.get(
            "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?$query",
            mapOf("Referer" to "https://live.bilibili.com/$roomId"), "bilibili-live-play-url"
        )
        require(response.successful()) { "getRoomPlayInfo HTTP ${response.status}" }
        val root = response.json()
        require(root.getIntValue("code", -1) == 0) { "getRoomPlayInfo code=${root.getIntValue("code", -1)}" }
        val data = root.getJSONObject("data") ?: error("getRoomPlayInfo omitted data")
        val playurl = data.getJSONObject("playurl_info")?.getJSONObject("playurl")
            ?: error("getRoomPlayInfo omitted playurl")
        val streams = playurl.getJSONArray("stream") ?: error("getRoomPlayInfo omitted streams")
        var selected: String? = null
        outer@ for (stream in streams.toList(com.alibaba.fastjson2.JSONObject::class.java)) {
            for (format in stream.getJSONArray("format").orEmpty().filterIsInstance<com.alibaba.fastjson2.JSONObject>()) {
                for (codec in format.getJSONArray("codec").orEmpty().filterIsInstance<com.alibaba.fastjson2.JSONObject>()) {
                    val base = codec.getString("base_url") ?: continue
                    val infos = codec.getJSONArray("url_info") ?: continue
                    val info = infos.getJSONObject(0) ?: continue
                    selected = info.getString("host").orEmpty() + base + info.getString("extra").orEmpty()
                    if (selected!!.startsWith("https://")) break@outer
                }
            }
        }
        val url = selected ?: error("getRoomPlayInfo contained no playable URL")
        val now = Instant.now()
        val expiry = parseExpiry(url)?.takeIf { it > now.epochSecond + 60 } ?: now.epochSecond + 300
        return PlayUrlLease(
            roomId = data.getLongValue("room_id", roomId), anchorUid = data.getLongValue("uid"),
            areaId = data.getLongValue("area_id"), parentAreaId = data.getLongValue("parent_area_id"),
            url = url, obtainedAtEpochMillis = System.currentTimeMillis(), expiresAtEpochSeconds = expiry
        ).also { cache[roomId] = it }
    }

    private fun parseExpiry(url: String): Long? {
        val query = runCatching { URI.create(url).rawQuery }.getOrNull() ?: return null
        for (part in query.split('&')) {
            val key = part.substringBefore('=').lowercase()
            if (key !in setOf("expires", "deadline", "expire")) continue
            val value = part.substringAfter('=', "")
            value.toLongOrNull()?.let { return it }
            value.removePrefix("0x").toLongOrNull(16)?.let { return it }
        }
        return null
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}

private fun com.alibaba.fastjson2.JSONArray?.orEmpty(): List<Any?> = this?.toList() ?: emptyList()
