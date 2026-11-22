package com.r.chatvault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.r.chatvault.data.Fmt
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

@Composable
fun SearchScreen(vm: AppViewModel, state: UiState, onOpen: (String, String) -> Unit) {
    val keywords = remember(state.query) {
        state.query.split(Regex("\\s+")).filter { it.isNotBlank() }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::search,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            placeholder = { Text("搜索所有会话的正文，空格分隔多个关键词", style = MaterialTheme.typography.bodySmall) },
            leadingIcon = { Icon(Icons.Outlined.Search, null, modifier = Modifier.size(18.dp)) }
        )

        if (state.query.isBlank()) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "试试这些关键词",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val samples = listOf("OpenWrt", "Magisk", "校园网", "adb", "路由器", "ffmpeg", "DNS", "分区")
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
            }
            return
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (state.searching) "搜索中…" else "${state.hits.size} 条匹配消息",
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
                items(state.hits, key = { it.msgId }) { hit ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(hit.convId, hit.msgId) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (hit.role == "user") "我" else "GPT",
                                Modifier
                                    .background(
                                        if (hit.role == "user") MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(5.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (hit.role == "user") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "  ${hit.convTitle}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(Fmt.date(hit.ts), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            highlightAll(hit.snippet, keywords, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                            Modifier.padding(top = 5.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
