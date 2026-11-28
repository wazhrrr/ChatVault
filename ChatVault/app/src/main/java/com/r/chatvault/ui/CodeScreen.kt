package com.r.chatvault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r.chatvault.R
import com.r.chatvault.data.CodeItem
import com.r.chatvault.data.Fmt
import com.r.chatvault.vm.AppViewModel
import com.r.chatvault.vm.UiState

private data class ConvCodeGroup(
    val convId: String,
    val title: String,
    val ts: Long,
    val items: List<CodeItem>
)

private data class MonthCodeGroup(
    val key: String,
    val ts: Long,
    val count: Int,
    val convs: List<ConvCodeGroup>
)

@Composable
fun CodeScreen(vm: AppViewModel, state: UiState, onOpen: (String, String) -> Unit) {
    var expandedMonths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedConvs by remember { mutableStateOf<Set<String>>(emptySet()) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val groups = remember(state.codeItems) {
        state.codeItems.groupBy { Fmt.monthKey(it.ts) }
            .map { (key, items) ->
                val convs = items.groupBy { it.convId }.map { (convId, list) ->
                    ConvCodeGroup(
                        convId = convId,
                        title = list.firstOrNull()?.convTitle ?: "",
                        ts = list.maxOf { it.ts },
                        // 新消息在前；同一条消息里的多个代码块按抽取顺序（id 递增）排列
                    items = list.sortedWith(
                        compareByDescending<CodeItem> { it.ts }
                            .thenByDescending { it.ord }
                            .thenBy { it.id }
                    )
                    )
                }.sortedByDescending { it.ts }
                MonthCodeGroup(
                    key = key,
                    ts = items.maxOf { it.ts },
                    count = items.size,
                    convs = convs
                )
            }
            .sortedByDescending { it.key }
    }
    val forceExpand = state.codeQuery.isNotBlank() || state.codeLang != null

    // 默认展开最新月份，会话仍然保持折叠
    LaunchedEffect(groups.firstOrNull()?.key) {
        val first = groups.firstOrNull()?.key ?: return@LaunchedEffect
        if (expandedMonths.isEmpty()) expandedMonths = setOf(first)
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.codeQuery,
            onValueChange = vm::setCodeQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.code_search_hint), style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = state.codeLang == null,
                onClick = { vm.setCodeLang(null) },
                label = { Text(stringResource(R.string.common_all), style = MaterialTheme.typography.labelSmall) }
            )
            state.codeLangs.forEach { lang ->
                FilterChip(
                    selected = state.codeLang == lang.name,
                    onClick = { vm.setCodeLang(if (state.codeLang == lang.name) null else lang.name) },
                    label = {
                        Text(
                            "${lang.name} ${lang.count}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(
                    R.string.code_count,
                    state.codeItems.size,
                    state.codeItems.map { it.convId }.distinct().size
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (!forceExpand && groups.isNotEmpty()) {
                val allKeys = groups.map { it.key }.toSet()
                val allExpanded = expandedMonths.containsAll(allKeys)
                TextButton(onClick = {
                    if (allExpanded) {
                        expandedMonths = emptySet()
                        expandedConvs = emptySet()
                    } else {
                        expandedMonths = allKeys
                    }
                }) {
                    Text(
                        if (allExpanded) stringResource(R.string.collapse_all) else stringResource(R.string.expand_all),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        if (state.codeItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.code_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                groups.forEach { month ->
                    val monthOpen = forceExpand || month.key in expandedMonths
                    item(key = "month:${month.key}") {
                        MonthHeader(
                            group = month,
                            open = monthOpen,
                            onClick = {
                                expandedMonths = if (monthOpen) expandedMonths - month.key
                                else expandedMonths + month.key
                            }
                        )
                    }
                    if (monthOpen) {
                        month.convs.forEach { conv ->
                            val convKey = "${month.key}:${conv.convId}"
                            val convOpen = forceExpand || convKey in expandedConvs
                            item(key = "conv:$convKey") {
                                ConvCodeHeader(
                                    group = conv,
                                    open = convOpen,
                                    onClick = {
                                        expandedConvs = if (convOpen) expandedConvs - convKey
                                        else expandedConvs + convKey
                                    },
                                    onOpenSource = {
                                        val first = conv.items.firstOrNull()
                                        if (first != null) onOpen(first.convId, first.msgId)
                                    }
                                )
                            }
                            if (convOpen) {
                                items(conv.items, key = { "code:${it.id}" }) { item ->
                                    CodeCard(
                                        item = item,
                                        onOpen = { onOpen(item.convId, item.msgId) },
                                        onCopy = {
                                            clipboard.setText(AnnotatedString(item.code))
                                            vm.toast(context.getString(R.string.code_copied, item.lang))
                                        }
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun MonthHeader(group: MonthCodeGroup, open: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            monthLabel(group.ts),
            Modifier.padding(start = 8.dp).weight(1f),
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            stringResource(R.string.code_group_meta, group.convs.size, group.count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConvCodeHeader(
    group: ConvCodeGroup,
    open: Boolean,
    onClick: () -> Unit,
    onOpenSource: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 22.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                group.title.ifBlank { stringResource(R.string.unnamed_chat) },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.code_item_meta, group.items.size, Fmt.date(group.ts)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onOpenSource, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Outlined.OpenInNew,
                stringResource(R.string.open_chat),
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CodeCard(
    item: CodeItem,
    onOpen: () -> Unit,
    onCopy: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 40.dp, end = 8.dp, top = 8.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Code,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp)
            )
            Text(
                item.lang.ifBlank { "text" },
                Modifier.padding(start = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCopy, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    stringResource(R.string.common_copy),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            item.code,
            Modifier
                .padding(top = 5.dp, end = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(10.dp),
            style = MonoStyle,
            fontSize = 12.sp,
            maxLines = 10,
            overflow = TextOverflow.Ellipsis
        )
    }
}
