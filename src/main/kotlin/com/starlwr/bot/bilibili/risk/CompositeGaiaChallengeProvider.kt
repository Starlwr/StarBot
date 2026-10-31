package com.starlwr.bot.bilibili.risk

import com.starlwr.bot.core.plugin.StarBotComponent
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Primary

/**
 * Gaia 挑战组合分发器：优先浏览器交互式解算（人工过码），
 * 浏览器不可用/超时/失败时回落到报告+退避等待（ReportingGaiaChallengeProvider）。
 *
 * 可插拔性: BrowserGaiaVtokenProvider 在 CfT 运行时缺席时 submit() 返回 null，
 * 本分发器据 null 回落，StarBot 不强依赖浏览器存在。
 */
@StarBotComponent
@Primary
class CompositeGaiaChallengeProvider(
    private val browserProvider: BrowserGaiaVtokenProvider,
    private val reportingProvider: ReportingGaiaChallengeProvider,
) : GaiaChallengeProvider {
    private val log = LoggerFactory.getLogger(javaClass)
    override fun hasPendingBrowserVerification(): Boolean = browserProvider.hasPendingBrowserVerification()

    override fun submit(challenge: GaiaChallenge): GaiaResolution? {
        val resolution = try { browserProvider.submit(challenge) }
            catch (e: BrowserRecoveryPendingException) { throw e }
            catch (e: Exception) {
                log.warn("浏览器 Gaia 解算异常, 转入退避等待: {}", e.javaClass.simpleName)
                null
            }
        if (resolution?.resolved == true) return resolution
        return reportingProvider.submit(challenge)
    }
}
