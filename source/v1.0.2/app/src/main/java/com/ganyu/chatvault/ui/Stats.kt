package com.ganyu.chatvault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ganyu.chatvault.data.Fmt
import com.ganyu.chatvault.data.MonthlyStat
import com.ganyu.chatvault.data.Palette
import com.ganyu.chatvault.data.StatsBundle
import com.ganyu.chatvault.vm.AppViewModel
import com.ganyu.chatvault.vm.UiState
import java.time.YearMonth
import kotlin.math.roundToInt

private enum class Metric(val label: String) {
    CONVS("会话数"), MESSAGES("消息数"), CHARS("字数(千)")
}

private enum class StatsTab(val label: String) {
    TREND("趋势"), TOPIC("主题"), HOUR("时段"), MODEL("模型")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(vm: AppViewModel, state: UiState) {
    val stats = state.stats
    var tab by remember { mutableStateOf(StatsTab.TREND) }
    var metric by remember { mutableStateOf(Metric.CONVS) }
    var config by remember { mutableStateOf(AxisConfig()) }
    var configOpen by remember { mutableStateOf(false) }
    var yManual by remember { mutableStateOf(false) }
    var yMaxValue by remember { mutableFloatStateOf(50f) }
    var stepIndex by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("数据总览", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (tab == StatsTab.TREND || tab == StatsTab.HOUR) {
                IconButton(onClick = { configOpen = true }) {
                    Icon(Icons.Outlined.Tune, "图表设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (stats == null) {
            Text("正在统计…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard("会话", Fmt.compact(stats.totalConvs), Modifier.weight(1f))
            StatCard("消息", Fmt.compact(stats.totalMsgs), Modifier.weight(1f))
            StatCard("字数", Fmt.compact(stats.totalChars), Modifier.weight(1f))
            StatCard("附件", Fmt.compact(stats.totalAttachments), Modifier.weight(1f))
        }

        if (stats.missingAttachments > 0) {
            Text(
                "${stats.missingAttachments} 个附件只有记录、没有实体文件（导出包未包含）",
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StatsTab.entries.forEach { t ->
                FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label, style = MaterialTheme.typography.labelSmall) })
            }
        }

        if (stats.years.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = state.year == null,
                    onClick = { vm.setYear(null) },
                    label = { Text("全部年份", style = MaterialTheme.typography.labelSmall) }
                )
                stats.years.take(5).forEach { y ->
                    FilterChip(
                        selected = state.year == y,
                        onClick = { vm.setYear(y) },
                        label = { Text("$y", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }

        when (tab) {
            StatsTab.TREND -> {
                val months = fillMonths(stats.monthly)
                if (months.isEmpty()) {
                    EmptyHint("没有可统计的数据")
                } else {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Metric.entries.forEach { m ->
                            FilterChip(selected = metric == m, onClick = { metric = m }, label = { Text(m.label, style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                    val values = when (metric) {
                        Metric.CONVS -> months.map { it.convs.toFloat() }
                        Metric.MESSAGES -> months.map { it.msgs.toFloat() }
                        Metric.CHARS -> months.map { it.chars / 1000f }
                    }
                    val maxRaw = values.maxOrNull() ?: 0f
                    val spec = ChartSpec(
                        labels = months.map { Fmt.monthShort(it.key) },
                        series = listOf(
                            Series(
                                metric.label,
                                values,
                                if (metric == Metric.MESSAGES) Palette.teal else Palette.blue,
                                asLine = config.lineMode
                            )
                        )
                    )
                    val effective = config.copy(
                        yMax = if (yManual) yMaxValue else null,
                        yStep = stepOptions[stepIndex]
                    )
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        CartesianChart(spec, effective, height = 240)
                        Legend(spec.series)
                        TapHint("点柱子看当月明细 · 右上角可调 ${maxRaw.roundToInt()} 上限与 X 轴区间")
                    }
                    MonSummary(months)
                }
            }

            StatsTab.TOPIC -> {
                SectionTitle("会话主题分布", "按标题关键词自动归类，共 ${stats.totalConvs} 个会话")
                HorizontalBars(stats.categories, Palette.blue, Modifier.padding(16.dp), valueSuffix = " 个")
            }

            StatsTab.HOUR -> {
                val spec = ChartSpec(
                    labels = (0..23).map { "$it" },
                    series = listOf(Series("消息", stats.hours.map { it.count.toFloat() }, Palette.amber, asLine = config.lineMode))
                )
                val effective = config.copy(
                    yMax = if (yManual) yMaxValue else null,
                    yStep = stepOptions[stepIndex]
                )
                Column(Modifier.padding(horizontal = 8.dp)) {
                    Text(
                        "消息在一天内的分布（本地时间）",
                        Modifier.padding(start = 8.dp, top = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CartesianChart(spec, effective, height = 230)
                    Legend(spec.series)
                    TapHint("横轴为 0–23 时 · 点任意柱子查看该小时消息量")
                }
            }

            StatsTab.MODEL -> {
                SectionTitle("回复模型分布", "仅统计带模型标记的助手回复")
                HorizontalBars(stats.models, Palette.teal, Modifier.padding(16.dp), valueSuffix = " 条")
            }
        }

        Column(Modifier.height(24.dp)) {}
    }

    if (configOpen) {
        ModalBottomSheet(onDismissRequest = { configOpen = false }, sheetState = rememberModalBottomSheetState()) {
            ChartConfigPanel(
                config = config,
                onChange = { config = it },
                yManual = yManual,
                onYManual = { yManual = it },
                yMax = yMaxValue,
                onYMax = { yMaxValue = it },
                stepIndex = stepIndex,
                onStepIndex = { stepIndex = it },
                maxIndex = (state.stats?.monthly?.size ?: 1) - 1
            )
        }
    }
}

private val stepOptions = listOf<Float?>(null, 1f, 2f, 5f, 10f, 20f, 50f, 100f)
private val stepLabels = listOf("自动", "1", "2", "5", "10", "20", "50", "100")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChartConfigPanel(
    config: AxisConfig,
    onChange: (AxisConfig) -> Unit,
    yManual: Boolean,
    onYManual: (Boolean) -> Unit,
    yMax: Float,
    onYMax: (Float) -> Unit,
    stepIndex: Int,
    onStepIndex: (Int) -> Unit,
    maxIndex: Int
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("图表设置", style = MaterialTheme.typography.titleMedium)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("手动指定 Y 轴上限", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = yManual, onCheckedChange = onYManual)
        }
        if (yManual) {
            Text("上限 ${yMax.roundToInt()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = yMax, onValueChange = onYMax, valueRange = 5f..600f)
        }

        Text("Y 轴刻度步长", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            stepLabels.forEachIndexed { i, label ->
                FilterChip(selected = stepIndex == i, onClick = { onStepIndex(i) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
            }
        }

        if (maxIndex > 1) {
            Text(
                "X 轴区间 ${config.xFrom + 1} – ${if (config.xTo < 0) maxIndex + 1 else config.xTo + 1}（共 ${maxIndex + 1} 项）",
                style = MaterialTheme.typography.labelMedium
            )
            RangeSlider(
                value = config.xFrom.toFloat()..(if (config.xTo < 0) maxIndex.toFloat() else config.xTo.toFloat()),
                onValueChange = { r ->
                    onChange(config.copy(xFrom = r.start.roundToInt(), xTo = r.endInclusive.roundToInt()))
                },
                valueRange = 0f..maxIndex.toFloat()
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("显示网格线", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = config.showGrid, onCheckedChange = { onChange(config.copy(showGrid = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("显示数值标签", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = config.showValues, onCheckedChange = { onChange(config.copy(showValues = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("折线模式", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = config.lineMode, onCheckedChange = { onChange(config.copy(lineMode = it)) })
        }
        TextButton(onClick = {
            onChange(AxisConfig())
            onYManual(false)
            onStepIndex(0)
        }) { Text("恢复默认") }
        Column(Modifier.height(12.dp)) {}
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, fontSize = 19.sp, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(title: String, sub: String) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MonSummary(months: List<MonthlyStat>) {
    val top = months.maxByOrNull { it.convs }
    val total = months.sumOf { it.convs }
    if (top != null && total > 0) {
        Text(
            "共 ${months.size} 个月 · 最忙是 ${top.key}（${top.convs} 个会话、${top.msgs} 条消息）",
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 补齐缺失月份，避免趋势图断档 */
private fun fillMonths(list: List<MonthlyStat>): List<MonthlyStat> {
    if (list.isEmpty()) return list
    val map = list.associateBy { it.key }
    val first = runCatching { YearMonth.parse(list.first().key) }.getOrNull() ?: return list
    val last = runCatching { YearMonth.parse(list.last().key) }.getOrNull() ?: return list
    val out = ArrayList<MonthlyStat>()
    var cur = first
    var guard = 0
    while (cur <= last && guard < 200) {
        val key = cur.toString()
        out.add(map[key] ?: MonthlyStat(key, 0, 0, 0))
        cur = cur.plusMonths(1)
        guard++
    }
    return out
}
