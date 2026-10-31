package com.starlwr.bot.bilibili.browser

import com.alibaba.fastjson2.JSON
import com.starlwr.bot.bilibili.credential.BilibiliCredentialFileStore
import com.starlwr.bot.bilibili.credential.BilibiliCredentialService
import com.starlwr.bot.bilibili.credential.StoredCookie
import com.starlwr.bot.bilibili.http.BilibiliHttpProperties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.net.URI

class BrowserRuntimeIsolationTest {
    private val properties = BilibiliBrowserProperties()
    private val store = Mockito.mock(BilibiliCredentialFileStore::class.java)
    private val connection = Mockito.mock(CdpConnection::class.java)
    private val runtime = BilibiliBrowserRuntime(properties, BilibiliHttpProperties(),
        Mockito.mock(ChromeForTestingManager::class.java), store).also { it.browser = connection }

    private fun cookies() {
        Mockito.`when`(connection.call("Storage.getCookies", null, 20)).thenReturn(JSON.parseObject("""
            {"cookies":[
              {"name":"buvid3","value":"browser-only","domain":".bilibili.com","path":"/","secure":true},
              {"name":"foreign","value":"ignore","domain":"bilibili.com.evil.test","path":"/"},
              {"name":"expired","value":"ignore","domain":".bilibili.com","path":"/","expires":1},
              {"name":"private","value":"ignore","domain":".bilibili.com","path":"/xlive-private"},
              {"name":"other-host","value":"ignore","domain":"www.bilibili.com","path":"/"}
            ]}
        """))
    }

    @Test
    fun `defaults isolate browser and never opt into persistence`() {
        assertEquals("isolated", properties.credentialSyncMode)
        assertFalse(properties.persistBrowserState)
        assertFalse(properties.permitsCredentialHydration())
        assertFalse(runtime.refreshCanonicalIdentity())
        Mockito.verifyNoInteractions(connection, store)
    }

    @Test
    fun `cookie observation neither hydrates nor persists and returns detached values`() {
        cookies()
        val uri = URI.create("https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo")
        val first = runtime.cookiesForRequest(uri)
        assertEquals(listOf("buvid3"), first.map { it.name })
        first.single().value = "caller-mutated"
        assertEquals("browser-only", runtime.cookiesForRequest(uri).single().value)
        assertTrue(runtime.synchronizeCookiesFromBrowser())
        assertFalse(runtime.synchronizeCookiesFromBrowser())
        Mockito.verifyNoInteractions(store)
        Mockito.verify(connection, Mockito.times(4)).call("Storage.getCookies", null, 20)
        Mockito.verifyNoMoreInteractions(connection)
    }

    @Test
    fun `browser set cookie does not export a persistent credential`() {
        cookies()
        runtime.setCookie(StoredCookie(name = "buvid3", value = "browser-only",
            domain = ".bilibili.com", path = "/", secure = true, transportScope = "browser"))
        Mockito.verifyNoInteractions(store)
    }

    @Test
    fun `isolated audit never promotes or rewrites either identity`() {
        properties.enabled = true
        val service = Mockito.mock(BilibiliCredentialService::class.java)
        val audit = BrowserCredentialAuditService(properties, runtime, store, service)
        audit.audit()
        audit.monitorBidirectionalCandidate()
        Mockito.verifyNoInteractions(store, service, connection)
    }

    @Test
    fun `unknown mode fails closed and legacy import requires explicit opt in`() {
        properties.credentialSyncMode = "typo"
        assertFalse(properties.permitsCredentialHydration())
        properties.credentialSyncMode = "validated-bidirectional"
        assertTrue(properties.permitsCredentialHydration())
        assertFalse(properties.persistBrowserState)
    }

    @Test
    fun `cookie request scope rejects suffix tricks and path prefixes`() {
        assertFalse(BilibiliBrowserRuntime.isBilibiliCookieDomain("evilbilibili.com"))
        assertFalse(BilibiliBrowserRuntime.isBilibiliCookieDomain("bilibili.com.evil.test"))
        assertTrue(BilibiliBrowserRuntime.isBilibiliCookieDomain(".api.live.bilibili.com"))
        assertFalse(BilibiliBrowserRuntime.cookiePathMatches("/xlive", "/xlive-private"))
        assertTrue(BilibiliBrowserRuntime.cookiePathMatches("/xlive", "/xlive/web-room"))
        assertTrue(BilibiliBrowserRuntime.cookiePathMatches("/", "/xlive"))
    }

    @Test
    fun `JVM authoritative mode cannot export browser state even with persistence enabled`() {
        properties.credentialSyncMode = "jvm-authoritative"
        properties.persistBrowserState = true
        assertTrue(properties.permitsCredentialHydration())
        assertFalse(properties.permitsBrowserPersistence())
        cookies()
        assertTrue(runtime.synchronizeCookiesFromBrowser())
        runtime.setCookie(StoredCookie(name = "buvid3", value = "browser-only",
            domain = ".bilibili.com", path = "/", secure = true))
        assertFalse(runtime.synchronizeWebStorageFromPage(BrowserPage("test", connection)))
        Mockito.verifyNoInteractions(store)
    }

    @Test fun `resolver phase never overwrites autonomous browser identity`() {
        properties.credentialSyncMode = "jvm-authoritative"
        runtime.enterResolverIdentity()
        assertFalse(runtime.refreshCanonicalIdentity())
        Mockito.verifyNoInteractions(connection, store)
    }

    @Test fun `resolver observations never persist even under a legacy bidirectional configuration`() {
        properties.enabled = true
        properties.credentialSyncMode = "validated-bidirectional"
        properties.persistBrowserState = true
        runtime.enterResolverIdentity()
        cookies()
        runtime.synchronizeCookiesFromBrowser()
        assertFalse(runtime.synchronizeWebStorageFromPage(BrowserPage("test", connection)))
        BrowserCredentialAuditService(properties, runtime, store,
            Mockito.mock(BilibiliCredentialService::class.java)).monitorBidirectionalCandidate()
        Mockito.verifyNoInteractions(store)
    }
}
