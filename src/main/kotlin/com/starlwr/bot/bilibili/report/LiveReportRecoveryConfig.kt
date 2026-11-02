package com.starlwr.bot.bilibili.report

import com.starlwr.bot.core.plugin.StarBotComponent
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import java.time.Duration

@ConfigurationProperties("starbot.bilibili.live-report.recovery")
class LiveReportRecoveryProperties {
    var enabled: Boolean = true
    var sameSessionTolerance: Duration = Duration.ofMinutes(10)
    var pendingCloseDelay: Duration = Duration.ofMinutes(10)
    var pendingCloseRetryInterval: Duration = Duration.ofMinutes(2)
    var pendingCloseMaxDuration: Duration = Duration.ofMinutes(30)
    var trustedTimeCache: Duration = Duration.ofMinutes(5)
    var trustedTimeTimeout: Duration = Duration.ofSeconds(3)
    var cloudflareTraceUrl: String = "https://www.cloudflare-cn.com/cdn-cgi/trace"

    fun valid(): Boolean = sameSessionTolerance > Duration.ZERO && pendingCloseDelay > Duration.ZERO &&
        pendingCloseRetryInterval > Duration.ZERO && pendingCloseMaxDuration >= pendingCloseDelay &&
        trustedTimeCache >= Duration.ZERO && trustedTimeTimeout > Duration.ZERO
}

@StarBotComponent
@EnableConfigurationProperties(LiveReportRecoveryProperties::class)
class LiveReportRecoveryConfig
