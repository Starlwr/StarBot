package com.starlwr.bot.bilibili.risk

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import com.starlwr.bot.bilibili.browser.BilibiliBrowserRuntime
import com.starlwr.bot.bilibili.browser.BrowserPage
import com.starlwr.bot.bilibili.browser.BrowserPageNavigator
import com.starlwr.bot.bilibili.credential.BilibiliBrowserIdentity
import com.starlwr.bot.core.plugin.StarBotComponent
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.net.URI
import com.starlwr.bot.bilibili.browser.captureRequestIdentity

/**
 * 交互式 Gaia 解算器（手动方案，Bilibili 页面逻辑自治）。
 *
 * 原则: 不人为篡改 Bilibili Web 的加载与验证码流程, StarBot 只做观察与收割:
 *  1. CfT 打开目标房间页(整轮会话仅此一个页面, 不反复开关) — 页面自身的逻辑自行请求
 *     getDanmuInfo / 自行触发验证码;
 *  2. 页内 fetch 探测(CDP Runtime.evaluate, 无页面创建): 明确得知 Web 自身 getDanmu 是否可用;
 *  3. 仅当「页面已打开 + 验证码面板未出现 + 页内 getDanmu 也是 -352」时, 才主动走 B 站原生
 *     验证码流程(CaptchaLoader → useCaptcha), 由我们注入挑战参数(v_voucher), 流程仍属 Bilibili 自身;
 *  4. 收割: 页内探测 code=0 → 直接带回完整响应(harvestedEnvelope); 或 Bilibili 验证逻辑写入的
 *     cookie x-bili-gaia-vtoken(遵循浏览器侧时效) → 带回 vtoken;
 *  5. 身份边界: 只读 vtoken cookie 与 API 响应, 不回写凭据库, StarBot 身份与 buvid* 不被反向覆盖。
 *
 * 可插拔: CfT 不可用时返回 null, 上层回落 ReportingGaiaChallengeProvider 退避等待; 绝不合成 token。
 */
@StarBotComponent
class BrowserGaiaVtokenProvider(
    private val runtime: BilibiliBrowserRuntime,
    private val navigator: BrowserPageNavigator,
    private val properties: BilibiliRiskProperties,
    private val identity: BilibiliBrowserIdentity,
) : GaiaChallengeProvider {

    private val log = LoggerFactory.getLogger(javaClass)
    private val inFlight = AtomicBoolean(false)
    private val pageLock = Any()
    private data class Session(val page: BrowserPage, val observer: com.starlwr.bot.bilibili.browser.BrowserRequestObserver,
                               val roomId: String, val gate: GaiaNativeInvocationGate, var nativeInvoked: Boolean = false,
                               var challengeId: String? = null,
                               var generation: Long = -1,
                               var successfulResponse: com.starlwr.bot.bilibili.browser.BrowserRequestObserver.Response? = null)
    private var session: Session? = null
    @Volatile private var awaitingProof = false
    override fun hasPendingBrowserVerification(): Boolean = awaitingProof

    override fun submit(challenge: GaiaChallenge): GaiaResolution? {
        if (!properties.gaiaEnabled) return null
        if (!inFlight.compareAndSet(false, true)) throw BrowserRecoveryPendingException("Official verification already pending")
        try { return resolve(challenge).also { if (it != null) awaitingProof = false } }
        catch (e: BrowserRecoveryPendingException) { throw e }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); return null }
        catch (e: Exception) {
            log.warn("Gaia 浏览器会话未完成: id={}, reason={}", challenge.id, e.javaClass.simpleName)
            return null
        } finally { inFlight.set(false) }
    }

    private fun resolve(challenge: GaiaChallenge): GaiaResolution? {
        val requestedRoomId = Regex("[?&]id=([0-9]+)").find(challenge.originalUri)?.groupValues?.get(1) ?: return null
        val ua = identity.headers(null)["User-Agent"] ?: return null
        runtime.enterResolverIdentity()
        if (runtime.start(ua) == null) return null
        // Once resolving risk, browser-owned login/device state is autonomous.
        val initialVtoken = readVtokenCookie(challenge.originalUri)
        val existing = synchronized(pageLock) { session }
        // The queue head may change while a human is solving a previous room's challenge.
        // Resume that shared session; its proof authorizes fresh issuance for the requested room,
        // never reuse its room token or navigate away from the unfinished challenge.
        val roomId = existing?.takeIf { it.page.connection.isOpen &&
            (it.nativeInvoked || it.observer.officialVerificationStarted() || it.successfulResponse != null) }?.roomId ?: requestedRoomId
        val current = synchronized(pageLock) {
            session?.takeIf { it.page.connection.isOpen && it.roomId == roomId } ?: run {
                val page = session?.page?.takeIf { it.connection.isOpen } ?: runtime.createPage()
                session?.observer?.close()
                val observer = com.starlwr.bot.bilibili.browser.BrowserRequestObserver(page, roomId)
                val created = Session(page, observer, roomId,
                    GaiaNativeInvocationGate(properties.gaiaNativeGraceMillis, properties.gaiaNativeQuietMillis))
                session = created
                observer.enable()
                navigator.navigate(page, "https://live.bilibili.com/$roomId")
                created
            }
        }
        val deadline = System.currentTimeMillis() + properties.gaiaInteractiveTimeoutSeconds * 1000
        awaitingProof = true
        var riskResponse: com.starlwr.bot.bilibili.browser.BrowserRequestObserver.Response? = null
        log.info("Gaia 观察官方页面请求，复用验证页且不重发成功响应: room={}, id={}", roomId, challenge.id)
        while (System.currentTimeMillis() < deadline && current.page.connection.isOpen) {
            if (current.generation != current.observer.documentGeneration()) {
                current.generation = current.observer.documentGeneration()
                current.gate.reset(); riskResponse = null; current.successfulResponse = null
                current.nativeInvoked = false; current.challengeId = null
            }
            var observed = current.observer.poll()
            while (observed != null) {
                if (observed.generation != current.generation) { observed = current.observer.poll(); continue }
                val code = observed.envelope.getInteger("code")
                if (code == 0 && observed.identity != null) {
                    current.gate.reset()
                    current.successfulResponse = observed
                }
                if (code == -352) {
                    current.successfulResponse = null
                    riskResponse = observed
                    current.gate.observedRisk(System.currentTimeMillis())
                }
                if (code == 0 && observed.identity == null) log.warn("官方响应通过但缺少同一 requestId 的身份证据，未导出 Overlay: room={}", roomId)
                observed = current.observer.poll()
            }
            val success = current.successfulResponse
            if (success != null && current.observer.officialSocketAuthenticated(success.envelope.getJSONObject("data")?.getString("token").orEmpty())) {
                val captured = success.identity!!
                log.info("官方 getDanmu 与 WS op8 均成功，带回临时身份证据，官方 socket 保留: room={}", roomId)
                return GaiaResolution(harvestedEnvelope = success.envelope,
                    browserIdentity = com.starlwr.bot.bilibili.credential.RequestIdentitySnapshot(
                        captured.headers, captured.buvid3, captured.uid, challenge.id), officialWsAuthenticated = true)
            }
            if (!current.observer.officialVerificationStarted() || current.nativeInvoked)
              currentChallengeToken(current.page, challenge.copy(id = current.challengeId ?: challenge.id), initialVtoken)?.let {
                log.info("取得 Gaia 临时令牌，等待一次带签名重试确认: id={}, tokenLen={}", challenge.id, it.length)
                return GaiaResolution(vtoken = it)
            }
            // The grace is only a lower bound: pending SDK promises, hidden
            // panels and recent network activity extend observation indefinitely.
            val readiness = if (riskResponse != null && !current.nativeInvoked) nativeCaptchaReadiness(current.page) else null
            if (riskResponse != null && !current.nativeInvoked && readiness != null && current.gate.mayInvoke(
                    System.currentTimeMillis(), readiness, current.observer.captchaLoading(), current.observer.captchaLastActivityMillis(),
                    current.observer.officialVerificationStarted()) && !riskResponse.voucher.isNullOrBlank()
                    && riskResponse.generation == current.observer.documentGeneration()) {
                current.nativeInvoked = true; current.challengeId = challenge.id
                val webChallenge = GaiaChallenge(-352, riskResponse.voucher,
                    challenge.gaData, riskResponse.url)
                evaluate(current.page, buildNativeCaptchaScript(challenge, webChallenge.voucher), await = false)
                log.info("官方页面明确 -352，原生 SDK/DOM/网络已连续安静且无面板，调用原生验证码 function: id={}, graceMs={}, quietMs={}",
                    challenge.id, properties.gaiaNativeGraceMillis, properties.gaiaNativeQuietMillis)
            }
            Thread.sleep(500)
        }
        log.warn("Gaia 等待结束，保留页面和原生验证码状态供后续继续: id={}", challenge.id)
        throw BrowserRecoveryPendingException("Official verification pending; page retained")
    }

    private fun nativeCaptchaReadiness(page: BrowserPage): GaiaNativeInvocationGate.State? = runCatching {
        val j = JSON.parseObject(evaluate(page, NATIVE_READINESS_SCRIPT, await = false))
        GaiaNativeInvocationGate.State(j.getBooleanValue("documentReady"), j.getBooleanValue("panelPresent"),
            j.getBooleanValue("panelVisible"), j.getBooleanValue("bootstrapPending"), j.getBooleanValue("nativePending"),
            j.getBooleanValue("coverageComplete"))
    }.getOrNull()

    @jakarta.annotation.PreDestroy
    fun shutdown() = synchronized(pageLock) {
        session?.observer?.close()
        session?.page?.let { runCatching { runtime.closePage(it) } }
        session = null
        awaitingProof = false
    }

    /** Single explicit fetch helper; never used to re-fetch a successful observed response. */
    internal fun fetchGetDanmuEnvelopeInPage(page: BrowserPage, url: String): JSONObject? {
        val raw = runCatching { evaluate(page,
            "fetch(${JSON.toJSONString(url)}, {credentials:'include'}).then(r=>r.json()).then(j=>JSON.stringify(j))", await = true)
        }.getOrNull() ?: return null
        return runCatching { JSON.parseObject(raw) }.getOrNull()
    }

    internal companion object {
        // Observe SDK promises without replacing CaptchaLoader.load, its cached
        // promises, native functions or their arguments/return values.
        val NATIVE_READINESS_SCRIPT = """
            (() => {
              const watch = window.__starbotNativeCaptchaObserver ||
                (window.__starbotNativeCaptchaObserver = {seen: new WeakSet(), pending: 0});
              for (const key of ['_loadConfigPromise_', '_loadLogicPromise_', '_loadSourcePromise_']) {
                const p = window[key];
                if (p && typeof p.then === 'function' && !watch.seen.has(p)) {
                  watch.seen.add(p); watch.pending++;
                  Promise.resolve(p).then(() => {watch.pending--;}, () => {watch.pending--;});
                }
              }
              const panels = []; let coverageComplete = true;
              const selector = 'div.risk-captcha,div[class*=risk-captcha],.geetest_panel,.geetest_holder,' +
                '.geetest_captcha,.geetest_box,.geetest_popup_wrap,.geetest_mask,.geetest_bind_box,iframe[src*=captcha],iframe[src*=geetest]';
              const scan = root => {
                panels.push(...root.querySelectorAll(selector));
                for (const e of root.querySelectorAll('*')) {
                  if (e.shadowRoot) scan(e.shadowRoot);
                  if (e.tagName === 'IFRAME') {
                    try { if (e.contentDocument) scan(e.contentDocument); else coverageComplete = false; }
                    catch (_) { coverageComplete = false; }
                  }
                }
              };
              scan(document);
              return JSON.stringify({documentReady: document.readyState === 'complete',
                coverageComplete,
                panelPresent: panels.length > 0,
                panelVisible: panels.some(e => !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length)),
                bootstrapPending: watch.pending > 0,
                nativePending: window.__starbotGaiaChallenge?.status === 'pending' || !!window.CaptchaLoader});
            })()
        """.trimIndent()
    }

    /** 调用 Bilibili 原生验证码 function(CaptchaLoader → useCaptcha), 仅注入挑战参数, 不改其流程。 */
    internal fun buildNativeCaptchaScript(challenge: GaiaChallenge, voucher: String? = challenge.voucher): String {
        val voucherJson = JSON.toJSONString(voucher)
        val challengeJson = JSON.toJSONString(challenge.id)
        return """
            (() => {
              const state = window.__starbotGaiaChallenge = {id: $challengeJson, status: 'pending', token: null};
              (async () => {
              try {
                const loader = window.CaptchaLoader ||
                  await new Promise((resolve, reject) => {
                    if (window.CaptchaLoader) return resolve(window.CaptchaLoader);
                    const s = document.createElement('script');
                    s.src = '//s1.hdslb.com/bfs/seed/jinkela/risk-captcha-sdk/CaptchaLoader.js';
                    s.onload = () => resolve(window.CaptchaLoader);
                    s.onerror = () => reject(new Error('CaptchaLoader.js 加载失败'));
                    document.head.appendChild(s);
                  });
                const instance = await loader.load();
                const riskParams = $voucherJson ? { riskParams: { v_voucher: $voucherJson } } : {};
                const token = await instance(riskParams);
                if (typeof token !== 'string' || !token.trim()) throw new Error('Captcha returned no token');
                state.token = token;
                state.status = 'resolved';
              } catch (e) {
                state.status = 'failed';
              }
              })();
              return 'started';
            })()
        """.trimIndent()
    }

    private fun evaluate(page: BrowserPage, script: String, await: Boolean): String {
        val result = page.connection.call(
            "Runtime.evaluate",
            mapOf("expression" to script, "returnByValue" to true, "awaitPromise" to await),
            25
        )
        result.getJSONObject("exceptionDetails")?.let { error("Gaia page script failed") }
        return result.getJSONObject("result")?.getString("value")
            ?: result["value"]?.toString()
            ?: ""
    }

    private fun pageAlive(page: BrowserPage): Boolean = runCatching {
        page.connection.call("Runtime.evaluate", mapOf("expression" to "1"), 3)
        true
    }.getOrDefault(false)

    /** Scope reads to the actual request. Reading a token does not persist browser cookies. */
    private fun readVtokenCookie(url: String): String? = runCatching {
        runtime.cookiesForRequest(URI.create(url))
            .firstOrNull { it.name.equals("x-bili-gaia-vtoken", ignoreCase = true) }?.value
    }.getOrNull()

    internal fun currentChallengeToken(page: BrowserPage, challenge: GaiaChallenge, initialVtoken: String?): String? {
        val state = runCatching { JSON.parseObject(evaluate(page,
            "JSON.stringify(window.__starbotGaiaChallenge || {})", await = false)) }.getOrNull()
        if (state?.getString("id") == challenge.id) {
            if (state.getString("status") == "resolved") {
                state.getString("token")?.takeIf { it.isNotBlank() }?.let {
                    evaluate(page, "delete window.__starbotGaiaChallenge.token; window.__starbotGaiaChallenge.status = 'consumed'", await = false)
                    return it
                }
            }
            if (state.getString("status") == "failed" && !state.getBooleanValue("reported")) {
                log.warn("Bilibili 原生验证码流程未返回令牌: id={}，请检查页面验证码或关闭/失败状态", challenge.id)
                runCatching { evaluate(page, "window.__starbotGaiaChallenge.reported = true", await = false) }
            }
        }
        return readVtokenCookie(challenge.originalUri)?.takeIf { it.isNotBlank() && it != initialVtoken }
    }
}
