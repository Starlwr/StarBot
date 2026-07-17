package com.starlwr.bot.bilibili.browser

import com.alibaba.fastjson2.JSON
import com.starlwr.bot.core.plugin.StarBotComponent
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import java.io.BufferedInputStream
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
import java.security.MessageDigest
import java.time.Duration
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

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
    var downloadConnectTimeoutSeconds: Long = 15
    var downloadTimeoutSeconds: Long = 300
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

/** Offline-first, version-preserving Chrome for Testing resolver and installer. */
@StarBotComponent
@EnableConfigurationProperties(BilibiliBrowserProperties::class)
class ChromeForTestingManager(private val properties: BilibiliBrowserProperties) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(properties.downloadConnectTimeoutSeconds.coerceAtLeast(1)))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val failedExecutables = ConcurrentHashMap.newKeySet<Path>()
    private val closed = AtomicBoolean()
    private val activeRequests = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
    private val activeVerificationProcess = AtomicReference<Process?>()

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
                .onFailure { log.warn("Chrome for Testing 按需安装失败，将尝试系统浏览器: {}", it.toString()) }
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
            val resolved = BrowserExecutable(finalExecutable, "cft-installed", version.version, version.revision)
            writeCurrent(resolved, version, platform, download.url, "")
            return resolved
        }

        val stagingRoot = home.resolve(".staging").normalize()
        Files.createDirectories(stagingRoot)
        val staging = stagingRoot.resolve(UUID.randomUUID().toString()).normalize()
        require(staging.startsWith(stagingRoot))
        Files.createDirectories(staging)
        val archive = staging.resolve("chrome.zip.part")
        try {
            val request = HttpRequest.newBuilder(URI.create(download.url))
                .timeout(Duration.ofSeconds(properties.downloadTimeoutSeconds.coerceAtLeast(30)))
                .GET().build()
            val response = sendCancellable(request, HttpResponse.BodyHandlers.ofFile(archive))
            require(response.statusCode() in 200..299) { "CfT 下载 HTTP ${response.statusCode()}" }
            val contentLength = response.headers().firstValueAsLong("content-length").orElse(-1)
            require(contentLength <= 0 || contentLength <= properties.maximumArchiveBytes) { "CfT archive Content-Length 超限" }
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
            verifyVersion(sourceExecutable, version.version)
            Files.createDirectories(finalDirectory.parent)
            moveDirectory(extracted, finalDirectory)
            val resolved = BrowserExecutable(finalExecutable, "cft-downloaded", version.version, version.revision)
            writeCurrent(resolved, version, platform, download.url, archiveHash)
            return resolved
        } finally {
            deleteTree(staging, stagingRoot)
        }
    }

    private fun loadCatalogForDownload(): ChromeForTestingCatalog {
        val catalogPath = browserHome().resolve(properties.versionsCatalog).normalize()
        if (!Files.isRegularFile(catalogPath)) downloadCatalog(catalogPath)
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
                .timeout(Duration.ofSeconds(properties.downloadTimeoutSeconds.coerceAtLeast(30))).GET().build()
            val response = sendCancellable(request, HttpResponse.BodyHandlers.ofFile(temporary))
            require(response.statusCode() in 200..299) { "CfT catalog HTTP ${response.statusCode()}" }
            val parsed = JSON.parseObject(Files.readString(temporary, StandardCharsets.UTF_8), ChromeForTestingCatalog::class.java)
            require(parsed != null && parsed.versions.isNotEmpty()) { "CfT catalog 结构无效" }
            force(temporary)
            atomicMove(temporary, target)
            log.info("本地 CfT 清单缺失，已按需从官方端点取得并原子保存: {}", target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun readCurrent(): BrowserExecutable? {
        val path = browserHome().resolve(properties.currentManifest)
        if (!Files.isRegularFile(path)) return null
        val manifest = runCatching { JSON.parseObject(Files.readString(path), ChromeInstallManifest::class.java) }.getOrNull()
            ?: return null
        if (manifest.executable.isBlank()) return null
        // Normal startup deliberately does not hash or execute --version. Launch failure
        // is the trigger for repair/fallback.
        return BrowserExecutable(browserHome().resolve(manifest.executable).normalize(), "cft-current", manifest.version, manifest.revision)
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
            val executable = path.resolve(platform).resolve(executableRelativePath(platform))
            if (Files.isRegularFile(executable) && executable.toAbsolutePath().normalize() !in failedExecutables) {
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

    private fun unzipSafely(archive: Path, destination: Path) {
        Files.createDirectories(destination)
        var total = 0L
        ZipInputStream(BufferedInputStream(Files.newInputStream(archive))).use { zip ->
            while (true) {
                ensureOpen()
                val entry = zip.nextEntry ?: break
                val output = destination.resolve(entry.name).normalize()
                require(output.startsWith(destination)) { "CfT ZIP entry 越界: ${entry.name}" }
                if (entry.isDirectory) Files.createDirectories(output) else {
                    Files.createDirectories(output.parent)
                    Files.newOutputStream(output, StandardOpenOption.CREATE_NEW).use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureOpen()
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= properties.maximumExtractedBytes) { "CfT 解压总大小超限" }
                            stream.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun verifyVersion(executable: Path, expected: String) {
        ensureOpen()
        executable.toFile().setExecutable(true, true)
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

    private fun <T> sendCancellable(
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        ensureOpen()
        val future = http.sendAsync(request, handler)
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
        activeVerificationProcess.getAndSet(null)?.destroyForcibly()
    }

    private fun browserHome() = Path.of(properties.browserHome).toAbsolutePath().normalize()
    private fun requestedChromeVersion(ua: String) = CHROME_VERSION.find(ua)?.groupValues?.get(1)
    private fun currentPlatform(): String? = when {
        isWindows() && System.getProperty("os.arch").contains("64") -> "win64"
        isWindows() -> "win32"
        isMac() && System.getProperty("os.arch").lowercase(Locale.ROOT) in setOf("aarch64", "arm64") -> "mac-arm64"
        isMac() -> "mac-x64"
        System.getProperty("os.arch").contains("64") -> "linux64"
        else -> null.also { log.warn("CfT 不支持当前平台: {} {}", System.getProperty("os.name"), System.getProperty("os.arch")) }
    }
    private fun executableRelativePath(platform: String): Path = when {
        platform.startsWith("win") -> Path.of("chrome-$platform", "chrome.exe")
        platform == "linux64" -> Path.of("chrome-linux64", "chrome")
        platform == "mac-arm64" -> Path.of("chrome-mac-arm64", "Google Chrome for Testing.app", "Contents", "MacOS", "Google Chrome for Testing")
        else -> Path.of("chrome-mac-x64", "Google Chrome for Testing.app", "Contents", "MacOS", "Google Chrome for Testing")
    }
    private fun isWindows() = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
    private fun isMac() = System.getProperty("os.name").lowercase(Locale.ROOT).contains("mac")

    companion object { private val CHROME_VERSION = Regex("Chrome/(\\d+\\.\\d+\\.\\d+\\.\\d+)", RegexOption.IGNORE_CASE) }
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
