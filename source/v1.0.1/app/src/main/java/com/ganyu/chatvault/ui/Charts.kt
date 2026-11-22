package com.ganyu.chatvault.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ganyu.chatvault.data.Fmt
import com.ganyu.chatvault.data.NamedCount
import kotlin.math.ceil

data class Series(
    val name: String,
    val values: List<Float>,
    val color: Color,
    val asLine: Boolean = false
)

data class ChartSpec(
    val labels: List<String>,
    val series: List<Series>
)

data class AxisConfig(
    val yMax: Float? = null,
    val yStep: Float? = null,
    val xFrom: Int = 0,
    val xTo: Int = -1,
    val showGrid: Boolean = true,
    val showValues: Boolean = false,
    val lineMode: Boolean = false
)

private fun niceCeil(v: Float): Float {
    if (v <= 0f) return 10f
    val exp = Math.pow(10.0, Math.floor(Math.log10(v.toDouble()))).toFloat()
    val f = v / exp
    val nf = when {
        f <= 1f -> 1f
        f <= 2f -> 2f
        f <= 2.5f -> 2.5f
        f <= 5f -> 5f
        else -> 10f
    }
    return nf * exp
}

/** 竖向柱状 / 折线图，支持 X 区间与 Y 轴刻度手动调节 */
@Composable
fun CartesianChart(
    spec: ChartSpec,
    config: AxisConfig,
    modifier: Modifier = Modifier,
    height: Int = 230,
    onIndexSelected: (Int) -> Unit = {}
) {
    val colors = LocalChatColors.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outline
    val measurer = rememberTextMeasurer()
    var selected by remember { mutableIntStateOf(-1) }

    val total = spec.labels.size
    val from = config.xFrom.coerceIn(0, (total - 1).coerceAtLeast(0))
    val to = (if (config.xTo < 0 || config.xTo >= total) total - 1 else config.xTo).coerceIn(from, (total - 1).coerceAtLeast(0))
    val count = to - from + 1

    val rawMax = (from..to).flatMap { i -> spec.series.map { it.values.getOrElse(i) { 0f } } }.maxOrNull() ?: 0f
    val maxValue = config.yMax?.takeIf { it > 0f } ?: niceCeil(rawMax * 1.12f)
    val step = config.yStep?.takeIf { it > 0f } ?: (maxValue / 4f)
    val tickCount = ceil(maxValue / step).toInt().coerceIn(1, 12)

    Box(modifier.fillMaxWidth().height(height.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height.dp)
                .pointerInput(spec, from, to) {
                    detectTapGestures { offset ->
                        val left = 40.dp.toPx()
                        val right = size.width - 8.dp.toPx()
                        val plotW = right - left
                        if (plotW <= 0) return@detectTapGestures
                        val slot = plotW / count
                        val idx = from + ((offset.x - left) / slot).toInt().coerceIn(0, count - 1)
                        selected = if (selected == idx) -1 else idx
                        onIndexSelected(selected)
                    }
                }
        ) {
            val labelStyle = TextStyle(fontSize = 10.sp, color = muted)
            val left = 40.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 16.dp.toPx()
            val bottom = size.height - 22.dp.toPx()
            val plotW = right - left
            val plotH = bottom - top
            if (plotW <= 0 || plotH <= 0) return@Canvas

            // Y 轴网格与刻度
            for (i in 0..tickCount) {
                val v = step * i
                if (v > maxValue * 1.001f) continue
                val y = bottom - (v / maxValue) * plotH
                if (config.showGrid) {
                    drawLine(colors.grid, Offset(left, y), Offset(right, y), strokeWidth = 1f)
                }
                drawLabel(measurer, formatTick(v, step), left - 6.dp.toPx(), y - 6.dp.toPx(), labelStyle, alignRight = true)
            }

            val slot = plotW / count
            val groupCount = spec.series.size
            val barW = if (config.lineMode) 0f else (slot * 0.62f / groupCount).coerceAtMost(16.dp.toPx())

            // 选中列背景
            if (selected in from..to) {
                val x = left + (selected - from) * slot
                drawRect(colors.grid, Offset(x, top), Size(slot, plotH))
            }

            // 柱子
            if (!config.lineMode) {
                for (s in spec.series.indices) {
                    val series = spec.series[s]
                    for (i in from..to) {
                        val v = series.values.getOrElse(i) { 0f }
                        val h = (v / maxValue) * plotH
                        val x = left + (i - from) * slot + slot * 0.19f + s * barW
                        val y = bottom - h
                        drawRoundRect(
                            color = series.color,
                            topLeft = Offset(x, y),
                            size = Size(barW * 0.92f, h.coerceAtLeast(1f)),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                        )
                        if (config.showValues && count <= 14) {
                            drawLabel(
                                measurer,
                                Fmt.compact(v.toInt()),
                                x + barW * 0.46f,
                                y - 14.dp.toPx(),
                                labelStyle.copy(fontSize = 9.5.sp, color = series.color, fontWeight = FontWeight.Medium),
                                alignCenter = true
                            )
                        }
                    }
                }
            }

            // 折线
            for (series in spec.series) {
                val points = (from..to).map { i ->
                    val v = series.values.getOrElse(i) { 0f }
                    Offset(
                        left + (i - from) * slot + slot / 2f,
                        bottom - (v / maxValue) * plotH
                    )
                }
                if (points.size >= 2) {
                    val path = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        points.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(path, series.color, style = Stroke(width = 2.dp.toPx()))
                }
                points.forEach { p ->
                    drawCircle(series.color, radius = 2.5.dp.toPx(), center = p)
                }
            }

            // X 轴标签
            val stride = if (count <= 7) 1 else ceil(count / 7f).toInt()
            for (i in from..to) {
                if ((i - from) % stride != 0 && i != to) continue
                val x = left + (i - from) * slot + slot / 2f
                drawLabel(measurer, spec.labels.getOrElse(i) { "" }, x, bottom + 6.dp.toPx(), labelStyle, alignCenter = true)
            }

            // 选中提示
            if (selected in from..to) {
                val idx = selected
                val lines = buildList {
                    add(spec.labels.getOrElse(idx) { "" })
                    spec.series.forEach { add("${it.name} ${it.values.getOrElse(idx) { 0f }.toInt()}") }
                }
                val x = left + (idx - from) * slot + slot / 2f
                val tooltipW = lines.maxOf { measurer.measure(AnnotatedString(it), labelStyle).size.width }.toFloat() + 20.dp.toPx()
                val tooltipH = (lines.size * 15 + 10).dp.toPx()
                val tx = (x - tooltipW / 2).coerceIn(2.dp.toPx(), size.width - tooltipW - 2.dp.toPx())
                drawRoundRect(
                    color = surfaceVariant,
                    topLeft = Offset(tx, top - 2.dp.toPx()),
                    size = Size(tooltipW, tooltipH),
                    cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                )
                drawRoundRect(
                    color = outlineColor,
                    topLeft = Offset(tx, top - 2.dp.toPx()),
                    size = Size(tooltipW, tooltipH),
                    cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                    style = Stroke(1f)
                )
                lines.forEachIndexed { i, line ->
                    val style = if (i == 0) labelStyle.copy(color = onSurface, fontWeight = FontWeight.Medium)
                    else labelStyle.copy(color = spec.series.getOrNull(i - 1)?.color ?: onSurface)
                    drawLabel(
                        measurer, line,
                        tx + 10.dp.toPx(),
                        top - 2.dp.toPx() + (6 + i * 15).dp.toPx(),
                        style
                    )
                }
            }
        }
    }
}

private fun formatTick(v: Float, step: Float): String {
    return if (step >= 1f && v == v.toInt().toFloat()) Fmt.compact(v.toInt())
    else String.format(java.util.Locale.CHINA, "%.1f", v)
}

private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    style: TextStyle,
    alignRight: Boolean = false,
    alignCenter: Boolean = false
) {
    if (text.isBlank()) return
    val layout = measurer.measure(AnnotatedString(text), style)
    val dx = when {
        alignCenter -> x - layout.size.width / 2f
        alignRight -> x - layout.size.width
        else -> x
    }
    drawText(layout, topLeft = Offset(dx, y))
}

/** 横向条形（分类占比），用布局实现，文字天然可读 */
@Composable
fun HorizontalBars(
    items: List<NamedCount>,
    color: Color,
    modifier: Modifier = Modifier,
    maxItems: Int = 10,
    valueSuffix: String = ""
) {
    val filtered = items.filter { it.count > 0 }.take(maxItems)
    val max = filtered.maxOfOrNull { it.count } ?: 0
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        filtered.forEach { item ->
            val fraction = if (max > 0) item.count.toFloat() / max else 0f
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${item.count}$valueSuffix",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .height(7.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction.coerceAtLeast(0.012f))
                            .height(7.dp)
                            .background(color, RoundedCornerShape(4.dp))
                    ) {}
                }
            }
        }
    }
}

@Composable
fun Legend(series: List<Series>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        series.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(s.color, CircleShape)) {}
                Text(
                    "  ${s.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun TapHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}
