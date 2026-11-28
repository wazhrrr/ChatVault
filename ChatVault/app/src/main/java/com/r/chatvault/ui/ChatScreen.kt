package com.r.chatvault.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.r.chatvault.data.Attachment
import com.r.chatvault.data.Conversation
import com.r.chatvault.R
import com.r.chatvault.data.Fmt
import com.r.chatvault.data.Message
import com.r.chatvault.data.SearchHit
import com.r.chatvault.vm.AppViewModel
import com.r.chatvault.vm.UiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: AppViewModel,
    state: UiState,
    ui: ChatUi
) {
    val conv = state.selected ?: return
    val colors = LocalChatColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val bubbleMax = (screenWidth * 0.84f).dp

    var query by remember(conv.id) { mutableStateOf("") }
    var hits by remember(conv.id) { mutableStateOf<List<SearchHit>>(emptyList()) }
    var hitIndex by remember(conv.id) { mutableIntStateOf(0) }
    var pendingJump by remember(conv.id) { mutableStateOf<String?>(null) }
    var reasoningOpen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var viewer by remember { mutableStateOf<Attachment?>(null) }
    var actionTarget by remember { mutableStateOf<Message?>(null) }
    var branchTarget by remember { mutableStateOf<Message?>(null) }
    var branchStartId by remember { mutableStateOf<String?>(null) }
    var clipTarget by remember { mutableStateOf<Message?>(null) }
    var detailsTarget by remember { mutableStateOf<Message?>(null) }

    val displayed = remember(state.messages, ui.onlyUser, state.attachments) {
        state.messages.filter { m ->
            val hasContent = m.text.isNotBlank() || !m.reasoning.isNullOrBlank() ||
                state.attachments[m.id].orEmpty().isNotEmpty()
            hasContent && (!ui.onlyUser || m.role == "user")
        }
    }
    val keywords = remember(query) { query.split(Regex("\\s+")).filter { it.isNotBlank() } }

    /** 先滚到消息，再按关键词在正文里的比例估算偏移，避免只停在消息开头 */
    suspend fun scrollToHit(hit: SearchHit) {
        val idx = displayed.indexOfFirst { it.id == hit.msgId }
        if (idx < 0) return
        val m = state.messages.firstOrNull { it.id == hit.msgId }
        if (m == null) {
            listState.animateScrollToItem(idx)
            return
        }
        val kw = keywords.firstOrNull { k ->
            m.text.contains(k, true) || (m.reasoning?.contains(k, true) == true)
        }
        if (kw != null && !m.text.contains(kw, true) &&
            m.reasoning?.contains(kw, true) == true && m.id !in reasoningOpen
        ) {
            reasoningOpen = reasoningOpen + m.id
        }
        listState.animateScrollToItem(idx)
        if (kw == null) return
        delay(if (m.id in reasoningOpen) 160 else 70)
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx } ?: return
        val text = if (m.text.contains(kw, true)) m.text else (m.reasoning ?: m.text)
        if (text.isEmpty()) return
        val charIdx = text.indexOf(kw, ignoreCase = true).coerceAtLeast(0)
        val ratio = charIdx.toFloat() / text.length
        val viewport = listState.layoutInfo.viewportSize.height
        val maxOffset = (info.size - viewport * 0.55f).coerceAtLeast(0f)
        val target = (ratio * info.size - viewport * 0.22f).coerceIn(0f, maxOffset)
        if (target > 0f) listState.animateScrollToItem(idx, target.toInt())
    }

    // 从全局搜索跳进来：带上关键词，打开会话内搜索，并定位到命中消息
    LaunchedEffect(state.jumpMessageId, state.jumpQuery, conv.id) {
        val id = state.jumpMessageId ?: return@LaunchedEffect
        pendingJump = id
        val q = state.jumpQuery
        if (!q.isNullOrBlank()) {
            ui.onlyUser = false
            ui.searchOpen = true
            if (query == q) {
                val idx = hits.indexOfFirst { it.msgId == id }
                if (idx >= 0) {
                    hitIndex = idx
                    scrollToHit(hits[idx])
                } else {
                    val t = displayed.indexOfFirst { it.id == id }
                    if (t >= 0) listState.animateScrollToItem(t)
                }
                pendingJump = null
                vm.consumeJump()
            } else {
                query = q
            }
        } else {
            val t = displayed.indexOfFirst { it.id == id }
            if (t >= 0) listState.animateScrollToItem(t)
            vm.consumeJump()
        }
    }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            hits = emptyList()
            hitIndex = 0
            return@LaunchedEffect
        }
        delay(200)
        val res = vm.searchInConv(conv.id, query)
        hits = res
        val wanted = pendingJump
        val idx = if (wanted != null) res.indexOfFirst { it.msgId == wanted } else -1
        hitIndex = if (idx >= 0) idx else 0
        if (res.isNotEmpty()) {
            scrollToHit(res[hitIndex.coerceIn(0, res.size - 1)])
        } else if (wanted != null) {
            val t = displayed.indexOfFirst { it.id == wanted }
            if (t >= 0) listState.animateScrollToItem(t)
        }
        if (wanted != null) {
            pendingJump = null
            vm.consumeJump()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (ui.searchOpen) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_search_hint), style = MaterialTheme.typography.bodySmall) },
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
                                    scope.launch { scrollToHit(hits[hitIndex]) }
                                }
                            },
                            modifier = Modifier.size(30.dp)
                        ) { Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.chat_prev), modifier = Modifier.size(18.dp)) }
                        IconButton(
                            onClick = {
                                if (hits.isNotEmpty()) {
                                    hitIndex = (hitIndex + 1) % hits.size
                                    scope.launch { scrollToHit(hits[hitIndex]) }
                                }
                            },
                            modifier = Modifier.size(30.dp)
                        ) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.chat_next), modifier = Modifier.size(18.dp)) }
                    }
                }
            }
            if (hits.isNotEmpty()) {
                val h = hits[hitIndex.coerceIn(0, hits.size - 1)]
                Text(
                    highlightAll(h.snippet, keywords, colors.highlight),
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // 短按任意位置都清掉文字选择；长按留给 SelectionContainer。
                    // 用 Initial pass + requireUnconsumed=false，避免选择层先吃掉事件导致首次失效。
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var isTap = true
                        var released = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.uptimeMillis - down.uptimeMillis > 400) isTap = false
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                isTap = false
                            }
                            if (!change.pressed) {
                                released = true
                                break
                            }
                        }
                        if (isTap && released) ui.selectionEpoch++
                    }
                },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 14.dp, end = 14.dp, top = 8.dp, bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(displayed, key = { _, m -> m.id }) { _, m ->
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
                                key(ui.selectionEpoch) {
                                    SelectionContainer {
                                        Text(
                                            highlightAll(m.text, keywords, colors.highlight),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = colors.userText
                                        )
                                    }
                                }
                                AttachmentStrip(atts) { viewer = it }
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (m.childIdList.size > 1) {
                                        Text(
                                            stringResource(R.string.branch_count, m.childIdList.size),
                                            Modifier
                                                .clickable { branchTarget = m }
                                                .padding(end = 8.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.userText.copy(alpha = 0.75f)
                                        )
                                    }
                                    val time = Fmt.time(m.ts)
                                    if (time.isNotBlank()) {
                                        Text(
                                            time,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.userText.copy(alpha = 0.7f)
                                        )
                                    }
                                    IconButton(
                                        onClick = { actionTarget = m },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.MoreVert,
                                            stringResource(R.string.msg_actions),
                                            modifier = Modifier.size(14.dp),
                                            tint = colors.userText.copy(alpha = 0.7f)
                                        )
                                    }
                                }
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
                                            stringResource(R.string.reasoning),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            "  " + stringResource(R.string.chars_count, m.reasoning!!.length),
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
                                modifier = Modifier.fillMaxWidth()
                                    .padding(top = if (m.reasoning.isNullOrBlank()) 0.dp else 8.dp),
                                highlight = keywords,
                                wrapCode = state.codeWrap,
                                selectionKey = ui.selectionEpoch
                            )
                        }
                        AttachmentStrip(atts) { viewer = it }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val meta = listOfNotNull(
                                Fmt.time(m.ts).takeIf { it.isNotBlank() },
                                m.model?.takeIf { it.isNotBlank() }
                            ).joinToString(" · ")
                            if (meta.isNotBlank()) {
                                Box(
                                    Modifier.size(5.dp)
                                        .background(MaterialTheme.colorScheme.outline, CircleShape)
                                ) {}
                                Text(
                                    "  $meta",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (m.childIdList.size > 1) {
                                Text(
                                    stringResource(R.string.branch_count, m.childIdList.size),
                                    Modifier
                                        .padding(start = 10.dp)
                                        .clickable { branchTarget = m }
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                            RoundedCornerShape(5.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Box(Modifier.weight(1f)) {}
                            IconButton(
                                onClick = { actionTarget = m },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.MoreVert,
                                    stringResource(R.string.msg_actions),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    actionTarget?.let { msg ->
        MessageActionsSheet(
            msg = msg,
            onDismiss = { actionTarget = null },
            onCopy = {
                clipboard.setText(AnnotatedString(msg.text.ifBlank { msg.reasoning ?: "" }))
                actionTarget = null
                vm.toast(context.getString(R.string.copied_message))
            },
            onShare = {
                val text = msg.text.ifBlank { msg.reasoning ?: "" }
                if (text.isNotBlank()) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_message)))
                }
                actionTarget = null
            },
            onClip = {
                clipTarget = msg
                actionTarget = null
            },
            onBookmark = {
                vm.bookmark(msg)
                actionTarget = null
            },
            onBranch = {
                branchTarget = msg
                actionTarget = null
            },
            onDetails = {
                detailsTarget = msg
                actionTarget = null
            }
        )
    }

    clipTarget?.let { msg ->
        ClipSaveDialog(
            text = msg.text.ifBlank { msg.reasoning ?: "" },
            allTags = state.clipTags,
            onDismiss = { clipTarget = null },
            onSave = { text, note, tags ->
                vm.addClip(msg.convId, msg.id, text, note, tags)
                clipTarget = null
            }
        )
    }

    detailsTarget?.let { msg ->
        MessageDetails(msg) { detailsTarget = null }
    }

    branchTarget?.let { msg ->
        BranchSheet(
            msg = msg,
            vm = vm,
            onDismiss = { branchTarget = null },
            onOpenBranch = { start ->
                branchTarget = null
                branchStartId = start
            }
        )
    }

    branchStartId?.let { id ->
        BranchViewer(id, vm) { branchStartId = null }
    }

    if (ui.detailsOpen) {
        ConversationDetails(conv, state) { ui.detailsOpen = false }
    }
    viewer?.let { a ->
        AttachmentViewer(a) { viewer = null }
    }
}

private fun Modifier.bubbleWidth(max: androidx.compose.ui.unit.Dp): Modifier = this.widthIn(max = max)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    msg: Message,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClip: () -> Unit,
    onBookmark: () -> Unit,
    onBranch: () -> Unit,
    onDetails: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Text(
                if (msg.role == "user") stringResource(R.string.my_message) else stringResource(R.string.gpt_reply),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                stringResource(R.string.time_with_chars, Fmt.dateTime(msg.ts), msg.text.length),
                Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ActionRow(Icons.Outlined.ContentCopy, stringResource(R.string.copy_all), onCopy)
            ActionRow(Icons.Outlined.Share, stringResource(R.string.share_text), onShare)
            ActionRow(Icons.Outlined.FormatQuote, stringResource(R.string.save_clip_ellipsis), onClip)
            ActionRow(Icons.Outlined.BookmarkBorder, stringResource(R.string.add_bookmark), onBookmark)
            if (msg.childIdList.size > 1) {
                ActionRow(Icons.Outlined.CallSplit, stringResource(R.string.other_branches, msg.childIdList.size), onBranch)
            }
            ActionRow(Icons.Outlined.Info, stringResource(R.string.msg_details), onDetails)
            Column(Modifier.height(24.dp)) {}
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, modifier = Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, Modifier.padding(start = 14.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ClipSaveDialog(
    text: String,
    allTags: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var body by remember { mutableStateOf(text.take(4000)) }
    var note by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_clip)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text(stringResource(R.string.body)) },
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.note_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text(stringResource(R.string.tags_comma)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                val current = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val available = allTags.filter { name -> current.none { it.equals(name, true) } }
                if (available.isNotEmpty()) {
                    Text(
                        stringResource(R.string.existing_tags),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        available.take(12).forEach { t ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    tags = if (tags.isBlank()) t else "$tags,$t"
                                },
                                label = { Text("#$t", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(body, note, tags) },
                enabled = body.isNotBlank()
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BranchSheet(
    msg: Message,
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onOpenBranch: (String) -> Unit
) {
    var children by remember(msg.id) { mutableStateOf<List<Message>>(emptyList()) }
    LaunchedEffect(msg.id) { children = vm.childrenOf(msg.id) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Text(stringResource(R.string.branches_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.branches_hint),
                Modifier.padding(top = 3.dp, bottom = 10.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(children, key = { it.id }) { child ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenBranch(child.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            Modifier
                                .padding(top = 5.dp)
                                .size(7.dp)
                                .background(
                                    if (child.active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    CircleShape
                                )
                        ) {}
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(
                                (if (child.role == "user") stringResource(R.string.me_prefix) else "GPT · ") + Fmt.dateTime(child.ts),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (child.active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                child.text.ifBlank { child.reasoning ?: stringResource(R.string.no_text) },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (child.active) {
                            Text(
                                stringResource(R.string.current),
                                Modifier
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        RoundedCornerShape(5.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            Column(Modifier.height(24.dp)) {}
        }
    }
}

@Composable
private fun BranchViewer(startId: String, vm: AppViewModel, onDismiss: () -> Unit) {
    var chain by remember(startId) { mutableStateOf<List<Message>>(emptyList()) }
    LaunchedEffect(startId) { chain = vm.branchChain(startId) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.another_branch), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.readonly_preview, chain.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.common_close)) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (chain.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_branch_content), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(chain, key = { it.id }) { m ->
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    (if (m.role == "user") stringResource(R.string.common_me) else "ChatGPT") +
                                        " · ${Fmt.dateTime(m.ts)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!m.reasoning.isNullOrBlank()) {
                                    Text(
                                        m.reasoning,
                                        Modifier.padding(top = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (m.text.isNotBlank()) {
                                    MarkdownView(m.text, Modifier.padding(top = 4.dp))
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageDetails(msg: Message, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.common_close)) } },
        title = { Text(stringResource(R.string.msg_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow(stringResource(R.string.common_role), if (msg.role == "user") stringResource(R.string.common_me) else "ChatGPT")
                DetailRow(stringResource(R.string.common_time), Fmt.dateTime(msg.ts))
                DetailRow(stringResource(R.string.common_type), msg.contentType)
                DetailRow(stringResource(R.string.common_model), msg.model?.let { modelLabel(it) } ?: stringResource(R.string.common_not_recorded))
                DetailRow(stringResource(R.string.common_chars), stringResource(R.string.chars_count, msg.text.length))
                DetailRow(stringResource(R.string.reasoning), if (msg.reasoning.isNullOrBlank()) stringResource(R.string.common_none) else stringResource(R.string.chars_count, msg.reasoning.length))
                DetailRow(stringResource(R.string.message_id), msg.id)
                DetailRow(stringResource(R.string.common_branches), if (msg.childIdList.size > 1) stringResource(R.string.branches_after, msg.childIdList.size) else stringResource(R.string.common_none))
            }
        }
    )
}

@Composable
private fun ConversationDetails(conv: Conversation, state: UiState, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.common_close)) } },
        title = { Text(stringResource(R.string.chat_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow(stringResource(R.string.common_title), conv.title)
                if (!conv.alias.isNullOrBlank()) DetailRow(stringResource(R.string.common_alias), conv.alias)
                DetailRow(stringResource(R.string.chat_id), conv.id)
                DetailRow(stringResource(R.string.created), Fmt.dateTime(conv.createTs))
                DetailRow(stringResource(R.string.updated), Fmt.dateTime(conv.updateTs))
                DetailRow(stringResource(R.string.msg_count), stringResource(R.string.count_messages, conv.msgCount))
                DetailRow(stringResource(R.string.text_size), stringResource(R.string.row_chars, Fmt.compact(conv.charCount)))
                DetailRow(stringResource(R.string.common_topic), categoryLabel(conv.category))
                DetailRow(stringResource(R.string.common_tags), if (conv.tags.isEmpty()) stringResource(R.string.common_none) else conv.tags.joinToString(" ") { "#$it" })
                if (conv.msgCount > 1 && !conv.id.startsWith("md-") &&
                    state.messages.isNotEmpty() && state.messages.none { it.childIds.isNotBlank() }
                ) {
                    DetailRow(stringResource(R.string.common_branches), stringResource(R.string.old_data_hint))
                }
                DetailRow(stringResource(R.string.default_model), conv.model?.let { modelLabel(it) } ?: stringResource(R.string.common_not_recorded))
                DetailRow(stringResource(R.string.common_attachments), stringResource(R.string.detail_attachments, conv.attachmentCount, state.convAttachments.count { it.exists }))
                if (conv.msgCount != state.messages.size) {
                    DetailRow(stringResource(R.string.common_tip), stringResource(R.string.showing_now, state.messages.size))
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
