package com.starlwr.bot.bilibili.report

import com.starlwr.bot.core.config.StarBotCoreProperties
import com.starlwr.bot.core.factory.StarBotCommonPainterFactory
import com.starlwr.bot.core.util.FontUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.info.BuildProperties
import org.springframework.core.io.DefaultResourceLoader
import java.awt.Color
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.Properties
import javax.imageio.ImageIO

class LiveReportPainterTest {
    @Test
    fun `final image is visible and cropped to its content`() {
        val properties = StarBotCoreProperties().apply {
            paint.autoExpandHeight = 5_000
            paint.fonts = listOf("SansSerif")
        }
        val fontUtil = FontUtil(DefaultResourceLoader(), properties).also { it.init() }
        val build = BuildProperties(Properties().apply { setProperty("version", "test") })
        val painter = LiveReportPainter(StarBotCommonPainterFactory(build, properties, fontUtil))
        val now = System.currentTimeMillis()
        val snapshot = LiveReportSnapshot(
            sessionId = "paint-test", uid = 1, roomId = 2, uname = "测试主播",
            startedAt = now - 300_000, endedAt = now
        ).apply {
            apply(ReportDelta(ReportMetric.DANMU, 6, user = ReportUserDelta("1", "观众", count = 6),
                occurredAt = now, text = "测试弹幕内容"))
            metadata["before_fans"] = 100
            metadata["after_fans"] = 101
        }
        val config = LiveReportTargetConfig(
            sections = LiveReportTargetConfig.DEFAULT_SECTIONS + ("fans" to true),
            rankings = mapOf("danmu" to 10), charts = mapOf("danmu" to true), wordCloud = true
        )

        val image = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(painter.paint(snapshot, config))))
        if (System.getProperty("starbot.test.saveImages").toBoolean()) {
            val output = java.nio.file.Path.of("target", "live-report-painter-test.png")
            java.nio.file.Files.createDirectories(output.parent)
            ImageIO.write(image, "PNG", output.toFile())
        }
        assertEquals(1_000, image.width)
        assertTrue(image.height in 300..2_500, "unexpected untrimmed image height ${image.height}")
        val center = Color(image.getRGB(image.width / 2, image.height / 2), true)
        assertTrue(center.red > 240 && center.green > 240 && center.blue > 240,
            "report background should be visible instead of transparent/black")
    }
}
