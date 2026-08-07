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
            apply(ReportDelta(ReportMetric.GIFT, 1, value = 0.1,
                user = ReportUserDelta("3", "礼物用户", count = 1, value = 0.1), occurredAt = now))
            apply(ReportDelta(ReportMetric.SC, 2, value = 123.0, user = ReportUserDelta("2", "supporter", count = 2, value = 123.0), occurredAt = now))
            metadata["before_fans"] = 100
            metadata["after_fans"] = 101
        }
        val config = LiveReportTargetConfig(
            sections = LiveReportTargetConfig.DEFAULT_SECTIONS + ("fans" to true),
            rankings = mapOf("danmu" to 10, "gift" to 10), charts = mapOf("danmu" to true, "sc" to true), wordCloud = true
        )

        val hiddenConfig = config.copy(amounts = mapOf("box" to false, "gift" to false, "sc" to false, "guard" to false))
        val hiddenText = painter.text(snapshot, hiddenConfig)
        assertTrue(!hiddenText.contains("金额"))
        assertTrue(hiddenText.contains("礼物: 1人"))
        assertTrue(hiddenText.contains("SC: 1人"))
        assertTrue(!hiddenText.contains("SC: 2"))

        if (System.getProperty("starbot.test.saveImages").toBoolean()) {
            val hiddenImage = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(painter.paint(snapshot, hiddenConfig))))
            val hiddenOutput = java.nio.file.Path.of("target", "live-report-painter-hidden-amounts-test.png")
            java.nio.file.Files.createDirectories(hiddenOutput.parent)
            ImageIO.write(hiddenImage, "PNG", hiddenOutput.toFile())
        }

        val image = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(painter.paint(snapshot, config))))
        if (System.getProperty("starbot.test.saveImages").toBoolean()) {
            val output = java.nio.file.Path.of("target", "live-report-painter-test.png")
            java.nio.file.Files.createDirectories(output.parent)
            ImageIO.write(image, "PNG", output.toFile())
        }
        assertEquals(1_000, image.width)
        assertTrue(image.height in 300..4_000, "unexpected untrimmed image height ${image.height}")
        val background = Color(image.getRGB(10, image.height / 2), true)
        assertTrue(background.red > 240 && background.green > 240 && background.blue > 240,
            "report background should be visible instead of transparent/black")
    }
}
