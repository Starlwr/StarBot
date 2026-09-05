package com.starlwr.bot.bilibili.report

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** AWT port of the v2 20-division interaction diagram. */
internal object V2InteractionChartRenderer {
    private const val DIVISIONS = 20
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun render(
        samples: Map<Long, Double>,
        startedAt: Long,
        endedAt: Long,
        cumulative: Boolean = false,
        showNumericAxis: Boolean = true,
        width: Int = 900,
        height: Int = 500
    ): BufferedImage {
        val start = startedAt.coerceAtMost(endedAt)
        val end = max(start + 1_000, endedAt)
        val values = divide(samples, start, end, cumulative)
        val signed = cumulative && values.any { it < 0.0 }
        val maxAbs = max(1.0, values.maxOf { abs(it) })
        val yMin = if (signed) -niceCeiling(maxAbs) else 0.0
        val yMax = niceCeiling(maxAbs)

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        quality(g)
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        val left = 72
        val right = 24
        val top = 22
        val bottom = 58
        val plotWidth = width - left - right
        val plotHeight = height - top - bottom
        val zeroY = yToPixel(0.0, yMin, yMax, top, plotHeight)

        g.font = Font("SansSerif", Font.PLAIN, 15)
        g.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(5f, 5f), 0f)
        repeat(6) { index ->
            val ratio = index / 5.0
            val value = yMax - (yMax - yMin) * ratio
            val y = top + (plotHeight * ratio).toInt()
            g.color = Color(220, 220, 220)
            g.drawLine(left, y, left + plotWidth, y)
            if (showNumericAxis) {
                g.color = Color(105, 105, 105)
                val label = format(value)
                g.drawString(label, left - g.fontMetrics.stringWidth(label) - 8, y + 5)
            }
        }
        repeat(5) { index ->
            val ratio = index / 4.0
            val x = left + (plotWidth * ratio).toInt()
            val label = timeFormat.format(Instant.ofEpochMilli(start + ((end - start) * ratio).toLong()).atZone(ZoneId.systemDefault()))
            g.color = Color(105, 105, 105)
            val labelX = (x - g.fontMetrics.stringWidth(label) / 2).coerceIn(0, width - g.fontMetrics.stringWidth(label))
            g.drawString(label, labelX, height - 20)
        }

        g.stroke = BasicStroke(2f)
        g.color = Color(90, 90, 90)
        g.drawLine(left, zeroY, left + plotWidth + 5, zeroY)
        g.drawLine(left, top + plotHeight, left, top - 5)
        g.drawLine(left + plotWidth + 5, zeroY, left + plotWidth - 4, zeroY - 5)
        g.drawLine(left + plotWidth + 5, zeroY, left + plotWidth - 4, zeroY + 5)
        g.drawLine(left, top - 5, left - 5, top + 4)
        g.drawLine(left, top - 5, left + 5, top + 4)

        val points = smooth(values, 10)
        val oldComposite = g.composite
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.22f)
        points.zipWithNext().forEach { (a, b) ->
            val x1 = left + (a.first * plotWidth).toInt()
            val x2 = left + (b.first * plotWidth).toInt()
            val y1 = yToPixel(a.second, yMin, yMax, top, plotHeight)
            val y2 = yToPixel(b.second, yMin, yMax, top, plotHeight)
            g.color = if ((a.second + b.second) / 2 >= 0) Color(251, 114, 153) else Color(45, 160, 75)
            val area = Path2D.Double().apply {
                moveTo(x1.toDouble(), zeroY.toDouble()); lineTo(x1.toDouble(), y1.toDouble())
                lineTo(x2.toDouble(), y2.toDouble()); lineTo(x2.toDouble(), zeroY.toDouble()); closePath()
            }
            g.fill(area)
        }
        g.composite = oldComposite

        g.color = Color(238, 73, 121)
        g.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val line = Path2D.Double()
        points.forEachIndexed { index, point ->
            val x = left + point.first * plotWidth
            val y = yToPixel(point.second, yMin, yMax, top, plotHeight).toDouble()
            if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        g.draw(line)
        g.dispose()
        return image
    }

    private fun divide(samples: Map<Long, Double>, start: Long, end: Long, cumulative: Boolean): DoubleArray {
        val result = DoubleArray(if (cumulative) DIVISIONS + 1 else DIVISIONS + 1)
        val duration = (end - start).coerceAtLeast(1)
        samples.forEach { (timestamp, value) ->
            if (timestamp in start..end) {
                val index = (((timestamp - start).toDouble() / duration) * DIVISIONS).toInt().coerceIn(0, DIVISIONS - 1)
                result[index] += value
            }
        }
        if (cumulative) {
            var total = 0.0
            for (index in 0 until DIVISIONS) { total += result[index]; result[index] = total }
            result[DIVISIONS] = total
        } else {
            result[DIVISIONS] = 0.0 // v2 explicitly closes interaction curves at the live end.
        }
        return result
    }

    private fun smooth(values: DoubleArray, samplesPerSegment: Int): List<Pair<Double, Double>> {
        if (values.size < 3) return values.mapIndexed { i, value -> i.toDouble() / (values.size - 1).coerceAtLeast(1) to value }
        val output = ArrayList<Pair<Double, Double>>((values.size - 1) * samplesPerSegment + 1)
        for (index in 0 until values.lastIndex) {
            val p0 = values[(index - 1).coerceAtLeast(0)]
            val p1 = values[index]
            val p2 = values[index + 1]
            val p3 = values[(index + 2).coerceAtMost(values.lastIndex)]
            repeat(samplesPerSegment) { part ->
                val t = part.toDouble() / samplesPerSegment
                val t2 = t * t
                val t3 = t2 * t
                val value = 0.5 * ((2 * p1) + (-p0 + p2) * t +
                    (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
                output += ((index + t) / values.lastIndex) to value
            }
        }
        output += 1.0 to values.last()
        return output
    }

    private fun yToPixel(value: Double, min: Double, max: Double, top: Int, height: Int): Int =
        (top + (max - value) / (max - min).coerceAtLeast(1.0) * height).toInt().coerceIn(top, top + height)

    private fun niceCeiling(value: Double): Double {
        val scale = when {
            value <= 10 -> 1.0
            value <= 100 -> 10.0
            value <= 1_000 -> 100.0
            else -> 1_000.0
        }
        return ceil(value / scale) * scale
    }

    private fun format(value: Double): String = if (abs(value - value.toLong()) < 0.001) value.toLong().toString()
        else java.text.DecimalFormat("0.##").format(value)

    private fun quality(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    }
}
