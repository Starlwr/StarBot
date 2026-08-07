package com.starlwr.bot.bilibili.credential

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties
import com.starlwr.bot.bilibili.http.BilibiliHttpProperties
import com.starlwr.bot.bilibili.log.BilibiliNetworkLogger
import com.starlwr.bot.bilibili.model.Cookies
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CredentialMaintenanceStateTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `maintenance stages retain independent failure state`() {
        val fixture = fixture()
        val credential = credential()

        val validation = fixture.service.recordMaintenanceFailure(
            credential,
            CredentialMaintenanceStage.VALIDATION,
            IllegalStateException("nav unavailable"),
            now = 1_000,
        )
        val probe = fixture.service.recordMaintenanceFailure(
            credential,
            CredentialMaintenanceStage.REFRESH_WINDOW,
            IllegalStateException("cookie info unavailable"),
            now = 1_001,
        )
        val refresh = fixture.service.recordMaintenanceFailure(
            credential,
            CredentialMaintenanceStage.REFRESH,
            IllegalStateException("correspond rejected"),
            now = 1_002,
        )

        assertEquals(60, validation.retrySeconds)
        assertEquals(60, probe.retrySeconds)
        assertEquals(60, refresh.retrySeconds)
        assertEquals(1, credential.validationFailureCount)
        assertEquals(1_060, credential.validationRetryAfterEpochSeconds)
        assertEquals(1, credential.refreshWindowFailureCount)
        assertEquals(1_061, credential.refreshWindowRetryAfterEpochSeconds)
        assertEquals(1, credential.refreshFailureCount)
        assertEquals(1_062, credential.refreshRetryAfterEpochSeconds)
        assertTrue(credential.validationLastFailureReason.contains("nav unavailable"))
        assertTrue(credential.refreshWindowLastFailureReason.contains("cookie info unavailable"))
        assertTrue(credential.refreshLastFailureReason.contains("correspond rejected"))
    }

    @Test
    fun `successful stage clears only its own failure`() {
        val fixture = fixture()
        val credential = credential()
        CredentialMaintenanceStage.entries.forEach { stage ->
            fixture.service.recordMaintenanceFailure(
                credential,
                stage,
                IllegalStateException(stage.name),
                now = 2_000,
            )
        }

        assertTrue(fixture.service.clearMaintenanceFailure(
            credential,
            CredentialMaintenanceStage.VALIDATION,
            "validation recovered",
        ))

        assertEquals(0, credential.validationFailureCount)
        assertEquals(1, credential.refreshWindowFailureCount)
        assertEquals(1, credential.refreshFailureCount)
    }

    @Test
    fun `server refresh false resolves only failures observed before that window`() {
        val fixture = fixture()
        val credential = credential()
        fixture.service.recordMaintenanceFailure(
            credential,
            CredentialMaintenanceStage.REFRESH,
            IllegalStateException("refresh failed"),
            now = 3_000,
        )
        credential.serverRefreshRequired = false
        credential.serverRefreshCheckedAtEpochSeconds = 2_999

        assertFalse(fixture.service.reconcileRefreshFailureWithServerWindow(credential))
        assertEquals(1, credential.refreshFailureCount)

        credential.serverRefreshCheckedAtEpochSeconds = 3_001
        assertTrue(fixture.service.reconcileRefreshFailureWithServerWindow(credential))
        assertEquals(0, credential.refreshFailureCount)
        assertEquals(0, credential.refreshRetryAfterEpochSeconds)
    }

    @Test
    fun `stage failures survive restart without changing ownership`() {
        val fixture = fixture()
        val credential = credential()
        fixture.service.recordMaintenanceFailure(
            credential,
            CredentialMaintenanceStage.REFRESH_WINDOW,
            IllegalStateException("probe failed"),
            now = 4_000,
        )

        val restarted = BilibiliCredentialFileStore(fixture.properties).loadCookies()!!

        assertEquals(0, restarted.validationFailureCount)
        assertEquals(1, restarted.refreshWindowFailureCount)
        assertEquals(4_060, restarted.refreshWindowRetryAfterEpochSeconds)
        assertEquals(0, restarted.refreshFailureCount)
    }

    @Test
    fun `pending refresh recovery honors actual refresh backoff`() {
        val fixture = fixture()
        val now = System.currentTimeMillis() / 1_000
        val credential = credential().apply {
            refreshFailureCount = 1
            refreshRetryAfterEpochSeconds = now + 3_600
            refreshLastFailureAtEpochSeconds = now
            refreshLastFailureReason = "previous confirmation failure"
        }
        fixture.store.saveCookies(credential)
        fixture.store.stagePendingRefresh(PendingCredentialRefresh(
            transactionId = "pending",
            oldRefreshToken = "old-refresh",
            candidate = credential(),
        ))

        assertSame(credential, fixture.service.maintain(credential))
        assertEquals(0, fixture.store.pendingRefresh()!!.lastAttemptAtEpochMillis)
    }

    @Test
    fun `schema five global backoff migrates conservatively then fresh false resolves it`() {
        val credentialFile = temporary.resolve("bilibili-credential.json")
        Files.writeString(credentialFile, """
            {
              "schemaVersion": 5,
              "account": {
                "SESSDATA": "session",
                "bili_jct": "csrf",
                "refreshFailureCount": 30,
                "refreshRetryAfterEpochSeconds": 9000,
                "serverRefreshRequired": false,
                "serverRefreshCheckedAtEpochSeconds": 8000,
                "serverRefreshWindowExpiresAtEpochSeconds": 10000,
                "serverRefreshTimestampMillis": 8000000
              }
            }
        """.trimIndent())
        val fixture = fixture()

        val migrated = fixture.store.loadCookies()!!

        assertEquals(30, migrated.refreshFailureCount)
        assertEquals(0, migrated.validationFailureCount)
        assertEquals(0, migrated.refreshWindowFailureCount)
        assertTrue(fixture.service.reconcileRefreshFailureWithServerWindow(migrated))
        assertEquals(0, migrated.refreshFailureCount)
    }

    private fun fixture(): Fixture {
        val properties = BilibiliCredentialProperties().apply {
            credentialFile = temporary.resolve("bilibili-credential.json").toString()
            legacyCookieFile = temporary.resolve("cookies.json").toString()
            refreshRetryBaseSeconds = 60
            refreshRetryMaxSeconds = 3_600
        }
        val store = BilibiliCredentialFileStore(properties)
        val service = BilibiliCredentialService(
            properties,
            BilibiliBrowserIdentity(properties),
            BilibiliNetworkLogger(StarBotBilibiliProperties()),
            store,
            BilibiliHttpProperties(),
        )
        return Fixture(properties, store, service)
    }

    private fun credential() = Cookies().apply {
        sessData = "session"
        biliJct = "csrf"
        dedeUserId = "42"
        acTimeValue = "refresh-token"
        buvid3 = "buvid3"
    }

    private data class Fixture(
        val properties: BilibiliCredentialProperties,
        val store: BilibiliCredentialFileStore,
        val service: BilibiliCredentialService,
    )
}
