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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.r.chatvault.R
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
    "all" to R.string.common_all,
    "title" to R.string.nav_chats,
    "message" to R.string.common_messages,
    "code" to R.string.common_code,
    "clip" to R.string.common_clips,
    "file" to R.string.common_attachments
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
            placeholder = { Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodySmall) },
            leadingIcon = { Icon(Icons.Outlined.Search, null, modifier = Modifier.size(18.dp)) }
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            typeFilters.forEach { (key, labelRes) ->
                val selected = if (key == "all") state.searchTypes.isEmpty() else state.searchTypes == setOf(key)
                FilterChip(
                    selected = selected,
                    onClick = {
                        vm.setSearchTypes(if (key == "all") emptySet() else setOf(key))
                    },
                    label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        if (state.query.isBlank()) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    stringResource(R.string.search_try),
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
                    stringResource(R.string.search_scope),
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
                if (state.searching) stringResource(R.string.searching)
                else stringResource(R.string.match_count, state.hits.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (state.hits.isEmpty() && !state.searching) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.search_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                "title" -> stringResource(R.string.nav_chats) to MaterialTheme.colorScheme.primary
                "code" -> stringResource(R.string.code_with_lang, hit.lang ?: "").trim() to
                    MaterialTheme.colorScheme.secondary
                "clip" -> stringResource(R.string.common_clips) to MaterialTheme.colorScheme.tertiary
                "file" -> stringResource(R.string.common_attachments) to MaterialTheme.colorScheme.onSurfaceVariant
                else -> (if (hit.role == "user") stringResource(R.string.common_me) else "GPT") to
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
