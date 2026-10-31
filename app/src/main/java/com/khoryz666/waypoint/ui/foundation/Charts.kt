package com.khoryz666.waypoint.ui.foundation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** One bar or point: its label for the readout, and its value (null = a gap, drawn as nothing). */
data class ChartPoint(val label: String, val value: Float?, val readout: String)

/**
 * A single-series bar chart: one hue, thin bars with rounded tops on a shared baseline, a 2 px
 * gap between bars, and an optional recessive target band. Tapping a bar shows its value.
 */
@Composable
fun BarChart(points: List<ChartPoint>, modifier: Modifier = Modifier, band: ClosedFloatingPointRange<Float>? = null) {
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val bar = MaterialTheme.colorScheme.primary
    val bandColor = MaterialTheme.colorScheme.surfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val max = maxOf(points.mapNotNull { it.value }.maxOrNull() ?: 1f, band?.endInclusive ?: 0f) * 1.1f
    Column(modifier) {
        Text(selected?.let { points[it].readout } ?: " ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(
            Modifier.fillMaxWidth().height(140.dp).pointerInput(points) {
                detectTapGestures { o -> if (points.isNotEmpty()) selected = (o.x / (size.width / points.size)).toInt().coerceIn(0, points.lastIndex) }
            },
        ) {
            if (points.isEmpty()) return@Canvas
            fun y(v: Float) = size.height * (1 - v / max)
            band?.let { drawRect(bandColor, Offset(0f, y(it.endInclusive)), Size(size.width, y(it.start) - y(it.endInclusive))) }
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
            val slot = size.width / points.size
            val gap = 2.dp.toPx()
            val width = (slot - gap).coerceIn(1f, 24.dp.toPx())
            val radius = minOf(4.dp.toPx(), width / 2)
            points.forEachIndexed { i, p ->
                val v = p.value ?: return@forEachIndexed
                val x = i * slot + (slot - width) / 2
                val top = y(v)
                val alpha = if (selected == null || selected == i) 1f else 0.45f
                // Rounded top, square base: a rounded rect plus a square patch over its bottom corners.
                drawRoundRect(bar.copy(alpha = alpha), Offset(x, top), Size(width, size.height - top), CornerRadius(radius))
                if (size.height - top > radius) drawRect(bar.copy(alpha = alpha), Offset(x, size.height - radius), Size(width, radius))
            }
        }
    }
}

/** A single-series 2 px line with a crosshair readout on tap. */
@Composable
fun LineChart(points: List<ChartPoint>, modifier: Modifier = Modifier) {
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val values = points.mapNotNull { it.value }
    Column(modifier) {
        Text(selected?.let { points[it].readout } ?: " ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(
            Modifier.fillMaxWidth().height(140.dp).pointerInput(points) {
                detectTapGestures { o -> if (points.size > 1) selected = (o.x / size.width * points.lastIndex + 0.5f).toInt().coerceIn(0, points.lastIndex) }
            },
        ) {
            if (values.isEmpty()) return@Canvas
            if (values.size == 1) {
                // One day of data: a single marker, so the chart isn't blank.
                val i = points.indexOfFirst { it.value != null }
                drawCircle(line, 4.dp.toPx(), Offset(if (points.size > 1) size.width * i / points.lastIndex else size.width / 2, size.height / 2))
                return@Canvas
            }
            val min = minOf(values.min(), 0f)
            val max = values.max().let { if (it == min) it + 1 else it }
            fun x(i: Int) = size.width * i / points.lastIndex
            fun y(v: Float) = size.height * (1 - (v - min) / (max - min)) * 0.92f + size.height * 0.04f
            drawLine(grid, Offset(0f, y(0f)), Offset(size.width, y(0f)), strokeWidth = 1f)
            val path = Path()
            var started = false
            points.forEachIndexed { i, p ->
                val v = p.value ?: return@forEachIndexed
                if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
            }
            drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
            selected?.let { i ->
                drawLine(grid, Offset(x(i), 0f), Offset(x(i), size.height), strokeWidth = 1.dp.toPx())
                points[i].value?.let { drawCircle(line, 4.dp.toPx(), Offset(x(i), y(it))) }
            }
        }
    }
}
