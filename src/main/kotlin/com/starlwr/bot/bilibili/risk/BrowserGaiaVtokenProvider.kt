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

    override fun submit(challenge: GaiaChallenge): GaiaResolution? {
        if (!properties.gaiaEnabled) return null
        if (!inFlight.compareAndSet(false, true)) {
            log.info("Gaia 交互验证已在进行中, 本次挑战 (code={}) 等待既有会话完成: uri={}",
                challenge.businessCode, challenge.originalUri.substringBefore('?'))
            return null
        }
        try {
            return resolve(challenge)
        } catch (e: Exception) {
            log.warn("Gaia 交互验证失败, 回退至退避等待: code={}, uri={}, reason={}",
                challenge.businessCode, challenge.originalUri.substringBefore('?'), e.toString())
            return null
        } finally {
            inFlight.set(false)
        }
    }

    private fun resolve(challenge: GaiaChallenge): GaiaResolution? {
        val roomId = Regex("[?&]id=([0-9]+)").find(challenge.originalUri)?.groupValues?.get(1)
            ?: return null.also { log.warn("挑战 URI 无房间号, 无法打开房间页: id={}", challenge.id) }

        log.info("启动浏览器交互式 Gaia 验证: id={}, code={}, room={}, 等待 Bilibili 页面逻辑与人工验证 (最长 {} 秒)",
            challenge.id, challenge.businessCode, roomId, properties.gaiaInteractiveTimeoutSeconds)

        val ua = identity.headers(null)["User-Agent"] ?: return null.also {
            log.warn("无法确定浏览器 UA, Gaia 交互验证跳过: id={}", challenge.id)
        }
        if (runtime.start(ua) == null) {
            log.warn("CfT 浏览器运行时不可用, Gaia 交互验证跳过: id={}", challenge.id)
            return null
        }

        // 整轮会话仅一个页面; 先建空白页再经 CDP 导航(createPage 直接带 URL 会在跨进程导航时关闭 WS)
        val page = runtime.createPage()
        try {
            navigator.navigate(page, "https://live.bilibili.com/$roomId")
            // 将 StarBot 身份(buvid*/SESSDATA/ticket)下发浏览器并刷新页面:
            // 页面自身的 getDanmuInfo 因此绑定 StarBot 身份, 收割的 token 与 WS 认证体一致。
            // 方向为 JVM→浏览器(StarBot 权威), 不会反向覆盖 StarBot 身份与 buvid*。
            runCatching { runtime.refreshCanonicalIdentity() }
                .onFailure { log.warn("身份下发失败(继续以浏览器现状验证): {}", it.toString()) }
                .onSuccess { runCatching { navigator.navigate(page, "https://live.bilibili.com/$roomId") } }
        } catch (e: Exception) {
            log.warn("房间页导航失败: {}", e.toString())
            runtime.closePage(page)
            return null
        }

        val deadline = System.currentTimeMillis() + properties.gaiaInteractiveTimeoutSeconds * 1000
        var nativeCaptchaInvoked = false
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(3000)

                // (a) 页内 fetch 探测: Web 自身环境的 getDanmuInfo 结果(无任何页面创建)
                val probeCode = probeGetDanmuInPage(page, challenge.originalUri)

                // (b) code=0 → Web 环境已通过, 直接带回完整响应, StarBot 不再自发请求
                if (probeCode == 0) {
                    val envelope = fetchGetDanmuEnvelopeInPage(page, challenge.originalUri)
                    if (envelope != null) {
                        log.info("Web 环境 getDanmuInfo 已通过, 直接带回响应: room={}", roomId)
                        return GaiaResolution(harvestedEnvelope = envelope)
                    }
                }

                // (c) Bilibili 验证逻辑写入的 vtoken cookie(遵循浏览器侧时效)
                readVtokenCookie()?.let {
                    log.info("Gaia 交互验证通过(vtoken cookie): id={}, tokenLen={}", challenge.id, it.length)
                    return GaiaResolution(vtoken = it)
                }

                // (d) 页内明确 -352 且验证码面板未出现 → 主动走 B 站原生验证码流程(仅一次, 注入挑战参数)
                if (probeCode == -352 && !nativeCaptchaInvoked && !captchaPanelVisible(page)) {
                    nativeCaptchaInvoked = true
                    log.info("Web 自身 getDanmu -352 且验证码未自行触发, 注入参数走 Bilibili 原生验证码流程: room={}", roomId)
                    runCatching { evaluate(page, buildNativeCaptchaScript(challenge.voucher), await = true) }
                        .onFailure { log.warn("原生验证码流程调用失败: {}", it.toString()) }
                }

                // (e) 页面 WS 断开(反爬/重载) → 宁静重建一次, 不与其他逻辑混用页面
                if (!pageAlive(page)) {
                    log.info("房间页连接断开, 重建页面继续等待: room={}", roomId)
                    runCatching { runtime.closePage(page) }
                    val replacement = runtime.createPage()
                    try {
                        navigator.navigate(replacement, "https://live.bilibili.com/$roomId")
                        // 重建后允许再次注入原生流程(新页面 Bilibili 逻辑可能需要重新辅助)
                        nativeCaptchaInvoked = false
                        return waitOnPage(replacement, challenge, deadline)
                    } catch (e: Exception) {
                        log.warn("重建页面导航失败: {}", e.toString())
                        runtime.closePage(replacement)
                        return null
                    }
                }
            }
        } finally {
            runCatching { runtime.closePage(page) }
        }
        log.warn("Gaia 交互验证等待超时 ({} 秒), 用户未完成验证: id={}",
            properties.gaiaInteractiveTimeoutSeconds, challenge.id)
        return null
    }

    /** 页面重建后的等待循环(与主循环同语义, 页面不再触发重建递归)。 */
    private fun waitOnPage(page: BrowserPage, challenge: GaiaChallenge, deadline: Long): GaiaResolution? {
        var nativeInvoked = false
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(3000)
                val probeCode = probeGetDanmuInPage(page, challenge.originalUri)
                if (probeCode == 0) {
                    val envelope = fetchGetDanmuEnvelopeInPage(page, challenge.originalUri)
                    if (envelope != null) {
                        log.info("Web 环境 getDanmuInfo 已通过, 直接带回响应: room={}",
                            challenge.originalUri.substringAfter("id=").substringBefore('&'))
                        return GaiaResolution(harvestedEnvelope = envelope)
                    }
                }
                readVtokenCookie()?.let {
                    log.info("Gaia 交互验证通过(vtoken cookie): id={}, tokenLen={}", challenge.id, it.length)
                    return GaiaResolution(vtoken = it)
                }
                if (probeCode == -352 && !nativeInvoked && !captchaPanelVisible(page)) {
                    nativeInvoked = true
                    log.info("重建页面后 Web getDanmu 仍 -352 且无验证码, 注入参数走原生流程: id={}", challenge.id)
                    runCatching { evaluate(page, buildNativeCaptchaScript(challenge.voucher), await = true) }
                }
                if (!pageAlive(page)) {
                    log.warn("重建后的页面再次断开, 本轮会话终止等待: id={}", challenge.id)
                    return null
                }
            }
        } finally {
            runCatching { runtime.closePage(page) }
        }
        return null
    }

    /** 页内 fetch 探测(只取 code): 返回 null 表示探测不可用(不算 -352, 不触发注入)。 */
    private fun probeGetDanmuInPage(page: BrowserPage, url: String): Int? {
        val raw = runCatching {
            evaluate(page, """
                fetch('$url', { credentials: 'include' })
                  .then(r => r.json()).then(j => String(j.code)).catch(() => 'error')
            """.trimIndent(), await = true)
        }.getOrNull() ?: return null
        return raw.toIntOrNull()
    }

    /** 页内 fetch 拉取完整响应体。 */
    private fun fetchGetDanmuEnvelopeInPage(page: BrowserPage, url: String): JSONObject? {
        val raw = runCatching {
            evaluate(page, """
                fetch('$url', { credentials: 'include' }).then(r => r.json()).then(j => JSON.stringify(j)).catch(() => '')
            """.trimIndent(), await = true)
        }.getOrNull().orEmpty()
        if (raw.isBlank()) return null
        return runCatching { JSON.parseObject(raw) }.getOrNull()
    }

    /** 验证码面板是否已由 Bilibili 自身逻辑触发。 */
    private fun captchaPanelVisible(page: BrowserPage): Boolean = runCatching {
        evaluate(page,
            "!!document.querySelector('.risk-captcha,[class*=risk-captcha],.geetest_panel,iframe[src*=captcha]')",
            await = false) == "true"
    }.getOrDefault(false)

    /** 调用 Bilibili 原生验证码 function(CaptchaLoader → useCaptcha), 仅注入挑战参数, 不改其流程。 */
    private fun buildNativeCaptchaScript(voucher: String?): String {
        val voucherJson = JSON.toJSONString(voucher)
        return """
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
                instance(riskParams);
              } catch (e) {
                console.log('[gaia-native] 调用失败: ' + ((e && e.message) || e));
              }
            })();
        """.trimIndent()
    }

    private fun evaluate(page: BrowserPage, script: String, await: Boolean): String {
        val result = page.connection.call(
            "Runtime.evaluate",
            mapOf("expression" to script, "returnByValue" to true, "awaitPromise" to await),
            25
        )
        return result.getJSONObject("result")?.getString("value")
            ?: result["value"]?.toString()
            ?: ""
    }

    private fun pageAlive(page: BrowserPage): Boolean = runCatching {
        page.connection.call("Runtime.evaluate", mapOf("expression" to "1"), 3)
        true
    }.getOrDefault(false)

    /** 浏览器级 CDP 读 vtoken cookie, 不依赖页面 WS 存活, 不回写 StarBot 凭据库。 */
    private fun readVtokenCookie(): String? = runCatching {
        val connection = runtime.browser ?: return@runCatching null
        val cookies = connection.call("Storage.getCookies").getJSONArray("cookies") ?: return@runCatching null
        cookies.filterIsInstance<JSONObject>()
            .filter { "x-bili-gaia-vtoken".equals(it.getString("name"), ignoreCase = true) }
            .maxByOrNull { it.getLongValue("expires") }
            ?.getString("value")
    }.getOrNull()
}
