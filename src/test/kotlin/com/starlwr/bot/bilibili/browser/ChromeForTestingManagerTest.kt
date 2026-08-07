package com.starlwr.bot.bilibili.browser

import com.alibaba.fastjson2.JSON
import com.starlwr.bot.bilibili.credential.BilibiliCredentialFileStore
import com.starlwr.bot.bilibili.credential.BilibiliCredentialProperties
import com.starlwr.bot.bilibili.http.BilibiliHttpProperties
import com.sun.net.httpserver.HttpServer
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ChromeForTestingManagerTest {
    @TempDir
    lateinit var temporary: Path

    private val manager = ChromeForTestingManager(BilibiliBrowserProperties(), BilibiliHttpProperties())

    @Test
    fun `selects exact then smallest higher version within same major`() {
        val catalog = ChromeForTestingCatalog(versions = mutableListOf(
            version("152.0.7924.0"), version("153.0.1.0"), version("152.0.7923.0"),
        ))
        assertEquals("152.0.7923.0", manager.selectVersion(catalog, "152.0.7923.0", "win64")?.version)
        assertEquals("152.0.7923.0", manager.selectVersion(catalog, "152.0.0.0", "win64")?.version)
        assertNull(manager.selectVersion(catalog, "151.9.9.9", "win64"))
        assertNull(manager.selectVersion(catalog, "152.0.7925.0", "win64"))
        assertTrue(BilibiliBrowserRuntime.browserVersionMatches("Chrome/152.0.7923.0", "152.0.7923.0"))
        assertTrue(!BilibiliBrowserRuntime.browserVersionMatches("Chrome/152.0.7924.0", "152.0.7923.0"))
        assertTrue(BilibiliBrowserRuntime.browserVersionMatches("Chrome/999.0.0.0", null))
    }

    @Test
    fun `platform mapping rejects unsupported linux arm and bsd`() {
        assertEquals("linux64", manager.platformFor("Linux", "amd64"))
        assertEquals("linux64", manager.platformFor("Linux", "x86_64"))
        assertEquals("win64", manager.platformFor("Windows 11", "amd64"))
        assertEquals("mac-arm64", manager.platformFor("Mac OS X", "aarch64"))
        assertEquals("mac-x64", manager.platformFor("Darwin", "x86_64"))
        assertNull(manager.platformFor("Linux", "aarch64"))
        assertNull(manager.platformFor("FreeBSD", "amd64"))
    }

    @Test
    fun `unix mode conversion preserves executable bits`() {
        val permissions = manager.permissionsFromUnixMode(493) // 0755
        assertTrue(PosixFilePermission.OWNER_EXECUTE in permissions)
        assertTrue(PosixFilePermission.GROUP_EXECUTE in permissions)
        assertTrue(PosixFilePermission.OTHERS_EXECUTE in permissions)
        assertTrue(PosixFilePermission.OWNER_WRITE in permissions)
        assertTrue(PosixFilePermission.GROUP_WRITE !in permissions)
    }

    @Test
    fun `extractor restores chrome helper executable modes on posix`() {
        val archive = temporary.resolve("linux-cft.zip")
        ZipArchiveOutputStream(archive).use { zip ->
            listOf("chrome", "chrome_crashpad_handler", "chrome_sandbox", "chrome-wrapper").forEach { name ->
                val entry = ZipArchiveEntry("chrome-linux64/$name").apply {
                    unixMode = UnixStat.FILE_FLAG or 493 // 0755
                }
                zip.putArchiveEntry(entry)
                zip.write("test-$name".toByteArray())
                zip.closeArchiveEntry()
            }
        }
        val destination = temporary.resolve("extracted")

        manager.unzipSafely(archive, destination)

        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            listOf("chrome", "chrome_crashpad_handler", "chrome_sandbox", "chrome-wrapper").forEach { name ->
                val permissions = Files.getPosixFilePermissions(destination.resolve("chrome-linux64/$name"))
                assertTrue(PosixFilePermission.OWNER_EXECUTE in permissions, name)
                assertTrue(PosixFilePermission.GROUP_EXECUTE in permissions, name)
                assertTrue(PosixFilePermission.OTHERS_EXECUTE in permissions, name)
            }
        }
    }

    @Test
    fun `release codebase contains external cft catalog`() {
        val catalog = Path.of("release-template", "Browser", "known-good-versions-with-downloads.json")
        assertTrue(Files.isRegularFile(catalog))
        assertTrue(Files.size(catalog) > 1_000_000)
    }

    @Test
    fun `segmented download uses ranges concurrently and merges exact bytes`() {
        val content = ByteArray(10 * 1024 * 1024) { index -> (index * 31).toByte() }
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val rangeRequests = AtomicInteger()
        val interruptedOnce = AtomicBoolean()
        val resumedRequests = AtomicInteger()
        val executor = Executors.newFixedThreadPool(6)
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = executor
        server.createContext("/chrome.zip") { exchange ->
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.responseHeaders.add("Content-Length", content.size.toString())
            if (exchange.requestMethod.equals("HEAD", true)) {
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
                return@createContext
            }
            val range = exchange.requestHeaders.getFirst("Range")
            val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range.orEmpty())
            if (match == null) {
                exchange.sendResponseHeaders(200, content.size.toLong())
                exchange.responseBody.use { it.write(content) }
                return@createContext
            }
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].toInt().coerceAtMost(content.lastIndex)
            val length = end - start + 1
            if (start !in setOf(0, 4 * 1024 * 1024, 8 * 1024 * 1024)) resumedRequests.incrementAndGet()
            rangeRequests.incrementAndGet()
            val now = active.incrementAndGet()
            maximumActive.accumulateAndGet(now, ::maxOf)
            try {
                Thread.sleep(75)
                exchange.responseHeaders.set("Content-Length", length.toString())
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${content.size}")
                exchange.sendResponseHeaders(206, length.toLong())
                exchange.responseBody.use {
                    if (start == 4 * 1024 * 1024 && interruptedOnce.compareAndSet(false, true)) {
                        it.write(content, start, length / 2)
                    } else {
                        it.write(content, start, length)
                    }
                }
            } finally {
                active.decrementAndGet()
                exchange.close()
            }
        }
        server.start()
        val properties = BilibiliBrowserProperties().apply {
            browserHome = temporary.toString()
            downloadParallelism = 3
            downloadChunkBytes = 4L * 1024 * 1024
            downloadProbeBytes = 256L * 1024
            downloadTimeoutSeconds = 60
        }
        val downloader = ChromeForTestingManager(properties, BilibiliHttpProperties())
        val staging = temporary.resolve("segmented")
        Files.createDirectories(staging)
        val target = staging.resolve("archive.zip")
        try {
            downloader.downloadArchiveForTest(
                "http://127.0.0.1:${server.address.port}/chrome.zip", target, staging
            )
            assertTrue(content.contentEquals(Files.readAllBytes(target)))
            assertTrue(rangeRequests.get() >= 5)
            assertTrue(resumedRequests.get() >= 1)
            assertTrue(maximumActive.get() >= 2)
        } finally {
            downloader.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `runtime close cancels an active browser download and retains resumable staging`() {
        val responseStarted = CountDownLatch(1)
        val serverExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "cft-test-server").apply { isDaemon = true }
        }
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = serverExecutor
        server.createContext("/chrome.zip") { exchange ->
            if (exchange.requestMethod.equals("HEAD", true)) {
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
                return@createContext
            }
            exchange.sendResponseHeaders(200, 0)
            try {
                val block = ByteArray(64 * 1024)
                var firstBlock = true
                while (true) {
                    exchange.responseBody.write(block)
                    exchange.responseBody.flush()
                    if (firstBlock) {
                        firstBlock = false
                        responseStarted.countDown()
                    }
                    Thread.sleep(25)
                }
            } catch (_: Exception) {
                // The client cancellation closes the response stream.
            } finally {
                exchange.close()
            }
        }
        server.start()

        val properties = BilibiliBrowserProperties().apply {
            enabled = true
            browserHome = temporary.toString()
            versionsCatalog = "catalog.json"
            downloadTimeoutSeconds = 60
            installLockTimeoutSeconds = 5
        }
        val url = "http://127.0.0.1:${server.address.port}/chrome.zip"
        val downloads = mutableListOf("win64", "win32", "linux64", "mac-arm64", "mac-x64")
            .mapTo(mutableListOf()) { ChromeForTestingDownload(it, url) }
        val catalog = ChromeForTestingCatalog(versions = mutableListOf(
            ChromeForTestingVersion("152.0.0.0", "test", linkedMapOf("chrome" to downloads)),
        ))
        Files.writeString(temporary.resolve(properties.versionsCatalog), JSON.toJSONString(catalog))
        val cancellableManager = ChromeForTestingManager(properties, BilibiliHttpProperties())
        val credentialProperties = BilibiliCredentialProperties().apply {
            credentialFile = temporary.resolve("credential.json").toString()
            legacyCookieFile = temporary.resolve("cookies.json").toString()
        }
        val runtime = BilibiliBrowserRuntime(
            properties,
            BilibiliHttpProperties(),
            cancellableManager,
            BilibiliCredentialFileStore(credentialProperties),
        )

        try {
            val task = CompletableFuture.supplyAsync {
                runtime.start("Mozilla/5.0 Chrome/152.0.0.0 Safari/537.36")
            }
            assertTrue(responseStarted.await(5, TimeUnit.SECONDS), "test download did not start")

            val startedAt = System.nanoTime()
            runtime.close()
            val result = task.get(3, TimeUnit.SECONDS)
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertNull(result)
            assertTrue(elapsedMillis < 3_000, "download cancellation took ${elapsedMillis}ms")
            val staging = temporary.resolve(".staging")
            assertTrue(Files.isDirectory(staging))
            assertTrue(Files.walk(staging).use { paths ->
                paths.anyMatch(Files::isRegularFile)
            })
        } finally {
            runtime.close()
            server.stop(0)
            serverExecutor.shutdownNow()
        }
    }

    private fun version(value: String) = ChromeForTestingVersion(
        version = value,
        downloads = linkedMapOf("chrome" to mutableListOf(ChromeForTestingDownload("win64", "https://example.invalid/$value.zip")))
    )
}
