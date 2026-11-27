package com.r.chatvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.r.chatvault.data.SortMode
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 草稿状态：点「应用」前不改动 UiState */
private class FilterDraft(
    sort: SortMode,
    year: Int?,
    category: String?,
    model: String?,
    from: Long?,
    to: Long?
) {
    var sort by mutableStateOf(sort)
    var year by mutableStateOf(year)
    var category by mutableStateOf(category)
    var model by mutableStateOf(model)
    var from by mutableStateOf(from)
    var to by mutableStateOf(to)
    var pickFrom by mutableStateOf(false)
    var pickTo by mutableStateOf(false)
}

/**
 * 会话列表与统计页共用的筛选面板（底部上拉抽屉）。
 * 年份/主题/模型/创建时间范围与排序都写回同一份 UiState，天然同步。
 * 从左侧抽屉打开时不会关闭抽屉，关掉面板即可回到会话列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    sort: SortMode,
    year: Int?,
    category: String?,
    model: String?,
    dateFrom: Long?,
    dateTo: Long?,
    years: List<Int>,
    categories: List<String>,
    models: List<String>,
    onApply: (SortMode, Int?, String?, String?, Long?, Long?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val draft = remember(sort, year, category, model, dateFrom, dateTo) {
        FilterDraft(sort, year, category, model, dateFrom, dateTo)
    }

    // M3 把“下拉关闭”的位移阈值写死成 56dp，内容里轻轻一拖就关掉。
    // 这里用 confirmValueChange 兜一层：手势至少要拉过 140dp 才允许隐藏；
    // 点遮罩/返回时位移是 0，直接放行。
    val density = LocalDensity.current
    val restingOffset = remember { mutableStateOf<Float?>(null) }
    val stateBox = remember { arrayOfNulls<SheetState>(1) }
    val confirmHide: (SheetValue) -> Boolean = remember {
        { target ->
            if (target != SheetValue.Hidden) {
                true
            } else {
                val st = stateBox[0]
                val base = restingOffset.value
                val offset = st?.let { runCatching { it.requireOffset() }.getOrNull() }
                if (st == null || base == null || offset == null) {
                    true
                } else {
                    val draggedDp = with(density) { (offset - base).toDp().value }
                    draggedDp < 1f || draggedDp > 140f
                }
            }
        }
    }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = confirmHide
    )
    SideEffect { stateBox[0] = sheetState }
    LaunchedEffect(sheetState) {
        // 展开后的位移是整个过程的最小值；下拉只会让它变大，所以取最小值当基准
        snapshotFlow { runCatching { sheetState.requireOffset() }.getOrNull() }
            .collect { offset ->
                if (offset != null) {
                    val base = restingOffset.value
                    if (base == null || offset < base) restingOffset.value = offset
                }
            }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
        ) {
            Text("筛选与排序", style = MaterialTheme.typography.titleMedium)
            Text(
                "与统计图表共用同一组条件；改完后列表和图表一起更新。",
                Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilterBody(
                draft = draft,
                years = years,
                categories = categories,
                models = models,
                onApply = onApply,
                onReset = onReset,
                onDismiss = onDismiss
            )
            Spacer(Modifier.height(28.dp))
        }
    }

    if (draft.pickFrom) {
        DatePickerModal(
            initial = draft.from,
            endOfDay = false,
            onDismiss = { draft.pickFrom = false },
            onPick = { draft.from = it; draft.pickFrom = false }
        )
    }
    if (draft.pickTo) {
        DatePickerModal(
            initial = draft.to,
            endOfDay = true,
            onDismiss = { draft.pickTo = false },
            onPick = { draft.to = it; draft.pickTo = false }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterBody(
    draft: FilterDraft,
    years: List<Int>,
    categories: List<String>,
    models: List<String>,
    onApply: (SortMode, Int?, String?, String?, Long?, Long?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    SectionLabel("排序")
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SortMode.entries.forEach { m ->
            FilterChip(
                selected = draft.sort == m,
                onClick = { draft.sort = m },
                label = { Text(sortLabel(m), style = MaterialTheme.typography.labelSmall) }
            )
        }
    }

    if (years.isNotEmpty()) {
        SectionLabel("年份")
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = draft.year == null,
                onClick = { draft.year = null },
                label = { Text("全部", style = MaterialTheme.typography.labelSmall) }
            )
            years.take(10).forEach { y ->
                FilterChip(
                    selected = draft.year == y,
                    onClick = { draft.year = if (draft.year == y) null else y },
                    label = { Text("$y", style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }

    if (categories.isNotEmpty()) {
        SectionLabel("主题")
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = draft.category == null,
                onClick = { draft.category = null },
                label = { Text("全部", style = MaterialTheme.typography.labelSmall) }
            )
            categories.forEach { cat ->
                FilterChip(
                    selected = draft.category == cat,
                    onClick = { draft.category = if (draft.category == cat) null else cat },
                    label = { Text(cat, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }

    if (models.isNotEmpty()) {
        SectionLabel("模型")
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = draft.model == null,
                onClick = { draft.model = null },
                label = { Text("全部", style = MaterialTheme.typography.labelSmall) }
            )
            models.forEach { m ->
                FilterChip(
                    selected = draft.model == m,
                    onClick = { draft.model = if (draft.model == m) null else m },
                    label = { Text(m, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }

    SectionLabel("创建时间")
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = { draft.pickFrom = true },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Outlined.CalendarMonth, null, modifier = Modifier.size(16.dp))
            Text(
                " 开始：${formatDate(draft.from)}",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
        OutlinedButton(
            onClick = { draft.pickTo = true },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Outlined.CalendarMonth, null, modifier = Modifier.size(16.dp))
            Text(
                " 结束：${formatDate(draft.to)}",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
    if (draft.from != null || draft.to != null) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            TextButton(onClick = { draft.from = null; draft.to = null }) {
                Icon(Icons.Outlined.Close, null, modifier = Modifier.size(14.dp))
                Text(" 清除时间", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TextButton(
            onClick = {
                onReset()
                onDismiss()
            },
            modifier = Modifier.weight(1f)
        ) { Text("重置") }
        Button(
            onClick = {
                onApply(draft.sort, draft.year, draft.category, draft.model, draft.from, draft.to)
                onDismiss()
            },
            modifier = Modifier.weight(1f)
        ) { Text("应用") }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(top = 14.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun sortLabel(m: SortMode): String = when (m) {
    SortMode.NEWEST -> "最新优先"
    SortMode.OLDEST -> "最早优先"
    SortMode.MESSAGES -> "消息最多"
    SortMode.CHARS -> "字数最多"
    SortMode.TITLE -> "标题 A-Z"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerModal(
    initial: Long?,
    endOfDay: Boolean,
    onDismiss: () -> Unit,
    onPick: (Long?) -> Unit
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial?.let { toUtcMillis(it) })
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPick(state.selectedDateMillis?.let { fromUtcMillis(it, endOfDay) })
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    ) {
        DatePicker(state)
    }
}

private fun formatDate(ts: Long?): String =
    if (ts == null) "不限" else Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).toLocalDate().toString()

/** 本地日期 -> DatePicker 需要的 UTC 零点毫秒 */
private fun toUtcMillis(ts: Long): Long {
    val date = Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).toLocalDate()
    return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

/** DatePicker 返回的 UTC 零点毫秒 -> 本地当天起止秒 */
private fun fromUtcMillis(millis: Long, endOfDay: Boolean): Long {
    val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
    return if (endOfDay) {
        date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() - 1
    } else {
        date.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
    }
}
