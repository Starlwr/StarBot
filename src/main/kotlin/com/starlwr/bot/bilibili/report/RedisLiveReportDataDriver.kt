package com.starlwr.bot.bilibili.report

import com.alibaba.fastjson2.JSON
import io.lettuce.core.RedisClient
import io.lettuce.core.ScriptOutputType

class RedisLiveReportDataDriver(uri: String, private val prefix: String = "starbot:report:v1") : LiveReportDataDriver {
    override val id = "redis"
    private val client = RedisClient.create(uri)
    private val connection = client.connect()
    private val redis get() = connection.sync()
    override fun initialize() { check(redis.ping() == "PONG") { "Redis is unavailable" } }

    override fun createOrResume(session: ReportSession): LiveReportSnapshot {
        val key = sessionKey(session.sessionId)
        redis.setnx(key, encode(session.snapshot()))
        redis.expire(key, SESSION_TTL_SECONDS)
        return decode(redis.get(key))
    }

    override fun apply(session: ReportSession, eventId: String, delta: ReportDelta): Boolean {
        repeat(32) {
            val snapshotKey = sessionKey(session.sessionId)
            val expected = redis.get(snapshotKey) ?: encode(session.snapshot())
            val next = decode(expected).also { it.apply(delta) }
            val result = try { redis.eval<Long>(APPLY_LUA, ScriptOutputType.INTEGER,
                arrayOf(snapshotKey, eventKey(session.sessionId)), expected, encode(next), eventId, SESSION_TTL_SECONDS.toString()) }
            catch (e: io.lettuce.core.RedisCommandExecutionException) {
                if (e.message?.contains("scripting support disabled", true) == true)
                    return applyWithoutLua(session, eventId, delta) else throw e
            }
            when (result) { 1L -> return true; 0L -> return false }
        }
        error("Concurrent report update retry limit exceeded for ${session.sessionId}")
    }

    override fun snapshot(sessionId: String): LiveReportSnapshot? = redis.get(sessionKey(sessionId))?.let(::decode)
    override fun complete(sessionId: String, endedAt: Long): LiveReportSnapshot? {
        repeat(32) {
            val key = sessionKey(sessionId); val expected = redis.get(key) ?: return null
            val next = decode(expected).also { it.endedAt = endedAt }; val encoded = encode(next)
            val changed = try { redis.eval<Long>(COMPLETE_LUA, ScriptOutputType.INTEGER,
                arrayOf(key, recentKey(next.uid)), expected, encoded, sessionId, next.startedAt.toString(), HISTORY_TTL_SECONDS.toString()) }
            catch (e: io.lettuce.core.RedisCommandExecutionException) {
                if (e.message?.contains("scripting support disabled", true) == true)
                    return completeWithoutLua(sessionId, endedAt) else throw e
            }
            if (changed == 1L) return next
        }
        error("Concurrent report completion retry limit exceeded for $sessionId")
    }
    override fun recent(uid: Long, limit: Int): List<LiveReportSnapshot> = redis.zrevrange(recentKey(uid), 0, limit.coerceIn(1, 100).toLong() - 1)
        .mapNotNull { snapshot(it) }
    override fun health() = runCatching { redis.ping() }.fold({ DriverHealth(it == "PONG") }, { DriverHealth(false, it.message ?: "redis error") })
    override fun close() { connection.close(); client.shutdown() }

    @Synchronized private fun applyWithoutLua(session: ReportSession, eventId: String, delta: ReportDelta): Boolean {
        repeat(32) {
            val key = sessionKey(session.sessionId); val events = eventKey(session.sessionId)
            redis.watch(key, events)
            if (redis.sismember(events, eventId)) { redis.unwatch(); return false }
            val current = redis.get(key)?.let(::decode) ?: session.snapshot(); current.apply(delta)
            redis.multi(); redis.setex(key, SESSION_TTL_SECONDS, encode(current)); redis.sadd(events, eventId)
            redis.expire(events, SESSION_TTL_SECONDS)
            if (!redis.exec().wasDiscarded()) return true
        }
        error("Concurrent report update retry limit exceeded for ${session.sessionId}")
    }

    @Synchronized private fun completeWithoutLua(sessionId: String, endedAt: Long): LiveReportSnapshot? {
        repeat(32) {
            val key = sessionKey(sessionId); redis.watch(key); val current = redis.get(key)?.let(::decode) ?: run { redis.unwatch(); return null }
            current.endedAt = endedAt; redis.multi(); redis.setex(key, HISTORY_TTL_SECONDS, encode(current))
            redis.zadd(recentKey(current.uid), current.startedAt.toDouble(), sessionId)
            if (!redis.exec().wasDiscarded()) return current
        }
        error("Concurrent report completion retry limit exceeded for $sessionId")
    }

    private fun sessionKey(id: String) = "$prefix:{$id}:snapshot"
    private fun eventKey(id: String) = "$prefix:{$id}:events"
    private fun recentKey(uid: Long) = "$prefix:recent:$uid"
    private fun encode(s: LiveReportSnapshot) = JSON.toJSONString(s)
    private fun decode(s: String) = LiveReportSchemaMigration.migrate(JSON.parseObject(s, LiveReportSnapshot::class.java))
    companion object {
        private const val SESSION_TTL_SECONDS = 7 * 24 * 3600L
        private const val HISTORY_TTL_SECONDS = 365 * 24 * 3600L
        private const val APPLY_LUA = """
            if redis.call('SISMEMBER', KEYS[2], ARGV[3]) == 1 then return 0 end
            local current = redis.call('GET', KEYS[1])
            if current and current ~= ARGV[1] then return -1 end
            redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[4])
            redis.call('SADD', KEYS[2], ARGV[3]); redis.call('EXPIRE', KEYS[2], ARGV[4]); return 1
        """
        private const val COMPLETE_LUA = """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[5])
            redis.call('ZADD', KEYS[2], ARGV[4], ARGV[3]); return 1
        """
    }
}
