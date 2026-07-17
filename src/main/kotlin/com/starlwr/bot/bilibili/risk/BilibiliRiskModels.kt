package com.starlwr.bot.bilibili.risk

import com.alibaba.fastjson2.JSON
import com.starlwr.bot.core.plugin.StarBotComponent
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@ConfigurationProperties("starbot.bilibili.risk")
class BilibiliRiskProperties {
    var http412Enabled: Boolean = true
    var powLimit: Int = 0x4C4B40
    var submitReserveSeconds: Long = 30
    var maximumChallenges: Int = 2
    var gaiaEnabled: Boolean = true
    var gaiaBackoffSeconds: MutableList<Long> = mutableListOf(30, 120, 600)
}

data class Http412Challenge(
    val cookieValue: String,
    val version: String,
    val token: String,
    val q: String,
    val targetHex: String,
    val ip: String,
    val fingerprint: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val verity: Int,
)

data class PowSolution(val result: Int, val elapsedMillis: Long)

@StarBotComponent
@EnableConfigurationProperties(BilibiliRiskProperties::class)
class Http412Resolver(private val properties: BilibiliRiskProperties) {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun <T> singleFlight(partition: String, operation: () -> T): T {
        val lock = locks.computeIfAbsent(partition) { ReentrantLock() }
        return lock.withLock(operation).also {
            if (!lock.hasQueuedThreads()) locks.remove(partition, lock)
        }
    }

    fun parse(setCookie: String): Http412Challenge? {
        val first = setCookie.substringBefore(';')
        val name = first.substringBefore('=', "").trim()
        if (!name.equals("X-BILI-SEC-TOKEN", true)) return null
        val cookieValue = first.substringAfter('=', "")
        val version = cookieValue.substringBefore(',', "")
        val token = cookieValue.substringAfter(',', "")
        if (version.isBlank() || token.count { it == '.' } != 2) return null
        val payloadPart = token.split('.')[1]
        val padded = payloadPart + "=".repeat((4 - payloadPart.length % 4) % 4)
        val payload = runCatching {
            JSON.parseObject(String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8))
        }.getOrNull() ?: return null
        val q = payload.getString("q") ?: return null
        val target = payload.getString("r") ?: return null
        if (q.isEmpty() || target.length != 64 || target.any { it !in "0123456789abcdefABCDEF" }) return null
        return Http412Challenge(
            cookieValue, version, token, q, target.lowercase(), payload.getString("ip").orEmpty(),
            payload.getString("fp").orEmpty(), payload.getLongValue("iat"), payload.getLongValue("exp"),
            payload.getIntValue("verity")
        )
    }

    fun solve(challenge: Http412Challenge, cancelled: AtomicBoolean = AtomicBoolean(false)): PowSolution {
        require(properties.http412Enabled) { "HTTP 412 resolver disabled" }
        require(challenge.verity == 0) { "HTTP 412 challenge is already verified" }
        require(challenge.expiresAt > Instant.now().epochSecond + properties.submitReserveSeconds) {
            "HTTP 412 challenge expired or lacks submit reserve"
        }
        val prefix = challenge.q.toByteArray(StandardCharsets.UTF_8)
        val target = challenge.targetHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256")
        val started = System.nanoTime()
        for (candidate in 0 until properties.powLimit) {
            if ((candidate and 0x3fff) == 0) {
                check(!cancelled.get()) { "HTTP 412 PoW cancelled" }
                check(challenge.expiresAt > Instant.now().epochSecond + properties.submitReserveSeconds) {
                    "HTTP 412 challenge expired while solving"
                }
            }
            digest.reset()
            digest.update(prefix)
            digest.update(candidate.toString().toByteArray(StandardCharsets.US_ASCII))
            if (MessageDigest.isEqual(digest.digest(), target)) {
                return PowSolution(candidate, (System.nanoTime() - started) / 1_000_000)
            }
        }
        error("HTTP 412 PoW target was not found within configured limit ${properties.powLimit}")
    }
}

data class GaiaChallenge(
    val id: String = UUID.randomUUID().toString(),
    val businessCode: Int,
    val voucher: String?,
    val gaData: String?,
    val originalUri: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val expiresAtEpochMillis: Long? = null,
) {
    constructor(businessCode: Int, voucher: String?, gaData: String?, originalUri: String) : this(
        UUID.randomUUID().toString(), businessCode, voucher, gaData, originalUri, System.currentTimeMillis(), null
    )
}

interface GaiaChallengeProvider {
    fun submit(challenge: GaiaChallenge): String?
}

/** Safe default: expose a replay-oriented challenge record, never synthesize a token. */
@StarBotComponent
class ReportingGaiaChallengeProvider(private val properties: BilibiliRiskProperties) : GaiaChallengeProvider {
    private val log = LoggerFactory.getLogger(javaClass)
    private val reports = ConcurrentHashMap<String, Pair<Int, Long>>()
    override fun submit(challenge: GaiaChallenge): String? {
        val key = "${challenge.businessCode}|${challenge.originalUri.substringBefore('?')}"
        val now = System.currentTimeMillis()
        val previous = reports[key]
        if (previous != null && now < previous.second) {
            log.info("Bilibili Gaia challenge remains pending; duplicate report suppressed: id={}, code={}, retryAfterMs={}",
                challenge.id, challenge.businessCode, previous.second - now)
            return null
        }
        val attempt = ((previous?.first ?: 0) + 1).coerceAtMost(properties.maximumChallenges.coerceAtLeast(1))
        val delays = properties.gaiaBackoffSeconds.ifEmpty { mutableListOf(30L) }
        val delay = delays[(attempt - 1).coerceAtMost(delays.lastIndex)].coerceAtLeast(1)
        reports[key] = attempt to (now + delay * 1000)
        log.warn("Bilibili Gaia challenge requires an interactive provider: id={}, code={}, uri={}, expiresAt={}",
            challenge.id, challenge.businessCode, challenge.originalUri, challenge.expiresAtEpochMillis)
        return null
    }
}
