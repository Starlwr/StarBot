package com.starlwr.bot.bilibili.browser

import com.alibaba.fastjson2.JSON
import com.starlwr.bot.bilibili.http.BilibiliHttpProperties
import com.starlwr.bot.bilibili.http.configuredProxySelector
import com.starlwr.bot.core.plugin.StarBotComponent
import jakarta.annotation.PreDestroy
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipFile
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@ConfigurationProperties("starbot.bilibili.browser")
class BilibiliBrowserProperties {
    var enabled: Boolean = false
    var browserHome: String = "./Browser"
    var profileDirectory: String = "./config/BrowserProfile"
    var executable: String? = null
    var currentManifest: String = "current.json"
    var versionsCatalog: String = "known-good-versions-with-downloads.json"
    var downloadEnabled: Boolean = true
    var catalogEndpoint: String = KNOWN_GOOD_ENDPOINT
    var allowSystemChrome: Boolean = true
    var allowSystemChromium: Boolean = true
    var allowEdge: Boolean = false
    var allowAddressFamilySplit: Boolean = false
    var startMinimized: Boolean = true
    var headless: Boolean = false
    var credentialSyncMode: String = "jvm-authoritative"
    var credentialAuditIntervalSeconds: Long = 90
    var downloadConnectTimeoutSeconds: Long = 15
    var downloadTimeoutSeconds: Long = 300
    var downloadProxyUri: String? = null
    var downloadHttpVersion: String = "auto"
    var downloadParallelism: Int = 4
    var downloadChunkBytes: Long = 8L * 1024 * 1024
    var downloadProbeBytes: Long = 2L * 1024 * 1024
    var downloadRetryMaxAttempts: Int = 5
    var maximumArchiveBytes: Long = 1_500L * 1024 * 1024
    var maximumExtractedBytes: Long = 3_000L * 1024 * 1024
    var installLockTimeoutSeconds: Long = 120

    companion object {
        const val KNOWN_GOOD_ENDPOINT =
            "https://googlechromelabs.github.io/chrome-for-testing/known-good-versions-with-downloads.json"
        const val BROWSER_RESOLUTION = "1920x1080"
    }
}

data class ChromeForTestingCatalog(
    var timestamp: String = "",
    var versions: MutableList<ChromeForTestingVersion> = mutableListOf(),
)

data class ChromeForTestingVersion(
    var version: String = "",
    var revision: String = "",
    var downloads: MutableMap<String, MutableList<ChromeForTestingDownload>> = linkedMapOf(),
)

data class ChromeForTestingDownload(var platform: String = "", var url: String = "")

data class ChromeInstallManifest(
    var version: String = "",
    var revision: String = "",
    var platform: String = "",
    var executable: String = "",
    var sourceUrl: String = "",
    var archiveSha256: String = "",
    var installedAtEpochMillis: Long = 0,
)

data class BrowserExecutable(
    val path: Path,
    val source: String,
    val expectedVersion: String? = null,
    val revision: String? = null,
)

private data class RemoteArchiveMetadata(
    val length: Long,
    val rangeSupported: Boolean,
    val responseVersion: HttpClient.Version,
)

private data class DownloadChunk(val index: Int, val start: Long, val end: Long, val path: Path) {
    val length: Long get() = end - start + 1
}

private data class ProtocolProbe(val version: HttpClient.Version, val bytesPerSecond: Long)

private data class DownloadState(val url: String, val totalLength: Long, val chunkBytes: Long)

/** Offline-first, version-preserving Chrome for Testing resolver and installer. */
@StarBotComponent
@EnableConfigurationProperties(BilibiliBrowserProperties::class)
class ChromeForTestingManager(
    private val properties: BilibiliBrowserProperties,
    private val httpProperties: BilibiliHttpProperties,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val proxySelector = configuredProxySelector(properties.downloadProxyUri ?: httpProperties.proxyUri)
    private val http11 = buildHttpClient(HttpClient.Version.HTTP_1_1)
    private val http2 = buildHttpClient(HttpClient.Version.HTTP_2)
    private val downloadThreadSequence = AtomicInteger()
    private val downloadExecutor: ExecutorService = Executors.newFixedThreadPool(
        properties.downloadParallelism.coerceIn(1, 8)
    ) { runnable ->
        Thread(runnable, "cft-download-${downloadThreadSequence.incrementAndGet()}").apply { isDaemon = true }
    }
    private val failedExecutables = ConcurrentHashMap.newKeySet<Path>()
    private val closed = AtomicBoolean()
    private val activeRequests = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
    private val activeVerificationProcess = AtomicReference<Process?>()

    private fun buildHttpClient(version: HttpClient.Version): HttpClient = HttpClient.newBuilder()
        .version(version)
        .connectTimeout(Duration.ofSeconds(properties.downloadConnectTimeoutSeconds.coerceAtLeast(1)))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .apply { proxySelector?.let(::proxy) }
        .build()

    fun resolve(userAgent: String): BrowserExecutable? {
        if (!properties.enabled || closed.get()) return null
        properties.executable?.takeIf { it.isNotBlank() }?.let { configured ->
            val candidate = BrowserExecutable(Path.of(configured).toAbsolutePath().normalize(), "configured")
            if (candidate.path !in failedExecutables) return candidate
        }
        readCurrent()?.takeIf { it.path !in failedExecutables }?.let { return it }
        findInstalled(userAgent)?.let { return it }
        if (properties.downloadEnabled) {
            runCatching { installForUserAgent(userAgent) }
                .onFailure {
                    if (!closed.get() && it !is CancellationException) {
                        log.warn("Chrome for Testing 按需安装失败，将尝试系统浏览器: {}", it.toString())
                    }
                }
                .getOrNull()?.let { return it }
        }
        if (closed.get()) return null
        findSystemChrome()?.let { return it }
        log.warn("未找到可用的 Chrome for Testing/Chromium；浏览器 Resolver 将不可用，JVM 与直播连接继续运行")
        return null
    }

    fun reportLaunchFailure(executable: BrowserExecutable, error: Throwable? = null) {
        failedExecutables.add(executable.path.toAbsolutePath().normalize())
        log.warn("Browser launch failed; this process will skip the candidate and continue fallback: source={}, executable={}, error={}",
            executable.source, executable.path, error?.toString().orEmpty())
    }

    internal fun selectVersion(catalog: ChromeForTestingCatalog, requested: String, platform: String): ChromeForTestingVersion? {
        val wanted = FourPartVersion.parse(requested) ?: return null
        val candidates = catalog.versions.asSequence()
            .filter { version -> version.downloads["chrome"].orEmpty().any { it.platform == platform } }
            .mapNotNull { entry -> FourPartVersion.parse(entry.version)?.let { it to entry } }
            .filter { (version, _) -> version.major == wanted.major }
            .sortedBy { it.first }
            .toList()
        return candidates.firstOrNull { it.first == wanted }?.second
            ?: candidates.firstOrNull { it.first > wanted }?.second
    }

    fun installForUserAgent(userAgent: String): BrowserExecutable? {
        ensureOpen()
        val requested = requestedChromeVersion(userAgent) ?: run {
            log.warn("UA 不包含可解析的 Chrome 完整版本，跳过 CfT 自动安装: {}", userAgent)
            return null
        }
        val platform = currentPlatform() ?: return null
        val home = browserHome()
        Files.createDirectories(home)
        val lockPath = home.resolve(".install.lock")
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = acquireLock(channel) ?: error("等待 CfT 安装锁超时")
            lock.use {
                ensureOpen()
                readCurrent()?.takeIf { it.path !in failedExecutables }?.let { return it }
                findInstalled(userAgent)?.let { return it }
                val catalog = loadCatalogForDownload()
                val selected = selectVersion(catalog, requested, platform)
                    ?: error("离线 CfT 清单中没有 Chrome/$requested 的相同或更高同 major $platform 版本；不会跨 major 或自动刷新清单")
                return install(selected, platform)
            }
        }
    }

    private fun install(version: ChromeForTestingVersion, platform: String): BrowserExecutable {
        ensureOpen()
        val download = version.downloads["chrome"].orEmpty().firstOrNull { it.platform == platform }
            ?: error("CfT ${version.version} 不提供 $platform chrome 下载")
        val home = browserHome()
        val finalDirectory = home.resolve("ChromeForTesting").resolve(version.version).resolve(platform).normalize()
        val relativeExecutable = executableRelativePath(platform)
        val finalExecutable = finalDirectory.resolve(relativeExecutable).normalize()
        if (Files.isRegularFile(finalExecutable)) {
            require(finalExecutable.toAbsolutePath().normalize() !in failedExecutables) {
                "Previously failed CfT candidate is not reused during this process: $finalExecutable"
            }
            ensureRuntimePermissions(finalDirectory, platform)
            val resolved = BrowserExecutable(finalExecutable, "cft-installed", version.version, version.revision)
            writeCurrent(resolved, version, platform, download.url, "")
            return resolved
        }

        val stagingRoot = home.resolve(".staging").normalize()
        Files.createDirectories(stagingRoot)
        val staging = stagingRoot.resolve("${version.version}-$platform").normalize()
        require(staging.startsWith(stagingRoot))
        Files.createDirectories(staging)
        val archive = staging.resolve("chrome.zip.part")
        var installationCompleted = false
        try {
            downloadArchive(download.url, archive, staging)
            require(Files.size(archive) in 1..properties.maximumArchiveBytes) { "CfT archive 大小异常" }
            ensureOpen()
            force(archive)
            val archiveHash = sha256(archive)
            val extracted = staging.resolve("extracted")
            unzipSafely(archive, extracted)
            val sourceExecutable = extracted.resolve(relativeExecutable).normalize()
            require(sourceExecutable.startsWith(extracted) && Files.isRegularFile(sourceExecutable)) {
                "CfT archive 缺少 ${relativeExecutable.toString().replace('\\', '/')}"
            }
            ensureRuntimePermissions(extracted, platform)
            verifyVersion(sourceExecutable, version.version)
            Files.createDirectories(finalDirectory.parent)
            moveDirectory(extracted, finalDirectory)
            ensureRuntimePermissions(finalDirectory, platform)
            val resolved = BrowserExecutable(finalExecutable, "cft-downloaded", version.version, version.revision)
            writeCurrent(resolved, version, platform, download.url, archiveHash)
            installationCompleted = true
            return resolved
        } finally {
            if (installationCompleted) {
                deleteTree(staging, stagingRoot)
            } else if (Files.exists(staging)) {
                log.info("CfT 安装未完成，保留 staging 供续传或诊断: {}", staging)
            }
        }
    }

    private fun downloadArchive(url: String, archive: Path, staging: Path) {
        Files.deleteIfExists(archive)
        val metadata = runCatching { inspectArchive(url) }.getOrElse { error ->
            if (closed.get() || error is CancellationException || error is InterruptedException) throw error
            log.warn("CfT archive HEAD 探测失败，将退回单流下载: {}", error.toString())
            RemoteArchiveMetadata(-1, false, configuredDownloadVersion() ?: HttpClient.Version.HTTP_2)
        }
        require(metadata.length <= 0 || metadata.length <= properties.maximumArchiveBytes) {
            "CfT archive Content-Length 超限: ${metadata.length}"
        }
        val parallelism = properties.downloadParallelism.coerceIn(1, 8)
        val chunkBytes = properties.downloadChunkBytes.coerceIn(4L * 1024 * 1024, 256L * 1024 * 1024)
        val canSegment = metadata.length > chunkBytes && metadata.rangeSupported && parallelism > 1
        val protocol = selectDownloadProtocol(url, metadata, staging)
        log.info(
            "CfT 下载准备完成: protocol={}, contentLength={}, rangeSupported={}, parallelism={}, segmented={}",
            protocol, metadata.length, metadata.rangeSupported, parallelism, canSegment
        )
        if (canSegment) {
            downloadSegmented(url, archive, staging.resolve("chunks"), metadata.length, chunkBytes, protocol)
        } else {
            downloadSingle(url, archive, protocol)
        }
    }

    internal fun downloadArchiveForTest(url: String, archive: Path, staging: Path) =
        downloadArchive(url, archive, staging)

    private fun inspectArchive(url: String): RemoteArchiveMetadata {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(downloadTimeout())
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build()
        val response = sendWithConfiguredFallback(request, HttpResponse.BodyHandlers.discarding())
        require(response.statusCode() in 200..299) { "CfT archive HEAD HTTP ${response.statusCode()}" }
        val length = response.headers().firstValueAsLong("content-length").orElse(-1)
        val ranges = response.headers().firstValue("accept-ranges").orElse("").equals("bytes", true)
        return RemoteArchiveMetadata(length, ranges, response.version())
    }

    private fun selectDownloadProtocol(
        url: String,
        metadata: RemoteArchiveMetadata,
        staging: Path,
    ): HttpClient.Version {
        configuredDownloadVersion()?.let { return it }
        if (!metadata.rangeSupported || metadata.length <= 0) return metadata.responseVersion
        val probeBytes = properties.downloadProbeBytes.coerceIn(256L * 1024, 8L * 1024 * 1024)
            .coerceAtMost(metadata.length)
        val probes = listOf(HttpClient.Version.HTTP_2, HttpClient.Version.HTTP_1_1).mapNotNull { version ->
            probeProtocol(url, staging.resolve("probe-${version.name}.part"), probeBytes, version)
        }
        val selected = probes.maxByOrNull { it.bytesPerSecond }?.version ?: metadata.responseVersion
        if (probes.isNotEmpty()) {
            log.info("CfT 下载协议探测完成: results={}, selected={}",
                probes.joinToString { "${it.version}=${it.bytesPerSecond}B/s" }, selected)
        }
        return selected
    }

    private fun probeProtocol(url: String, target: Path, bytes: Long, version: HttpClient.Version): ProtocolProbe? {
        Files.deleteIfExists(target)
        return try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .header("Range", "bytes=0-${bytes - 1}")
                .timeout(probeTimeout())
                .GET().build()
            val started = System.nanoTime()
            val response = sendCancellable(client(version), request, HttpResponse.BodyHandlers.ofFile(target))
            val elapsedNanos = (System.nanoTime() - started).coerceAtLeast(1)
            if (response.statusCode() != 206 || Files.size(target) != bytes) return null
            ProtocolProbe(response.version(), (bytes * 1_000_000_000L / elapsedNanos).coerceAtLeast(1))
        } catch (error: Exception) {
            log.debug("CfT 下载协议探测失败: protocol={}, error={}", version, error.toString())
            null
        } finally {
            Files.deleteIfExists(target)
        }
    }

    private fun downloadSegmented(
        url: String,
        archive: Path,
        chunkDirectory: Path,
        totalLength: Long,
        chunkBytes: Long,
        protocol: HttpClient.Version,
    ) {
        prepareChunkDirectory(chunkDirectory, url, totalLength, chunkBytes)
        val chunks = generateSequence(0L) { previous -> (previous + chunkBytes).takeIf { it < totalLength } }
            .mapIndexed { index, start ->
                DownloadChunk(index, start, minOf(start + chunkBytes - 1, totalLength - 1),
                    chunkDirectory.resolve("%05d.part".format(index)))
            }.toList()
        val futures = ArrayList<Future<DownloadChunk>>(chunks.size)
        try {
            chunks.forEach { chunk ->
                futures += downloadExecutor.submit<DownloadChunk> {
                    downloadChunk(url, chunk, totalLength, protocol)
                    chunk
                }
            }
            var completedBytes = 0L
            futures.forEach { future ->
                val chunk = future.get()
                completedBytes += chunk.length
                log.info("CfT 分片下载完成: part={}/{}, bytes={}, progress={}/{}",
                    chunk.index + 1, chunks.size, chunk.length, completedBytes, totalLength)
            }
            Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW).use { output ->
                chunks.forEach { chunk ->
                    Files.newInputStream(chunk.path).use { it.transferTo(output) }
                }
            }
            require(Files.size(archive) == totalLength) {
                "CfT 分片合并大小异常: expected=$totalLength actual=${Files.size(archive)}"
            }
        } catch (error: Exception) {
            futures.forEach { it.cancel(true) }
            throw when (error) {
                is ExecutionException -> error.cause ?: error
                else -> error
            }
        }
    }

    private fun prepareChunkDirectory(chunkDirectory: Path, url: String, totalLength: Long, chunkBytes: Long) {
        val state = DownloadState(url, totalLength, chunkBytes)
        val expected = JSON.toJSONString(state)
        val statePath = chunkDirectory.resolve("download-state.json")
        val existing = runCatching { Files.readString(statePath, StandardCharsets.UTF_8) }.getOrNull()
        if (existing != null && existing != expected) {
            deleteTree(chunkDirectory, chunkDirectory.parent)
        }
        Files.createDirectories(chunkDirectory)
        if (!Files.isRegularFile(statePath)) {
            Files.writeString(statePath, expected, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
            force(statePath)
        }
    }

    private fun downloadChunk(
        url: String,
        chunk: DownloadChunk,
        totalLength: Long,
        preferred: HttpClient.Version,
    ) {
        val maxAttempts = properties.downloadRetryMaxAttempts.coerceIn(1, 5)
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            ensureOpen()
            Files.createDirectories(chunk.path.parent)
            var existing = if (Files.isRegularFile(chunk.path)) Files.size(chunk.path) else 0L
            if (existing !in 0..chunk.length) {
                Files.deleteIfExists(chunk.path)
                existing = 0L
            }
            if (existing == chunk.length) return
            val requestStart = chunk.start + existing
            val protocol = if (configuredDownloadVersion() != null || attempt % 2 == 0) preferred else alternate(preferred)
            try {
                val request = HttpRequest.newBuilder(URI.create(url))
                    .header("Range", "bytes=$requestStart-${chunk.end}")
                    .timeout(downloadTimeout())
                    .GET().build()
                val response = sendCancellable(client(protocol), request, HttpResponse.BodyHandlers.ofFile(
                    chunk.path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND
                ))
                if (response.statusCode() != 206 || !validContentRange(response, requestStart, chunk.end, totalLength)) {
                    Files.deleteIfExists(chunk.path)
                    error("CfT 分片响应无效: status=${response.statusCode()}, contentRange=${response.headers().firstValue("content-range").orElse("")}")
                }
                require(Files.size(chunk.path) == chunk.length) {
                    "CfT 分片大小异常: expected=${chunk.length} actual=${Files.size(chunk.path)}"
                }
                return
            } catch (error: Exception) {
                if (closed.get() || error is CancellationException || error is InterruptedException) throw error
                lastError = error
                if (attempt + 1 < maxAttempts) {
                    val retained = runCatching { Files.size(chunk.path) }.getOrDefault(0L)
                    log.warn("CfT 分片下载失败，将续传: part={}, attempt={}/{}, protocol={}, retained={}/{}, error={}",
                        chunk.index + 1, attempt + 1, maxAttempts, protocol, retained, chunk.length, error.toString())
                }
            }
        }
        if (Files.isRegularFile(chunk.path) && Files.size(chunk.path) == chunk.length) return
        throw IllegalStateException("CfT 分片下载失败: part=${chunk.index + 1}", lastError)
    }

    private fun validContentRange(
        response: HttpResponse<*>,
        expectedStart: Long,
        expectedEnd: Long,
        totalLength: Long,
    ): Boolean {
        val value = response.headers().firstValue("content-range").orElse("")
        val match = CONTENT_RANGE.matchEntire(value.trim()) ?: return false
        return match.groupValues[1].toLongOrNull() == expectedStart &&
            match.groupValues[2].toLongOrNull() == expectedEnd &&
            match.groupValues[3].toLongOrNull() == totalLength
    }

    private fun downloadSingle(url: String, archive: Path, protocol: HttpClient.Version) {
        val maxAttempts = properties.downloadRetryMaxAttempts.coerceIn(1, 5)
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            ensureOpen()
            Files.deleteIfExists(archive)
            val selected = if (attempt == 0 || configuredDownloadVersion() != null) protocol else alternate(protocol)
            val started = Instant.now()
            try {
                val request = HttpRequest.newBuilder(URI.create(url)).timeout(downloadTimeout()).GET().build()
                val response = sendCancellable(client(selected), request, HttpResponse.BodyHandlers.ofFile(archive))
                require(response.statusCode() in 200..299) { "CfT 下载 HTTP ${response.statusCode()}" }
                log.info("CfT 单流下载完成: protocol={}, bytes={}, elapsedMs={}", response.version(), Files.size(archive),
                    Duration.between(started, Instant.now()).toMillis())
                return
            } catch (error: Exception) {
                if (closed.get() || error is CancellationException || error is InterruptedException) throw error
                lastError = error
                Files.deleteIfExists(archive)
                if (attempt + 1 < maxAttempts) {
                    log.warn("CfT 单流下载失败，将重试: attempt={}/{}, protocol={}, error={}",
                        attempt + 1, maxAttempts, selected, error.toString())
                }
            }
        }
        throw IllegalStateException("CfT 单流下载失败", lastError)
    }

    private fun loadCatalogForDownload(): ChromeForTestingCatalog {
        val catalogPath = browserHome().resolve(properties.versionsCatalog).normalize()
        if (!Files.isRegularFile(catalogPath)) {
            log.warn("RuntimeBase 缺少 CfT 离线清单，将尝试官方端点: {}", catalogPath)
            downloadCatalog(catalogPath)
        }
        val catalog = JSON.parseObject(Files.readString(catalogPath, StandardCharsets.UTF_8), ChromeForTestingCatalog::class.java)
            ?: error("CfT versions catalog 为空")
        require(catalog.versions.isNotEmpty()) { "CfT versions catalog 不含 versions" }
        return catalog
    }

    private fun downloadCatalog(target: Path) {
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling("${target.fileName}.tmp-${UUID.randomUUID()}")
        try {
            val request = HttpRequest.newBuilder(URI.create(properties.catalogEndpoint))
                .timeout(downloadTimeout()).GET().build()
            var response: HttpResponse<Path>? = null
            var lastError: Throwable? = null
            for (protocol in configuredProtocols()) {
                Files.deleteIfExists(temporary)
                try {
                    response = sendCancellable(client(protocol), request, HttpResponse.BodyHandlers.ofFile(temporary))
                    if (response.statusCode() in 200..299) break
                    lastError = IllegalStateException("CfT catalog HTTP ${response.statusCode()}")
                } catch (error: Exception) {
                    if (closed.get() || error is CancellationException || error is InterruptedException) throw error
                    lastError = error
                }
            }
            val completed = response?.takeIf { it.statusCode() in 200..299 }
                ?: throw IllegalStateException("CfT catalog 下载失败", lastError)
            val parsed = JSON.parseObject(Files.readString(temporary, StandardCharsets.UTF_8), ChromeForTestingCatalog::class.java)
            require(parsed != null && parsed.versions.isNotEmpty()) { "CfT catalog 结构无效" }
            force(temporary)
            atomicMove(temporary, target)
            log.info("RuntimeBase CfT 清单已从官方端点原子保存: path={}, protocol={}", target, completed.version())
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun readCurrent(): BrowserExecutable? {
        val path = browserHome().resolve(properties.currentManifest)
        if (!Files.isRegularFile(path)) return null
        val manifest = runCatching { JSON.parseObject(Files.readString(path), ChromeInstallManifest::class.java) }.getOrNull()
            ?: return null
        val platform = currentPlatform() ?: return null
        if (manifest.executable.isBlank() || manifest.platform != platform) return null
        val executable = browserHome().resolve(manifest.executable).normalize()
        if (!Files.isRegularFile(executable)) return null
        val installRoot = browserHome().resolve("ChromeForTesting").resolve(manifest.version).resolve(platform).normalize()
        if (runCatching { ensureRuntimePermissions(installRoot, platform) }.isFailure) return null
        // Normal startup deliberately does not hash or execute --version. Launch failure
        // is the trigger for repair/fallback.
        return BrowserExecutable(executable, "cft-current", manifest.version, manifest.revision)
    }

    private fun findInstalled(userAgent: String): BrowserExecutable? {
        val requested = requestedChromeVersion(userAgent) ?: return null
        val platform = currentPlatform() ?: return null
        val root = browserHome().resolve("ChromeForTesting")
        if (!Files.isDirectory(root)) return null
        val wanted = FourPartVersion.parse(requested) ?: return null
        val entries = Files.list(root).use { stream ->
            stream.iterator().asSequence().filter { Files.isDirectory(it) }.mapNotNull { path ->
                FourPartVersion.parse(path.fileName.toString())?.let { it to path }
            }.toList()
        }.filter { it.first.major == wanted.major && it.first >= wanted }.sortedBy { it.first }
        for ((version, path) in entries) {
            val installRoot = path.resolve(platform)
            val executable = installRoot.resolve(executableRelativePath(platform))
            if (Files.isRegularFile(executable) && executable.toAbsolutePath().normalize() !in failedExecutables) {
                if (runCatching { ensureRuntimePermissions(installRoot, platform) }.isFailure) continue
                return BrowserExecutable(executable, "cft-installed", version.toString())
            }
        }
        return null
    }

    private fun findSystemChrome(): BrowserExecutable? {
        val env = System.getenv()
        val candidates = mutableListOf<Path>()
        if (isWindows()) {
            listOfNotNull(env["ProgramFiles"], env["ProgramFiles(x86)"], env["LOCALAPPDATA"]).forEach { base ->
                if (properties.allowSystemChrome) candidates.add(Path.of(base, "Google", "Chrome", "Application", "chrome.exe"))
                if (properties.allowEdge) candidates.add(Path.of(base, "Microsoft", "Edge", "Application", "msedge.exe"))
            }
        } else if (isMac()) {
            if (properties.allowSystemChrome) candidates.add(Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"))
            if (properties.allowSystemChromium) candidates.add(Path.of("/Applications/Chromium.app/Contents/MacOS/Chromium"))
        } else {
            if (properties.allowSystemChrome) candidates.addAll(listOf(Path.of("/usr/bin/google-chrome"), Path.of("/usr/bin/google-chrome-stable")))
            if (properties.allowSystemChromium) candidates.addAll(listOf(Path.of("/usr/bin/chromium"), Path.of("/usr/bin/chromium-browser")))
        }
        return candidates.firstOrNull {
            Files.isRegularFile(it) && it.toAbsolutePath().normalize() !in failedExecutables
        }?.let { BrowserExecutable(it, "system") }
    }

    private fun writeCurrent(executable: BrowserExecutable, version: ChromeForTestingVersion, platform: String, url: String, hash: String) {
        val home = browserHome()
        val manifest = ChromeInstallManifest(
            version.version, version.revision, platform, home.relativize(executable.path).toString(),
            url, hash, System.currentTimeMillis()
        )
        val target = home.resolve(properties.currentManifest)
        val temporary = target.resolveSibling("${target.fileName}.tmp-${UUID.randomUUID()}")
        try {
            Files.writeString(temporary, JSON.toJSONString(manifest), StandardCharsets.UTF_8)
            force(temporary)
            atomicMove(temporary, target)
        } finally { Files.deleteIfExists(temporary) }
    }

    internal fun unzipSafely(archive: Path, destination: Path) {
        Files.createDirectories(destination)
        var total = 0L
        val directoryModes = ArrayList<Pair<Path, Int>>()
        val symlinks = ArrayList<Pair<Path, String>>()
        ZipFile.builder().setPath(archive).get().use { zip ->
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                ensureOpen()
                val entry = entries.nextElement()
                val output = destination.resolve(entry.name).normalize()
                require(output.startsWith(destination)) { "CfT ZIP entry 越界: ${entry.name}" }
                when {
                    entry.isDirectory -> {
                        Files.createDirectories(output)
                        directoryModes += output to entry.unixMode
                    }
                    entry.isUnixSymlink -> {
                        Files.createDirectories(output.parent)
                        val target = zip.getUnixSymlink(entry) ?: error("CfT ZIP 符号链接目标为空: ${entry.name}")
                        val targetPath = Path.of(target)
                        require(!targetPath.isAbsolute && output.parent.resolve(targetPath).normalize().startsWith(destination)) {
                            "CfT ZIP 符号链接越界: ${entry.name} -> $target"
                        }
                        symlinks += output to target
                    }
                    else -> {
                    Files.createDirectories(output.parent)
                    zip.getInputStream(entry).use { input ->
                        Files.newOutputStream(output, StandardOpenOption.CREATE_NEW).use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureOpen()
                                val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= properties.maximumExtractedBytes) { "CfT 解压总大小超限" }
                            stream.write(buffer, 0, read)
                        }
                    }
                }
                        applyUnixMode(output, entry.unixMode)
                    }
                }
            }
        }
        symlinks.sortedByDescending { it.first.nameCount }.forEach { (output, target) ->
            Files.createSymbolicLink(output, Path.of(target))
        }
        directoryModes.sortedByDescending { it.first.nameCount }.forEach { (path, mode) -> applyUnixMode(path, mode) }
    }

    private fun applyUnixMode(path: Path, unixMode: Int) {
        val mode = unixMode and UnixStat.PERM_MASK
        if (mode == 0) return
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, permissionsFromUnixMode(mode))
        } else if (mode and EXECUTE_MASK != 0) {
            path.toFile().setExecutable(true, false)
        }
    }

    internal fun permissionsFromUnixMode(mode: Int): Set<PosixFilePermission> {
        val result = linkedSetOf<PosixFilePermission>()
        POSIX_PERMISSION_BITS.forEach { (bit, permission) -> if (mode and bit != 0) result += permission }
        return result
    }

    private fun ensureRuntimePermissions(installRoot: Path, platform: String) {
        requiredExecutables(platform).forEach { relative ->
            val executable = installRoot.resolve(relative).normalize()
            require(executable.startsWith(installRoot) && Files.isRegularFile(executable)) {
                "CfT 安装缺少必要可执行文件: ${relative.toString().replace('\\', '/')}"
            }
            if (!isWindows()) makeExecutable(executable)
            require(Files.isExecutable(executable)) { "CfT 文件不可执行: $executable" }
        }
    }

    private fun makeExecutable(path: Path) {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            val permissions = Files.getPosixFilePermissions(path).toMutableSet()
            permissions += setOf(
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_EXECUTE,
            )
            Files.setPosixFilePermissions(path, permissions)
        } else {
            require(path.toFile().setExecutable(true, false) || Files.isExecutable(path)) {
                "无法设置 CfT 执行权限: $path"
            }
        }
    }

    private fun requiredExecutables(platform: String): List<Path> = when (platform) {
        "linux64" -> listOf(
            Path.of("chrome-linux64", "chrome"),
            Path.of("chrome-linux64", "chrome_crashpad_handler"),
            Path.of("chrome-linux64", "chrome_sandbox"),
            Path.of("chrome-linux64", "chrome-wrapper"),
        )
        else -> listOf(executableRelativePath(platform))
    }

    private fun verifyVersion(executable: Path, expected: String) {
        ensureOpen()
        // chrome.exe treats --version as a normal GUI launch on Windows and may
        // never terminate. The runtime validates the full version through CDP.
        if (isWindows()) {
            require(executable.fileName.toString().equals("chrome.exe", true) && Files.size(executable) > 0) {
                "CfT Windows executable is invalid: $executable"
            }
            return
        }
        val process = ProcessBuilder(executable.toString(), "--version").redirectErrorStream(true).start()
        activeVerificationProcess.set(process)
        if (closed.get()) process.destroyForcibly()
        try {
            require(process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); "CfT --version 超时" }
            ensureOpen()
            val output = process.inputStream.bufferedReader().readText()
            require(process.exitValue() == 0 && output.contains(expected)) {
                "CfT 二进制版本不匹配: expected=$expected actual=${output.trim()}"
            }
        } finally {
            activeVerificationProcess.compareAndSet(process, null)
        }
    }

    private fun acquireLock(channel: FileChannel): FileLock? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(properties.installLockTimeoutSeconds.coerceAtLeast(1))
        while (System.nanoTime() < deadline) {
            ensureOpen()
            runCatching { channel.tryLock() }.getOrNull()?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    private fun moveDirectory(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun atomicMove(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deleteTree(target: Path, allowedRoot: Path) {
        if (!target.normalize().startsWith(allowedRoot.normalize()) || !Files.exists(target)) return
        var lastError: Exception? = null
        repeat(20) {
            try {
                if (Files.exists(target)) {
                    Files.walk(target).use { paths ->
                        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                    }
                }
                return
            } catch (error: Exception) {
                lastError = error
                Thread.sleep(50)
            }
        }
        log.warn("Unable to completely remove Chrome for Testing staging directory: {}", target, lastError)
    }

    private fun force(path: Path) = FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }
    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                ensureOpen()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun client(version: HttpClient.Version): HttpClient = when (version) {
        HttpClient.Version.HTTP_1_1 -> http11
        HttpClient.Version.HTTP_2 -> http2
    }

    private fun alternate(version: HttpClient.Version): HttpClient.Version = when (version) {
        HttpClient.Version.HTTP_1_1 -> HttpClient.Version.HTTP_2
        HttpClient.Version.HTTP_2 -> HttpClient.Version.HTTP_1_1
    }

    private fun configuredDownloadVersion(): HttpClient.Version? = when (
        properties.downloadHttpVersion.trim().lowercase(Locale.ROOT).replace('-', '_')
    ) {
        "", "auto" -> null
        "http_1_1", "http1_1", "http1.1" -> HttpClient.Version.HTTP_1_1
        "http_2", "http2", "h2" -> HttpClient.Version.HTTP_2
        else -> error("不支持的 CfT download-http-version: ${properties.downloadHttpVersion}")
    }

    private fun configuredProtocols(): List<HttpClient.Version> = configuredDownloadVersion()?.let(::listOf)
        ?: listOf(HttpClient.Version.HTTP_2, HttpClient.Version.HTTP_1_1)

    private fun downloadTimeout(): Duration = Duration.ofSeconds(properties.downloadTimeoutSeconds.coerceAtLeast(30))
    private fun probeTimeout(): Duration = Duration.ofSeconds(properties.downloadTimeoutSeconds.coerceIn(10, 30))

    private fun <T> sendWithConfiguredFallback(
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        var lastError: Throwable? = null
        for (protocol in configuredProtocols()) {
            try {
                return sendCancellable(client(protocol), request, handler)
            } catch (error: Exception) {
                if (closed.get() || error is CancellationException || error is InterruptedException) throw error
                lastError = error
                log.debug("CfT HTTP 请求失败，尝试备用协议: protocol={}, error={}", protocol, error.toString())
            }
        }
        throw IllegalStateException("CfT HTTP 请求失败", lastError)
    }

    private fun <T> sendCancellable(
        client: HttpClient,
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        ensureOpen()
        val future = client.sendAsync(request, handler)
        activeRequests.add(future)
        if (closed.get()) future.cancel(true)
        try {
            return future.get()
        } catch (error: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw CancellationException("Chrome for Testing request interrupted").also { it.initCause(error) }
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } finally {
            activeRequests.remove(future)
        }
    }

    private fun ensureOpen() {
        if (closed.get()) throw CancellationException("Chrome for Testing manager is shutting down")
    }

    @PreDestroy
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeRequests.forEach { it.cancel(true) }
        downloadExecutor.shutdownNow()
        activeVerificationProcess.getAndSet(null)?.destroyForcibly()
    }

    private fun browserHome() = Path.of(properties.browserHome).toAbsolutePath().normalize()
    private fun requestedChromeVersion(ua: String) = CHROME_VERSION.find(ua)?.groupValues?.get(1)
    private fun currentPlatform(): String? = platformFor(System.getProperty("os.name"), System.getProperty("os.arch"))
        ?: null.also { log.warn("CfT 不支持当前平台: {} {}", System.getProperty("os.name"), System.getProperty("os.arch")) }
    internal fun platformFor(osName: String, architecture: String): String? {
        val os = osName.lowercase(Locale.ROOT)
        val arch = architecture.lowercase(Locale.ROOT)
        val windows = os.startsWith("windows")
        val mac = "mac" in os || "darwin" in os
        return when {
            windows && arch in setOf("amd64", "x86_64", "x64") -> "win64"
            windows && arch in setOf("x86", "i386", "i486", "i586", "i686") -> "win32"
            mac && arch in setOf("aarch64", "arm64") -> "mac-arm64"
            mac && arch in setOf("amd64", "x86_64", "x64") -> "mac-x64"
            "linux" in os && arch in setOf("amd64", "x86_64", "x64") -> "linux64"
            else -> null
        }
    }
    private fun executableRelativePath(platform: String): Path = when {
        platform.startsWith("win") -> Path.of("chrome-$platform", "chrome.exe")
        platform == "linux64" -> Path.of("chrome-linux64", "chrome")
        platform == "mac-arm64" -> Path.of("chrome-mac-arm64", "Google Chrome for Testing.app", "Contents", "MacOS", "Google Chrome for Testing")
        else -> Path.of("chrome-mac-x64", "Google Chrome for Testing.app", "Contents", "MacOS", "Google Chrome for Testing")
    }
    private fun isWindows() = System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows")
    private fun isMac() = System.getProperty("os.name").lowercase(Locale.ROOT).contains("mac")

    companion object {
        private val CHROME_VERSION = Regex("Chrome/(\\d+\\.\\d+\\.\\d+\\.\\d+)", RegexOption.IGNORE_CASE)
        private val CONTENT_RANGE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
        private const val EXECUTE_MASK = 73
        private val POSIX_PERMISSION_BITS = listOf(
            256 to PosixFilePermission.OWNER_READ,
            128 to PosixFilePermission.OWNER_WRITE,
            64 to PosixFilePermission.OWNER_EXECUTE,
            32 to PosixFilePermission.GROUP_READ,
            16 to PosixFilePermission.GROUP_WRITE,
            8 to PosixFilePermission.GROUP_EXECUTE,
            4 to PosixFilePermission.OTHERS_READ,
            2 to PosixFilePermission.OTHERS_WRITE,
            1 to PosixFilePermission.OTHERS_EXECUTE,
        )
    }
}

internal data class FourPartVersion(val major: Int, val minor: Int, val build: Int, val patch: Int) : Comparable<FourPartVersion> {
    override fun compareTo(other: FourPartVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.build }, { it.patch })
    override fun toString() = "$major.$minor.$build.$patch"
    companion object {
        fun parse(value: String): FourPartVersion? {
            val parts = value.split('.')
            if (parts.size != 4) return null
            val numbers = parts.map { it.toIntOrNull() ?: return null }
            return FourPartVersion(numbers[0], numbers[1], numbers[2], numbers[3])
        }
    }
}
