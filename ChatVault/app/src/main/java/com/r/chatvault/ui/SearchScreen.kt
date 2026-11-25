package com.r.chatvault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.r.chatvault.data.Attachment
import com.r.chatvault.data.Fmt
import com.r.chatvault.data.SearchHit
import com.r.chatvault.vm.AppViewModel
import com.r.chatvault.vm.UiState

fun highlightAll(text: String, keywords: List<String>, color: androidx.compose.ui.graphics.Color): AnnotatedString {
    val ranges = ArrayList<IntRange>()
    for (kw in keywords) {
        if (kw.isBlank()) continue
        var from = 0
        while (from <= text.length) {
            val idx = text.indexOf(kw, from, ignoreCase = true)
            if (idx < 0) break
            ranges.add(idx until (idx + kw.length))
            from = idx + kw.length
        }
    }
    return buildAnnotatedString {
        append(text)
        val style = SpanStyle(background = color, fontWeight = FontWeight.Medium)
        for (r in ranges) addStyle(style, r.first, r.last + 1)
    }
}

private val typeFilters = listOf(
    "all" to "全部",
    "title" to "会话",
    "message" to "消息",
    "code" to "代码",
    "clip" to "摘录",
    "file" to "附件"
)

@Composable
fun SearchScreen(vm: AppViewModel, state: UiState, onOpen: (SearchHit) -> Unit) {
    val keywords = remember(state.query) {
        state.query.split(Regex("\\s+")).filter { it.isNotBlank() }
    }
    var viewer by remember { mutableStateOf<Attachment?>(null) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::search,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            placeholder = { Text("搜消息、思考过程、代码、摘录、附件文本", style = MaterialTheme.typography.bodySmall) },
            leadingIcon = { Icon(Icons.Outlined.Search, null, modifier = Modifier.size(18.dp)) }
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            typeFilters.forEach { (key, label) ->
                val selected = if (key == "all") state.searchTypes.isEmpty() else state.searchTypes == setOf(key)
                FilterChip(
                    selected = selected,
                    onClick = {
                        vm.setSearchTypes(if (key == "all") emptySet() else setOf(key))
                    },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        if (state.query.isBlank()) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "试试这些关键词",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val samples = listOf("OpenWrt", "Magisk", "adb", "ffmpeg", "DNS", "分区", "curl", "开机动画")
                samples.chunked(4).forEach { row ->
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { s ->
                            Text(
                                s,
                                Modifier
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                    .clickable { vm.search(s) }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                Text(
                    "搜索会同时覆盖正文、思考过程、代码片段、摘录和文本文档。",
                    Modifier.padding(top = 18.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (state.searching) "搜索中…" else "${state.hits.size} 条匹配",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (state.hits.isEmpty() && !state.searching) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有找到匹配内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.hits) { hit ->
                    ResultRow(hit, keywords) {
                        if (hit.type == "file" && hit.attachment != null) {
                            viewer = hit.attachment
                        } else {
                            onOpen(hit)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    viewer?.let { a -> AttachmentViewer(a) { viewer = null } }
}

@Composable
private fun ResultRow(hit: SearchHit, keywords: List<String>, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (label, color) = when (hit.type) {
                "title" -> "会话" to MaterialTheme.colorScheme.primary
                "code" -> "代码 ${hit.lang ?: ""}".trim() to MaterialTheme.colorScheme.secondary
                "clip" -> "摘录" to MaterialTheme.colorScheme.tertiary
                "file" -> "附件" to MaterialTheme.colorScheme.onSurfaceVariant
                else -> (if (hit.role == "user") "我" else "GPT") to
                    if (hit.role == "user") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                label,
                Modifier
                    .background(color.copy(alpha = 0.15f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
            Text(
                highlightAll(
                    "  ${hit.convTitle}",
                    keywords,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                Fmt.date(hit.ts),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            highlightAll(hit.snippet, keywords, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            Modifier.padding(top = 5.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
