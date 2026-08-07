package com.starlwr.bot.bilibili.credential

import com.alibaba.fastjson2.JSON
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class BilibiliCredentialFileStoreTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `flat credential migrates atomically and excludes session cookies`() {
        val credential = temporary.resolve("bilibili-credential.json")
        Files.writeString(credential, """{"SESSDATA":"sess","bili_jct":"csrf","buvid3":"b3","extraCookies":{"b_lsid":"ephemeral"}}""")
        val properties = BilibiliCredentialProperties().apply {
            credentialFile = credential.toString()
            legacyCookieFile = temporary.resolve("cookies.json").toString()
        }
        val envelope = BilibiliCredentialFileStore(properties).load()!!
        assertEquals(CredentialEnvelope.CURRENT_SCHEMA, envelope.schemaVersion)
        assertEquals("sess", envelope.account.sessData)
        assertFalse(envelope.cookies.any { it.name.equals("b_lsid", true) })
        assertTrue(Files.isRegularFile(temporary.resolve("bilibili-credential.json.v1.bak")))
        val persisted = JSON.parseObject(Files.readString(credential))
        assertEquals(CredentialEnvelope.CURRENT_SCHEMA, persisted.getIntValue("schemaVersion"))
        assertFalse(Files.readString(credential).contains("ephemeral"))
    }

    @Test
    fun `schema two envelope keeps identity data and gains probe lease`() {
        val credential = temporary.resolve("bilibili-credential.json")
        Files.writeString(credential, """
            {
              "schemaVersion": 2,
              "identityRevision": 7,
              "account": {
                "SESSDATA": "sess",
                "bili_jct": "csrf",
                "lastValidatedAtEpochSeconds": 1000,
                "expiresAtEpochSeconds": 9000,
                "nextRefreshAtEpochSeconds": 3000
              },
              "configuredProfile": {"userAgent": "preserved-agent"}
            }
        """.trimIndent())
        val properties = BilibiliCredentialProperties().apply {
            credentialFile = credential.toString()
            legacyCookieFile = temporary.resolve("cookies.json").toString()
            validationLeaseSeconds = 180
        }

        val envelope = BilibiliCredentialFileStore(properties).load()!!

        assertEquals(CredentialEnvelope.CURRENT_SCHEMA, envelope.schemaVersion)
        assertEquals(7, envelope.identityRevision)
        assertEquals("preserved-agent", envelope.configuredProfile.userAgent)
        assertEquals(1180, envelope.account.validationLeaseExpiresAtEpochSeconds)
        assertEquals(9000, envelope.account.expiresAtEpochSeconds)
        assertEquals(3000, envelope.account.nextRefreshAtEpochSeconds)
        assertEquals(
            "1920x1080",
            envelope.cookies.single { it.name == "browser_resolution" }.value,
        )
    }

    @Test
    fun `server refresh probe state survives credential round trip`() {
        val credential = temporary.resolve("bilibili-credential.json")
        val properties = BilibiliCredentialProperties().apply {
            credentialFile = credential.toString()
            legacyCookieFile = temporary.resolve("cookies.json").toString()
        }
        val store = BilibiliCredentialFileStore(properties)
        val account = com.starlwr.bot.bilibili.model.Cookies().apply {
            sessData = "sess"
            biliJct = "csrf"
            validationLeaseExpiresAtEpochSeconds = 2000
            serverRefreshRequired = true
            serverRefreshCheckedAtEpochSeconds = 1800
            serverRefreshTimestampMillis = 1_784_306_585_715L
        }

        store.saveCookies(account)
        val loaded = BilibiliCredentialFileStore(properties).loadCookies()!!

        assertEquals(true, loaded.serverRefreshRequired)
        assertEquals(1800, loaded.serverRefreshCheckedAtEpochSeconds)
        assertEquals(1_784_306_585_715L, loaded.serverRefreshTimestampMillis)
        assertEquals(2000, loaded.validationLeaseExpiresAtEpochSeconds)
    }

    @Test
    fun `modeled cookies replace conflicting legacy extras`() {
        val credential = temporary.resolve("bilibili-credential.json")
        val properties = BilibiliCredentialProperties().apply {
            credentialFile = credential.toString()
            legacyCookieFile = temporary.resolve("cookies.json").toString()
        }
        val store = BilibiliCredentialFileStore(properties)
        val account = com.starlwr.bot.bilibili.model.Cookies().apply {
            sessData = "current-session"
            biliJct = "current-csrf"
            biliTicket = "current-ticket"
            biliTicketExpires = 9999
            extraCookies["SESSDATA"] = "stale-session"
            extraCookies["bili_ticket_expires"] = "1111"
            extraCookies["browser_resolution"] = "1710-930"
        }

        store.saveCookies(account)
        val envelope = BilibiliCredentialFileStore(properties).load()!!

        assertFalse(envelope.account.extraCookies.containsKey("SESSDATA"))
        assertFalse(envelope.account.extraCookies.containsKey("bili_ticket_expires"))
        assertEquals("1920x1080", envelope.account.extraCookies["browser_resolution"])
        assertEquals("current-session", envelope.cookies.single { it.name == "SESSDATA" }.value)
        assertEquals("9999", envelope.cookies.single { it.name == "bili_ticket_expires" }.value)
    }

    @Test
    fun `schema three migrates refresh token out of Cookie jar and scopes JVM session data`() {
        val credential = temporary.resolve("bilibili-credential.json")
        Files.writeString(credential, """
            {
              "schemaVersion": 3,
              "account": {
                "SESSDATA": "sess",
                "bili_jct": "csrf",
                "ac_time_value": "refresh-secret",
                "biliTicket": "jvm-ticket",
                "biliTicketExpires": 9999999999,
                "extraCookies": {"sid": "jvm-sid"}
              },
              "cookies": [
                {"name":"ac_time_value","value":"refresh-secret","transportScope":"shared"},
                {"name":"bili_ticket","value":"jvm-ticket","transportScope":"shared"},
                {"name":"sid","value":"jvm-sid","transportScope":"shared"}
              ]
            }
        """.trimIndent())
        val properties = BilibiliCredentialProperties().apply {
            credentialFile = credential.toString()
            legacyCookieFile = temporary.resolve("cookies.json").toString()
        }

        val envelope = BilibiliCredentialFileStore(properties).load()!!

        assertEquals(CredentialEnvelope.CURRENT_SCHEMA, envelope.schemaVersion)
        assertEquals("refresh-secret", envelope.account.acTimeValue)
        assertFalse(envelope.cookies.any { it.name.equals("ac_time_value", true) })
        assertTrue(envelope.cookies.any { it.name == "bili_ticket" && it.transportScope == "jvm" })
        assertTrue(envelope.cookies.any { it.name == "sid" && it.transportScope == "jvm" })
        assertTrue(Files.isRegularFile(temporary.resolve("bilibili-credential.json.schema3.bak")))
    }

    @Test
    fun `browser observation cannot project account fields`() {
        val credential = temporary.resolve("bilibili-credential.json")
        val properties = BilibiliCredentialProperties().apply {
            credentialFile = credential.toString()
            legacyCookieFile = temporary.resolve("cookies.json").toString()
        }
        val store = BilibiliCredentialFileStore(properties)
        store.saveCookies(com.starlwr.bot.bilibili.model.Cookies().apply {
            sessData = "jvm-session"
            biliJct = "jvm-csrf"
        })

        store.mergeCookies(listOf(StoredCookie(
            name = "SESSDATA", value = "browser-session", transportScope = "browser",
            expiresAtEpochSeconds = Instant.now().epochSecond + 3600, source = "browser-audit",
        )), projectAccount = false)

        assertEquals("jvm-session", store.loadCookies()!!.sessData)
    }

    @Test
    fun `pending refresh survives round trip and completes atomically`() {
        val credential = temporary.resolve("bilibili-credential.json")
        val properties = BilibiliCredentialProperties().apply {
            credentialFile = credential.toString()
            legacyCookieFile = temporary.resolve("cookies.json").toString()
        }
        val store = BilibiliCredentialFileStore(properties)
        val candidate = com.starlwr.bot.bilibili.model.Cookies().apply {
            sessData = "new-session"; biliJct = "new-csrf"; acTimeValue = "new-refresh"
        }
        store.stagePendingRefresh(PendingCredentialRefresh(
            transactionId = "tx", oldRefreshToken = "old-refresh", candidate = candidate,
        ))

        assertEquals("tx", BilibiliCredentialFileStore(properties).apply { load() }.pendingRefresh()!!.transactionId)
        store.completePendingRefresh(candidate, listOf(StoredCookie(
            name = "sid", value = "new-sid", transportScope = "jvm", source = "credential-refresh"
        )))

        assertEquals("new-session", store.loadCookies()!!.sessData)
        assertTrue(store.pendingRefresh() == null)
        assertTrue(store.snapshot()!!.cookies.any { it.name == "sid" && it.transportScope == "jvm" })
    }

    @Test
    fun `refresh completion preserves server Cookie attributes without duplicate domains`() {
        val credential = temporary.resolve("bilibili-credential.json")
        val properties = BilibiliCredentialProperties().apply {
            credentialFile = credential.toString()
            legacyCookieFile = temporary.resolve("cookies.json").toString()
        }
        val store = BilibiliCredentialFileStore(properties)
        val expiry = Instant.now().epochSecond + 7200
        val candidate = com.starlwr.bot.bilibili.model.Cookies().apply {
            sessData = "new-session"
            biliJct = "new-csrf"
            expiresAtEpochSeconds = expiry
        }

        store.completePendingRefresh(candidate, listOf(StoredCookie(
            name = "SESSDATA", value = "new-session", domain = ".bilibili.com",
            hostOnly = false, secure = true, httpOnly = true, sameSite = "None",
            expiresAtEpochSeconds = expiry, transportScope = "shared", source = "credential-refresh",
        )))

        val cookies = store.snapshot()!!.cookies.filter { it.name == "SESSDATA" }
        assertEquals(1, cookies.size)
        assertEquals(".bilibili.com", cookies.single().domain)
        assertEquals("None", cookies.single().sameSite)
        assertTrue(cookies.single().httpOnly)
        assertEquals(expiry, cookies.single().expiresAtEpochSeconds)
    }

    @Test
    fun `same credential generation cannot shorten trusted SESSDATA expiry`() {
        val credential = temporary.resolve("bilibili-credential.json")
        val properties = BilibiliCredentialProperties().apply {
            credentialFile = credential.toString()
            legacyCookieFile = temporary.resolve("cookies.json").toString()
        }
        val store = BilibiliCredentialFileStore(properties)
        val serverExpiry = Instant.now().epochSecond + 30 * 24 * 60 * 60
        val account = com.starlwr.bot.bilibili.model.Cookies().apply {
            sessData = "same-session"
            biliJct = "csrf"
            expiresAtEpochSeconds = serverExpiry
        }
        store.saveCookiesWithMetadata(account, listOf(StoredCookie(
            name = "SESSDATA", value = "same-session", domain = ".bilibili.com",
            hostOnly = false, httpOnly = true, secure = true, sameSite = "None",
            expiresAtEpochSeconds = serverExpiry, source = "qr-login",
        )))

        account.expiresAtEpochSeconds = Instant.now().epochSecond + 180
        store.saveCookies(account)

        val persisted = store.snapshot()!!.cookies.single { it.name == "SESSDATA" }
        assertEquals(serverExpiry, persisted.expiresAtEpochSeconds)
        assertEquals("qr-login", persisted.source)
    }

    @Test
    fun `domain SESSDATA survives restart and covers main live and API hosts`() {
        val credential = temporary.resolve("bilibili-credential.json")
        val properties = BilibiliCredentialProperties().apply {
            credentialFile = credential.toString()
            legacyCookieFile = temporary.resolve("cookies.json").toString()
        }
        val expiry = Instant.now().epochSecond + 7200
        BilibiliCredentialFileStore(properties).saveCookiesWithMetadata(
            com.starlwr.bot.bilibili.model.Cookies().apply {
                sessData = "domain-session"
                biliJct = "csrf"
                expiresAtEpochSeconds = expiry
            },
            listOf(StoredCookie(
                name = "SESSDATA", value = "domain-session", domain = ".bilibili.com",
                hostOnly = false, secure = true, httpOnly = true, sameSite = "None",
                expiresAtEpochSeconds = expiry, source = "qr-login",
            )),
        )
        val restarted = BilibiliCredentialFileStore(properties).apply { load() }
        val persisted = restarted.snapshot()!!.cookies.single { it.name == "SESSDATA" }
        assertTrue(persisted.matches(
            java.net.URI.create("https://www.bilibili.com/"),
            "jvm",
            Instant.now().epochSecond + 301,
        ))

        listOf(
            "https://www.bilibili.com/",
            "https://live.bilibili.com/22384516",
            "https://api.bilibili.com/x/web-interface/nav",
        ).forEach { url ->
            assertEquals(
                "domain-session",
                restarted.cookiesFor(java.net.URI.create(url), "jvm")
                    .single { it.name == "SESSDATA" }.value,
            )
        }
    }

    @Test
    fun `server host-only metadata is not widened during atomic commit`() {
        val credential = temporary.resolve("bilibili-credential.json")
        val properties = BilibiliCredentialProperties().apply {
            credentialFile = credential.toString()
            legacyCookieFile = temporary.resolve("cookies.json").toString()
        }
        val store = BilibiliCredentialFileStore(properties)
        val expiry = Instant.now().epochSecond + 7200

        store.saveCookiesWithMetadata(
            com.starlwr.bot.bilibili.model.Cookies().apply {
                sessData = "host-session"
                biliJct = "csrf"
                expiresAtEpochSeconds = expiry
            },
            listOf(StoredCookie(
                name = "SESSDATA", value = "host-session", domain = "passport.bilibili.com",
                hostOnly = true, secure = true, httpOnly = true, expiresAtEpochSeconds = expiry,
                source = "qr-login",
            )),
        )

        val cookies = store.snapshot()!!.cookies.filter { it.name == "SESSDATA" }
        assertEquals(1, cookies.size)
        assertTrue(cookies.single().hostOnly)
        assertEquals("passport.bilibili.com", cookies.single().domain)
    }
}
