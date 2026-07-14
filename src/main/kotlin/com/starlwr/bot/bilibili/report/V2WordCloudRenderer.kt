package com.starlwr.bot.bilibili.report

import com.huaban.analysis.jieba.JiebaSegmenter
import com.huaban.analysis.jieba.WordDictionary
import org.slf4j.LoggerFactory
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kotlin port of the v2 word-cloud pipeline: Jieba segmentation, frequency
 * counting, optional user dictionary/stop words, and collision-based layout.
 */
internal object V2WordCloudRenderer {
    private val log = LoggerFactory.getLogger(javaClass)
    private val segmenter = JiebaSegmenter()
    private val loadedDictionaries = ConcurrentHashMap.newKeySet<Path>()
    private val tokenContent = Regex("[\\p{L}\\p{N}_]")
    private val colors = listOf(
        Color(251, 114, 153), Color(0, 161, 214), Color(126, 87, 194),
        Color(76, 175, 80), Color(255, 143, 0), Color(33, 150, 243), Color(156, 39, 176)
    )
    private val bundledFont: Font? by lazy {
        runCatching {
            requireNotNull(V2WordCloudRenderer::class.java.getResourceAsStream("/fonts/cloud.ttf"))
                .use { Font.createFont(Font.TRUETYPE_FONT, it) }
        }.onFailure { log.warn("v2 词云字体加载失败，回退到 SansSerif: {}", it.toString()) }.getOrNull()
    }

    fun render(texts: List<String>, config: LiveReportTargetConfig, width: Int = 900, height: Int = 450): BufferedImage {
        val frequencies = frequencies(texts, config).entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
        ).take(config.maxWords)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        quality(g)
        g.color = Color.WHITE
        g.fillRoundRect(0, 0, width, height, 20, 20)
        if (frequencies.isNotEmpty()) {
            val maxFrequency = frequencies.first().value.coerceAtLeast(1)
            val occupied = ArrayList<Rectangle>(frequencies.size)
            frequencies.forEachIndexed { index, entry ->
                place(g, entry.key, entry.value, maxFrequency, index, config.maxFontSize, width, height, occupied)
            }
        }
        g.color = Color(190, 190, 190)
        g.stroke = BasicStroke(1f)
        g.drawRoundRect(0, 0, width - 1, height - 1, 20, 20)
        g.dispose()
        return image
    }

    @Synchronized
    private fun frequencies(texts: List<String>, config: LiveReportTargetConfig): Map<String, Int> {
        config.dictionary?.takeIf(String::isNotBlank)?.let { raw ->
            val path = Path.of(raw).toAbsolutePath().normalize()
            if (loadedDictionaries.add(path)) runCatching {
                WordDictionary.getInstance().loadUserDict(path, StandardCharsets.UTF_8)
            }.onFailure {
                loadedDictionaries.remove(path)
                log.warn("载入 v2 词云自定义词典失败: {}", path, it)
            }
        }
        val stopWords = DEFAULT_STOP_WORDS + readLines(config.stopWords)
        val counts = HashMap<String, Int>()
        texts.forEach { text ->
            segmenter.sentenceProcess(text).asSequence().map(String::trim)
                .filter { it.isNotEmpty() && tokenContent.containsMatchIn(it) && it !in stopWords }
                .forEach { counts.merge(it, 1, Int::plus) }
        }
        return counts
    }

    private fun place(
        g: Graphics2D,
        word: String,
        frequency: Int,
        maxFrequency: Int,
        index: Int,
        configuredMaxFont: Int,
        width: Int,
        height: Int,
        occupied: MutableList<Rectangle>
    ) {
        val minFont = 18
        var size = (minFont + (configuredMaxFont - minFont) * sqrt(frequency.toDouble() / maxFrequency))
            .roundToInt().coerceIn(minFont, configuredMaxFont)
        val vertical = abs(word.hashCode()) % 10 == 0 // Python wordcloud defaults to roughly 90% horizontal.
        while (size >= minFont) {
            val font = (bundledFont ?: Font("SansSerif", Font.PLAIN, size)).deriveFont(size.toFloat())
            g.font = font
            val fm = g.fontMetrics
            val textWidth = fm.stringWidth(word).coerceAtLeast(1)
            val textHeight = fm.height.coerceAtLeast(1)
            val boxWidth = if (vertical) textHeight else textWidth
            val boxHeight = if (vertical) textWidth else textHeight
            val point = spiralPosition(boxWidth, boxHeight, width, height, occupied, index)
            if (point != null) {
                val rectangle = Rectangle(point.first, point.second, boxWidth, boxHeight)
                occupied += Rectangle(rectangle.x - 3, rectangle.y - 3, rectangle.width + 6, rectangle.height + 6)
                g.color = colors[index % colors.size]
                if (vertical) {
                    val transform = g.transform
                    g.translate(rectangle.x.toDouble(), (rectangle.y + rectangle.height).toDouble())
                    g.rotate(-PI / 2)
                    g.drawString(word, 0, fm.ascent)
                    g.transform = transform
                } else {
                    g.drawString(word, rectangle.x, rectangle.y + fm.ascent)
                }
                return
            }
            size -= if (size > 80) 10 else 4
        }
    }

    private fun spiralPosition(
        boxWidth: Int,
        boxHeight: Int,
        width: Int,
        height: Int,
        occupied: List<Rectangle>,
        index: Int
    ): Pair<Int, Int>? {
        if (boxWidth > width - 12 || boxHeight > height - 12) return null
        val startAngle = index * 2.399963229728653 // golden angle, deterministic across runs
        repeat(3_000) { step ->
            val angle = startAngle + step * 0.24
            val radius = 1.5 + angle * 1.18
            val x = (width / 2.0 + cos(angle) * radius - boxWidth / 2.0).roundToInt()
            val y = (height / 2.0 + sin(angle) * radius * 0.55 - boxHeight / 2.0).roundToInt()
            if (x < 6 || y < 6 || x + boxWidth > width - 6 || y + boxHeight > height - 6) return@repeat
            val candidate = Rectangle(x - 3, y - 3, boxWidth + 6, boxHeight + 6)
            if (occupied.none(candidate::intersects)) return x to y
        }
        return null
    }

    private fun readLines(path: String?): Set<String> = path?.takeIf(String::isNotBlank)?.let { raw ->
        runCatching { Files.readAllLines(Path.of(raw), StandardCharsets.UTF_8).map(String::trim).filter(String::isNotBlank).toSet() }
            .onFailure { log.warn("载入 v2 词云停用词失败: {}", raw, it) }.getOrDefault(emptySet())
    } ?: emptySet()

    private fun quality(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    }

    private val DEFAULT_STOP_WORDS = setOf(" ", "\t", "\n", "这个", "那个", "就是", "然后", "但是", "可以", "不是", "一个")
}
