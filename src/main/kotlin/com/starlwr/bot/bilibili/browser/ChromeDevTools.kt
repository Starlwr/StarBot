package com.starlwr.bot.bilibili.browser

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.bilibili.credential.BilibiliCredentialFileStore
import com.starlwr.bot.bilibili.credential.ClientProfileState
import com.starlwr.bot.bilibili.credential.CredentialEnvelope
import com.starlwr.bot.bilibili.credential.StoredCookie
import com.starlwr.bot.bilibili.http.BilibiliHttpProperties
import com.starlwr.bot.bilibili.http.proxyProfileId
import com.starlwr.bot.core.plugin.StarBotComponent
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

data class BrowserRuntimeInfo(
    val executable: BrowserExecutable,
    val product: String,
    val protocolVersion: String,
    val userAgent: String,
    val jsVersion: String,
    val profileDirectory: Path,
    val port: Int,
)

data class BrowserPage(val targetId: String, val connection: CdpConnection) : AutoCloseable {
    override fun close() = connection.close()
}

data class BrowserCredentialSnapshot(
    val cookies: Map<String, StoredCookie>,
    val cookieVariants: Map<String, List<StoredCookie>>,
    val refreshTokenStorage: String,
    val capturedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    fun cookie(name: String): StoredCookie? = cookies[name.lowercase(Locale.ROOT)]
    fun variants(name: String): List<StoredCookie> = cookieVariants[name.lowercase(Locale.ROOT)].orEmpty()
}

class CdpConnection(private val socket: WebSocket) : AutoCloseable {
    private val sequence = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()
    private val listeners = CopyOnWriteArrayList<Consumer<JSONObject>>()

    fun call(method: String, params: Any? = null, timeoutSeconds: Long = 20): JSONObject {
        val id = sequence.incrementAndGet()
        val request = JSONObject().fluentPut("id", id).fluentPut("method", method)
        if (params != null) request["params"] = params
        val future = CompletableFuture<JSONObject>()
        pending[id] = future
        socket.sendText(request.toJSONString(), true).join()
        val response = try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (error: Exception) {
            pending.remove(id)
            throw error
        }
        response.getJSONObject("error")?.let { error("CDP $method failed: ${it.toJSONString()}") }
        return response.getJSONObject("result") ?: JSONObject()
    }

    fun onEvent(listener: Consumer<JSONObject>): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    internal fun receive(message: String) {
        val json = runCatching { JSON.parseObject(message) }.getOrNull() ?: return
        val id = json.getLong("id")
        if (id != null) pending.remove(id)?.complete(json) else listeners.forEach { it.accept(json) }
    }

    internal fun fail(error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    override fun close() {
        runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "StarBot shutdown").get(3, TimeUnit.SECONDS) }
        fail(IllegalStateException("CDP connection closed"))
    }
}

private class CdpListener : WebSocket.Listener {
    val connected = CompletableFuture<CdpConnection>()
    private val text = StringBuilder()
    @Volatile private var connection: CdpConnection? = null

    override fun onOpen(webSocket: WebSocket) {
        connection = CdpConnection(webSocket)
        connected.complete(connection)
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<*>? {
        text.append(data)
        if (last) {
            val message = text.toString()
            text.setLength(0)
            connection?.receive(message)
        }
        webSocket.request(1)
        return null
    }

    override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletableFuture<*>? {
        webSocket.request(1)
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        connection?.fail(error) ?: connected.completeExceptionally(error)
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletableFuture<*>? {
        connection?.fail(IllegalStateException("CDP closed: $statusCode $reason"))
        return null
    }
}

/** One account-level browser process. It is a resolver/capability host, never a room-per-tab browser farm. */
@StarBotComponent
class BilibiliBrowserRuntime(
    private val properties: BilibiliBrowserProperties,
    private val httpProperties: BilibiliHttpProperties,
    private val manager: ChromeForTestingManager,
    private val credentialStore: BilibiliCredentialFileStore,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val lock = Any()
    @Volatile private var closed = false
    @Volatile private var process: Process? = null
    @Volatile private var browser: CdpConnection? = null
    @Volatile private var hydratedIdentityRevision: Long = -1
    @Volatile private var info: BrowserRuntimeInfo? = null
    @Volatile private var credentialPage: BrowserPage? = null
    private val credentialPageLock = Any()

    fun start(userAgent: String): BrowserRuntimeInfo? = synchronized(lock) {
        if (closed) return null
        info?.let { return it }
        val executable = manager.resolve(userAgent) ?: return null
        val port = freePort()
        val expectedMajor = executable.expectedVersion?.substringBefore('.')
            ?: CHROME_MAJOR.find(userAgent)?.groupValues?.get(1) ?: "unknown"
        val profile = Path.of(properties.profileDirectory).toAbsolutePath().normalize().resolve(expectedMajor)
        Files.createDirectories(profile)
        val command = mutableListOf(
            executable.path.toString(),
            "--remote-debugging-address=127.0.0.1",
            "--remote-debugging-port=$port",
            "--user-data-dir=$profile",
            "--user-agent=$userAgent",
            "--window-size=1920,1080",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-session-crashed-bubble",
            "--disable-background-networking",
        )
        if (properties.startMinimized && !properties.headless) command += "--start-minimized"
        if (properties.headless) command += "--headless=new"
        httpProperties.proxyUri?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { command += "--proxy-server=$it" }
            ?: run { command += "--no-proxy-server" }
        // A controlled capability host must not restore arbitrary Web tabs before
        // StarBot has hydrated/sanitized its Credential state.
        command += "about:blank"
        val launched = try {
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (error: Exception) {
            manager.reportLaunchFailure(executable, error)
            log.warn("Chrome 启动失败，浏览器能力不可用: executable={}, error={}", executable.path, error.toString())
            return start(userAgent)
        }
        process = launched
        try {
            val version = waitForVersion(port, launched)
                ?: error("Chrome DevTools endpoint did not become ready")
            val product = version.getString("Browser").orEmpty()
            require(browserVersionMatches(product, executable.expectedVersion)) {
                "Chrome CDP version mismatch: expected=${executable.expectedVersion} actual=$product"
            }
            val browserConnection = connect(version.getString("webSocketDebuggerUrl"))
            browser = browserConnection
            hydrateCookies(browserConnection)
            val runtimeInfo = BrowserRuntimeInfo(
                executable, product, version.getString("Protocol-Version").orEmpty(),
                version.getString("User-Agent").orEmpty(), version.getString("V8-Version").orEmpty(), profile, port
            )
            info = runtimeInfo
            credentialStore.update(critical = true) { envelope ->
                envelope.browser.executablePath = executable.path.toString()
                envelope.browser.profileDirectory = profile.toString()
                envelope.browser.installedVersion = executable.expectedVersion.orEmpty()
                envelope.browser.installedRevision = executable.revision.orEmpty()
                envelope.effectiveBrowserProfile = ClientProfileState(
                    transport = "browser", userAgent = runtimeInfo.userAgent,
                    browserProduct = runtimeInfo.product, browserVersion = executable.expectedVersion.orEmpty(),
                    platform = System.getProperty("os.name"),
                    proxyProfileId = proxyProfileId(httpProperties.proxyUri),
                    observedAtEpochMillis = System.currentTimeMillis()
                )
            }
            synchronizeCredentialStorage()
            log.info("Chrome 浏览器能力宿主已启动: source={}, product={}, profile={}, resolution={}",
                executable.source, runtimeInfo.product, profile, BilibiliBrowserProperties.BROWSER_RESOLUTION)
            runtimeInfo
        } catch (error: Exception) {
            cleanupFailedLaunch(launched)
            manager.reportLaunchFailure(executable, error)
            if (closed) null else start(userAgent)
        }
    }

    fun runtimeInfo(): BrowserRuntimeInfo? = info

    fun refreshCanonicalIdentity(): Boolean = synchronized(lock) {
        val connection = browser ?: return false
        runCatching {
            hydrateCookies(connection)
            synchronizeCredentialStorage()
        }.onFailure { error ->
            log.warn("向可选浏览器下发 JVM Credential 失败；JVM 主流程继续运行: {}", error.toString())
            log.debug("Browser Credential hydration detail", error)
        }.isSuccess
    }

    fun observeCredentialSnapshot(): BrowserCredentialSnapshot? {
        val connection = browser ?: return null
        val variants = connection.call("Storage.getCookies").getJSONArray("cookies")
            ?.filterIsInstance<JSONObject>().orEmpty()
            .filter { it.getString("domain").orEmpty().contains("bilibili.com") }
            .mapNotNull(::browserCookie)
            .groupBy { it.name.lowercase(Locale.ROOT) }
        val values = variants.mapValues { (_, cookies) ->
                cookies.sortedWith(compareBy<StoredCookie>(
                    { if (it.domain == ".bilibili.com") 0 else 1 },
                    { if (it.path == "/") 0 else 1 },
                )).first()
            }
        val refreshToken = runCatching {
            val page = credentialInspectionPage()
            evaluateString(page.connection, "localStorage.getItem('ac_time_value') || ''")
        }.onFailure { invalidateCredentialPage() }.getOrDefault("")
        return BrowserCredentialSnapshot(values, variants, refreshToken)
    }

    fun createPage(url: String = "about:blank"): BrowserPage {
        val browserConnection = browser ?: error("Browser runtime is not started")
        val targetId = browserConnection.call("Target.createTarget", mapOf("url" to url)).getString("targetId")
            ?: error("CDP Target.createTarget omitted targetId")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val list = queryJson("http://127.0.0.1:${info!!.port}/json/list") as JSONArray
            val target = list.filterIsInstance<JSONObject>().firstOrNull { it.getString("id") == targetId }
            val websocketUrl = target?.getString("webSocketDebuggerUrl")
            if (!websocketUrl.isNullOrBlank()) return BrowserPage(targetId, connect(websocketUrl))
            Thread.sleep(50)
        }
        error("Timed out waiting for CDP page target $targetId")
    }

    fun closePage(page: BrowserPage) {
        runCatching { browser?.call("Target.closeTarget", mapOf("targetId" to page.targetId), 5) }
        page.close()
    }

    fun synchronizeCookiesFromBrowser(source: String = "browser"): Boolean {
        val browserConnection = browser ?: return false
        val currentRevision = credentialStore.snapshot()?.identityRevision ?: -1
        if (currentRevision != hydratedIdentityRevision) {
            val previousRevision = hydratedIdentityRevision
            hydrateCookies(browserConnection)
            log.debug(
                "Rehydrated browser cookies before import: source={}, previousRevision={}, currentRevision={}",
                source, previousRevision, currentRevision,
            )
        }
        val cookies = browserConnection.call("Storage.getCookies").getJSONArray("cookies") ?: return false
        val stored = cookies.filterIsInstance<JSONObject>().mapNotNull(::browserCookie)
            .filterNot { it.name.lowercase(Locale.ROOT) in BROWSER_AUTHORITATIVE_BLOCKLIST }
            .onEach { it.transportScope = "browser"; it.source = source }
        val changed = credentialStore.mergeCookies(stored, critical = true, projectAccount = false)
        if (changed) credentialStore.update(critical = true) { envelope ->
            envelope.browser.cookieHash = credentialStore.cookieHash("browser")
            envelope.browser.lastSynchronizedAtEpochMillis = System.currentTimeMillis()
        }
        hydratedIdentityRevision = credentialStore.snapshot()?.identityRevision ?: hydratedIdentityRevision
        return changed
    }

    fun setCookie(cookie: StoredCookie) {
        val connection = browser ?: error("Browser runtime is not started")
        val value = linkedMapOf<String, Any>(
            "name" to cookie.name, "value" to cookie.value, "domain" to cookie.domain,
            "path" to cookie.path, "secure" to cookie.secure, "httpOnly" to cookie.httpOnly,
        ).apply {
            cookie.expiresAtEpochSeconds?.takeIf { it > 0 }?.let { put("expires", it) }
            cookie.sameSite?.takeIf { it in setOf("Strict", "Lax", "None") }?.let { put("sameSite", it) }
        }
        connection.call("Storage.setCookies", mapOf("cookies" to listOf(value)))
        credentialStore.mergeCookies(listOf(cookie), critical = true, projectAccount = false)
    }

    fun hydrateWebStorage(page: BrowserPage): String {
        val origin = evaluateString(page.connection, "location.origin")
        if (origin.isBlank() || origin == "null") return origin
        val values = credentialStore.snapshot()?.webStorage?.get(origin).orEmpty()
            .filterKeys(::isPersistentStorageKey)
        if (values.isNotEmpty()) {
            val json = JSON.toJSONString(values)
            evaluateString(page.connection, """
                (() => { const values = $json; for (const [key, value] of Object.entries(values)) localStorage.setItem(key, value); return location.origin; })()
            """.trimIndent())
        }
        return origin
    }

    fun synchronizeWebStorageFromPage(page: BrowserPage, source: String = "browser"): Boolean {
        val origin = evaluateString(page.connection, "location.origin")
        if (origin.isBlank() || origin == "null") return false
        val raw = evaluateString(page.connection,
            "JSON.stringify(Object.fromEntries(Array.from({length:localStorage.length},(_,i)=>localStorage.key(i)).filter(Boolean).map(k=>[k,localStorage.getItem(k)])))")
        val values = runCatching { JSON.parseObject(raw) }.getOrNull()?.entries
            ?.filter { isPersistentStorageKey(it.key) && it.value != null }
            ?.associate { it.key to it.value.toString() }.orEmpty()
        val before = credentialStore.snapshot()?.webStorage?.get(origin).orEmpty()
        if (before == values) return false
        credentialStore.update(critical = true) { envelope ->
            if (values.isEmpty()) envelope.webStorage.remove(origin)
            else envelope.webStorage[origin] = values.toMutableMap()
        }
        log.debug("Synchronized controlled browser storage: origin={}, source={}, keys={}", origin, source, values.keys)
        return true
    }

    private fun hydrateCookies(connection: CdpConnection) {
        val snapshot = credentialStore.snapshot() ?: credentialStore.load() ?: return
        val cookies = snapshot.cookies.filter {
            !it.isExpired() && !it.name.equals("b_lsid", true) && !it.name.equals("ac_time_value", true) &&
                (it.transportScope == "shared" || it.name.equals("bili_ticket", true) ||
                    it.name.equals("bili_ticket_expires", true))
        }.map { cookie ->
            linkedMapOf<String, Any>(
                "name" to cookie.name, "value" to cookie.value, "domain" to cookie.domain,
                "path" to cookie.path, "secure" to cookie.secure, "httpOnly" to cookie.httpOnly,
            ).apply {
                cookie.expiresAtEpochSeconds?.takeIf { it > 0 }?.let { put("expires", it) }
                cookie.sameSite?.takeIf { it in setOf("Strict", "Lax", "None") }?.let { put("sameSite", it) }
            }
        }
        val replacedNames = cookies.map { it["name"].toString().lowercase(Locale.ROOT) }
            .filter { it in JVM_AUTHORITY_COOKIE_NAMES }.toSet()
        if (replacedNames.isNotEmpty()) {
            connection.call("Storage.getCookies").getJSONArray("cookies")
                ?.filterIsInstance<JSONObject>().orEmpty()
                .filter {
                    it.getString("domain").orEmpty().contains("bilibili.com") &&
                        it.getString("name").orEmpty().lowercase(Locale.ROOT) in replacedNames
                }
                .forEach { existing ->
                    runCatching {
                        connection.call("Storage.deleteCookies", mapOf(
                            "name" to existing.getString("name").orEmpty(),
                            "domain" to existing.getString("domain").orEmpty(),
                            "path" to (existing.getString("path") ?: "/"),
                        ), 5)
                    }
                }
        }
        // Keep browser-generated/device cookies from the persistent profile and
        // overwrite only names present in the persisted StarBot credential.
        if (cookies.isNotEmpty()) connection.call("Storage.setCookies", mapOf("cookies" to cookies))
        hydratedIdentityRevision = snapshot.identityRevision
    }

    private fun browserCookie(item: JSONObject): StoredCookie? {
        val name = item.getString("name") ?: return null
        val value = item.getString("value") ?: return null
        val domain = item.getString("domain").orEmpty()
        if (!domain.contains("bilibili.com")) return null
        return StoredCookie(
            name = name,
            value = if (name.equals("browser_resolution", true)) BilibiliBrowserProperties.BROWSER_RESOLUTION else value,
            domain = domain,
            path = item.getString("path") ?: "/",
            hostOnly = !domain.startsWith('.'), secure = item.getBooleanValue("secure"),
            httpOnly = item.getBooleanValue("httpOnly"), sameSite = item.getString("sameSite"),
            expiresAtEpochSeconds = item.getDouble("expires")?.toLong()?.takeIf { it > 0 },
            transportScope = "browser", source = "browser-observation",
        )
    }

    private fun credentialInspectionPage(): BrowserPage = synchronized(credentialPageLock) {
        credentialPage?.let { return it }
        val page = createPage("https://www.bilibili.com/robots.txt")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val origin = runCatching { evaluateString(page.connection, "location.origin") }.getOrDefault("")
            if (origin == "https://www.bilibili.com") {
                credentialPage = page
                return page
            }
            Thread.sleep(50)
        }
        closePage(page)
        error("Timed out preparing Bilibili credential inspection origin")
    }

    private fun invalidateCredentialPage() = synchronized(credentialPageLock) {
        credentialPage?.let { runCatching { closePage(it) } }
        credentialPage = null
    }

    private fun synchronizeCredentialStorage() {
        val connection = browser ?: return
        runCatching {
            connection.call("Storage.deleteCookies", mapOf("name" to "ac_time_value", "domain" to ".bilibili.com"), 5)
        }
        runCatching {
            connection.call("Storage.deleteCookies", mapOf("name" to "ac_time_value", "domain" to "www.bilibili.com"), 5)
        }
        val page = credentialInspectionPage()
        val mode = properties.credentialSyncMode.trim().lowercase(Locale.ROOT)
        val token = credentialStore.snapshot()?.account?.acTimeValue.orEmpty()
        val expression = if (mode == "validated-bidirectional" && token.isNotBlank()) {
            val encoded = JSON.toJSONString(token)
            "localStorage.setItem('ac_time_value', $encoded); 'stored'"
        } else {
            "localStorage.removeItem('ac_time_value'); 'removed'"
        }
        evaluateString(page.connection, expression)
    }

    private fun evaluateString(connection: CdpConnection, expression: String): String {
        val result = connection.call("Runtime.evaluate", mapOf(
            "expression" to expression, "returnByValue" to true, "awaitPromise" to true
        )).getJSONObject("result") ?: return ""
        return result.getString("value") ?: result["value"]?.toString().orEmpty()
    }

    private fun isPersistentStorageKey(key: String): Boolean =
        key in CredentialEnvelope.BROWSER_STORAGE_ALLOWLIST ||
            key.startsWith("secure_collect_last_report_time_") ||
            key.startsWith("secure_collect_report_interval_")

    private fun waitForVersion(port: Int, launched: Process): JSONObject? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline && launched.isAlive) {
            runCatching { queryJson("http://127.0.0.1:$port/json/version") as JSONObject }.getOrNull()?.let { return it }
            Thread.sleep(100)
        }
        log.warn("Chrome DevTools 未在期限内就绪: pid={}, alive={}", launched.pid(), launched.isAlive)
        return null
    }

    private fun cleanupFailedLaunch(launched: Process) {
        runCatching { browser?.close() }
        browser = null
        info = null
        stopProcessTree(launched)
        if (process === launched) process = null
    }

    private fun stopProcessTree(root: Process) {
        val descendants = root.toHandle().descendants().toList().asReversed()
        descendants.forEach { runCatching { it.destroy() } }
        runCatching { root.destroy() }
        if (!runCatching { root.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(true)) {
            descendants.forEach { runCatching { it.destroyForcibly() } }
            runCatching { root.destroyForcibly() }
            runCatching { root.waitFor(3, TimeUnit.SECONDS) }
        }
    }

    private fun queryJson(url: String): Any {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        require(response.statusCode() in 200..299)
        return JSON.parse(response.body())
    }

    private fun connect(url: String): CdpConnection {
        val listener = CdpListener()
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(URI.create(url), listener).join()
        return listener.connected.get(10, TimeUnit.SECONDS)
    }

    private fun freePort(): Int = ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }

    @PreDestroy
    override fun close() {
        closed = true
        // start() can hold the runtime lock while CfT is downloading. Cancel the
        // manager before waiting for that lock so Spring shutdown cannot deadlock.
        manager.close()
        synchronized(lock) {
            runCatching { synchronizeCookiesFromBrowser("browser-shutdown") }
            synchronized(credentialPageLock) {
                credentialPage?.let { runCatching { closePage(it) } }
                credentialPage = null
            }
            browser?.close(); browser = null
            process?.let(::stopProcessTree)
            process = null; info = null
        }
    }

    companion object {
        private val CHROME_MAJOR = Regex("Chrome/(\\d+)", RegexOption.IGNORE_CASE)
        private val BROWSER_AUTHORITATIVE_BLOCKLIST = setOf(
            "sessdata", "bili_jct", "dedeuserid", "dedeuserid__ckmd5", "ac_time_value",
            "buvid3", "buvid4", "buvid_fp", "b_nut", "bili_ticket", "bili_ticket_expires", "sid",
        )
        private val JVM_AUTHORITY_COOKIE_NAMES = BROWSER_AUTHORITATIVE_BLOCKLIST - setOf("sid", "ac_time_value")

        internal fun browserVersionMatches(product: String, expected: String?): Boolean {
            if (expected.isNullOrBlank()) return true
            return product.substringAfter('/', "") == expected
        }
    }
}
