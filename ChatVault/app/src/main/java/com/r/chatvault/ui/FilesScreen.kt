package com.r.chatvault.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.r.chatvault.data.Attachment
import com.r.chatvault.vm.UiState

@Composable
fun FilesScreen(state: UiState) {
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var viewer by remember { mutableStateOf<Attachment?>(null) }

    val images = state.allFiles.filter { it.kind == "image" }
    val docs = state.allFiles.filter { it.kind != "image" }
    val orphans = state.allFiles.filter { it.convId == null }
    val missing = state.allFiles.filter { !it.exists }

    val base = when (tab) {
        0 -> state.allFiles
        1 -> missing
        2 -> images
        3 -> docs
        else -> orphans
    }
    val list = remember(base, query) {
        if (query.isBlank()) base
        else base.filter { it.name.contains(query, true) || (it.convId ?: "").contains(query, true) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(tab == 0, { tab = 0 }, { Text("全部 ${state.allFiles.size}", style = MaterialTheme.typography.labelSmall) })
            FilterChip(tab == 1, { tab = 1 }, { Text("缺失 ${missing.size}", style = MaterialTheme.typography.labelSmall) })
            FilterChip(tab == 2, { tab = 2 }, { Text("图片 ${images.size}", style = MaterialTheme.typography.labelSmall) })
            FilterChip(tab == 3, { tab = 3 }, { Text("文档 ${docs.size}", style = MaterialTheme.typography.labelSmall) })
            FilterChip(tab == 4, { tab = 4 }, { Text("未引用 ${orphans.size}", style = MaterialTheme.typography.labelSmall) })
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            placeholder = { Text("按文件名筛选", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall
        )

        Text(
            buildString {
                append("共 ${list.size} 个文件")
                if (missing.isNotEmpty()) append(" · 其中 ${missing.size} 个缺失实体")
                append(" · 图片可直接点开缩放查看")
            },
            Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (tab == 2 && query.isBlank()) {
            ImageGrid(list) { viewer = it }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { a ->
                    FileRow(a) { viewer = a }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    viewer?.let { a -> AttachmentViewer(a) { viewer = null } }
}
