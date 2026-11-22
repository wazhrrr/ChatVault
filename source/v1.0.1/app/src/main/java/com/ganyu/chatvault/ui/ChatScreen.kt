package com.ganyu.chatvault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ganyu.chatvault.data.Attachment
import com.ganyu.chatvault.data.Conversation
import com.ganyu.chatvault.data.Fmt
import com.ganyu.chatvault.data.Message
import com.ganyu.chatvault.data.SearchHit
import com.ganyu.chatvault.util.Exporter
import com.ganyu.chatvault.vm.AppViewModel
import com.ganyu.chatvault.vm.UiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    vm: AppViewModel,
    state: UiState
) {
    val conv = state.selected ?: return
    val colors = LocalChatColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val bubbleMax = (screenWidth * 0.84f).dp

    var onlyUser by remember(conv.id) { mutableStateOf(false) }
    var searchOpen by remember(conv.id) { mutableStateOf(false) }
    var query by remember(conv.id) { mutableStateOf("") }
    var hits by remember(conv.id) { mutableStateOf<List<SearchHit>>(emptyList()) }
    var hitIndex by remember(conv.id) { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    var reasoningOpen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var viewer by remember { mutableStateOf<Attachment?>(null) }

    val displayed = remember(state.messages, onlyUser) {
        if (onlyUser) state.messages.filter { it.role == "user" } else state.messages
    }
    val keywords = remember(query) { query.split(Regex("\\s+")).filter { it.isNotBlank() } }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            hits = emptyList(); hitIndex = 0
            return@LaunchedEffect
        }
        delay(200)
        hits = vm.searchInConv(conv.id, query)
        hitIndex = 0
        if (hits.isNotEmpty()) {
            val target = displayed.indexOfFirst { it.id == hits[0].msgId }
            if (target >= 0) listState.animateScrollToItem(target)
        }
    }

    LaunchedEffect(state.jumpMessageId, displayed) {
        val id = state.jumpMessageId ?: return@LaunchedEffect
        val target = displayed.indexOfFirst { it.id == id }
        if (target >= 0) listState.animateScrollToItem(target)
        vm.consumeJump()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 会话自己的操作放在正文顶部，顶栏统一留给全局导航
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { searchOpen = !searchOpen }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Outlined.Search,
                    "会话内搜索",
                    modifier = Modifier.size(19.dp),
                    tint = if (searchOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = vm::toggleStar, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (conv.starred) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    "收藏",
                    modifier = Modifier.size(19.dp),
                    tint = if (conv.starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilterChip(
                selected = onlyUser,
                onClick = { onlyUser = !onlyUser },
                label = { Text("只看提问", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.padding(start = 4.dp)
            )
            Box(Modifier.weight(1f)) {}
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        "更多",
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("会话详情") },
                        onClick = { detailsOpen = true; menuOpen = false }
                    )
                    DropdownMenuItem(
                        text = { Text("导出为 Markdown") },
                        leadingIcon = { Icon(Icons.Outlined.IosShare, null) },
                        onClick = {
                            menuOpen = false
                            val ok = Exporter.shareMarkdown(context, conv, state.messages, state.convAttachments)
                            vm.toast(if (ok) "已生成 Markdown，选择应用分享" else "导出失败")
                        }
                    )
                }
            }
        }

        if (searchOpen) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("在本会话中搜索", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 6.dp)) {
                    Text(
                        if (hits.isEmpty()) "0" else "${hitIndex + 1}/${hits.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row {
                        IconButton(
                            onClick = {
                                if (hits.isNotEmpty()) {
                                    hitIndex = (hitIndex - 1 + hits.size) % hits.size
                                    scope.launch {
                                        val t = displayed.indexOfFirst { it.id == hits[hitIndex].msgId }
                                        if (t >= 0) listState.animateScrollToItem(t)
                                    }
                                }
                            },
                            modifier = Modifier.size(30.dp)
                        ) { Icon(Icons.Outlined.KeyboardArrowUp, "上一条", modifier = Modifier.size(18.dp)) }
                        IconButton(
                            onClick = {
                                if (hits.isNotEmpty()) {
                                    hitIndex = (hitIndex + 1) % hits.size
                                    scope.launch {
                                        val t = displayed.indexOfFirst { it.id == hits[hitIndex].msgId }
                                        if (t >= 0) listState.animateScrollToItem(t)
                                    }
                                }
                            },
                            modifier = Modifier.size(30.dp)
                        ) { Icon(Icons.Outlined.KeyboardArrowDown, "下一条", modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 14.dp, end = 14.dp, top = 8.dp, bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(displayed, key = { _, m -> m.id }) { index, m ->
                val atts = state.attachments[m.id].orEmpty()
                val isUser = m.role == "user"
                if (isUser) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Surface(
                            color = colors.userBubble,
                            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                            modifier = Modifier.bubbleWidth(bubbleMax)
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(
                                    m.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.userText
                                )
                                AttachmentStrip(atts) { viewer = it }
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth()) {
                        if (!m.reasoning.isNullOrBlank()) {
                            val open = m.id in reasoningOpen
                            Surface(
                                color = colors.reasoningBg,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    reasoningOpen = if (open) reasoningOpen - m.id else reasoningOpen + m.id
                                }
                            ) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "思考过程",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            "  ${m.reasoning!!.length} 字",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Box(Modifier.weight(1f)) {}
                                        Icon(
                                            if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                            null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    if (open) {
                                        Text(
                                            m.reasoning!!,
                                            Modifier.padding(top = 6.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        if (m.text.isNotBlank()) {
                            MarkdownView(
                                text = m.text,
                                modifier = Modifier.fillMaxWidth().padding(top = if (m.reasoning.isNullOrBlank()) 0.dp else 8.dp),
                                highlight = keywords,
                                wrapCode = state.codeWrap
                            )
                        }
                        AttachmentStrip(atts) { viewer = it }
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.outline, CircleShape)) {}
                            Text(
                                "  ${Fmt.time(m.ts)}" + (m.model?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (detailsOpen) {
        ConversationDetails(conv, state) { detailsOpen = false }
    }
    viewer?.let { a ->
        AttachmentViewer(a) { viewer = null }
    }
}

private fun Modifier.bubbleWidth(max: androidx.compose.ui.unit.Dp): Modifier = this.widthIn(max = max)

@Composable
private fun ConversationDetails(conv: Conversation, state: UiState, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
        title = { Text("会话详情") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow("标题", conv.title)
                DetailRow("会话 ID", conv.id)
                DetailRow("创建时间", Fmt.dateTime(conv.createTs))
                DetailRow("更新时间", Fmt.dateTime(conv.updateTs))
                DetailRow("消息数", "${conv.msgCount} 条")
                DetailRow("文本量", "${Fmt.compact(conv.charCount)} 字")
                DetailRow("主题", conv.category)
                DetailRow("默认模型", conv.model ?: "未记录")
                DetailRow("附件", "${conv.attachmentCount} 个（实体 ${state.convAttachments.count { it.exists }}）")
                if (conv.msgCount != state.messages.size) {
                    DetailRow("提示", "仅加载 ${state.messages.size} 条")
                }
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            Modifier.width(76.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}
