package com.r.chatvault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
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
import com.r.chatvault.data.Attachment
import com.r.chatvault.data.Conversation
import com.r.chatvault.data.Fmt
import com.r.chatvault.data.Palette
import com.r.chatvault.data.SortMode
import com.r.chatvault.util.Exporter
import com.r.chatvault.vm.AppViewModel
import com.r.chatvault.vm.Screen
import com.r.chatvault.vm.UiState
import kotlinx.coroutines.launch

/** 会话页的轻量 UI 状态：顶栏的搜索/菜单与正文共享 */
class ChatUi {
    var searchOpen by mutableStateOf(false)
    var onlyUser by mutableStateOf(false)
    var menuOpen by mutableStateOf(false)
    var detailsOpen by mutableStateOf(false)
    var editOpen by mutableStateOf(false)
    var deleteOpen by mutableStateOf(false)
    var selectionEpoch by mutableIntStateOf(0)
}

private fun categoryColor(cat: String) = when (cat) {
    "安卓玩机" -> Palette.teal
    "网络路由" -> Palette.blue
    "编程开发" -> Palette.purple
    "电脑系统" -> Palette.amber
    "影音图像" -> Palette.coral
    "硬件性能" -> Palette.gray
    "学习办公" -> Palette.teal
    "生活杂问" -> Palette.coral
    else -> Palette.gray
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: AppViewModel,
    state: UiState,
    onPickFolder: () -> Unit,
    onPickZip: () -> Unit,
    onPickMarkdown: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var importMenu by remember { mutableStateOf(false) }
    var reportViewer by remember { mutableStateOf<Attachment?>(null) }
    val chatOpen = state.selectedId != null
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val context = LocalContext.current
    // 切换会话时重置会话页 UI 状态
    val chatUi = remember(state.selectedId) { ChatUi() }

    BackHandler(
        enabled = chatOpen || (!wide && drawerState.isOpen) || state.screen != Screen.CHATS
    ) {
        when {
            chatOpen -> vm.closeConversation()
            !wide && drawerState.isOpen -> scope.launch { drawerState.close() }
            state.screen != Screen.CHATS -> vm.setScreen(Screen.CHATS)
        }
    }

    val content: @Composable () -> Unit = {
        // 全局唯一的顶栏：左侧永远是抽屉按钮，右侧永远是搜索 + 导入。
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TopAppBar(
                title = {
                    val conv = state.selected
                    if (chatOpen && conv != null) {
                        Column {
                            Text(
                                conv.displayTitle,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${Fmt.date(conv.createTs)} · ${state.messages.count { m ->
                                    m.text.isNotBlank() || !m.reasoning.isNullOrBlank() ||
                                        state.attachments[m.id].orEmpty().isNotEmpty()
                                }} 条 · ${conv.category}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(screenTitle(state.screen), style = MaterialTheme.typography.titleMedium)
                    }
                },
                navigationIcon = {
                    if (!wide) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Outlined.Menu, "打开抽屉")
                        }
                    } else {
                        Box(Modifier.size(4.dp))
                    }
                },
                actions = {
                    if (chatOpen) {
                        IconButton(onClick = { chatUi.searchOpen = !chatUi.searchOpen }) {
                            Icon(
                                Icons.Outlined.Search,
                                "会话内搜索",
                                tint = if (chatUi.searchOpen) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = vm::toggleStar) {
                            Icon(
                                if (state.selected?.starred == true) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                "收藏",
                                tint = if (state.selected?.starred == true) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box {
                            IconButton(onClick = { chatUi.menuOpen = true }) {
                                Icon(Icons.Outlined.MoreVert, "更多")
                            }
                            DropdownMenu(expanded = chatUi.menuOpen, onDismissRequest = { chatUi.menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (chatUi.onlyUser) "显示全部消息" else "只看我的提问") },
                                    onClick = { chatUi.onlyUser = !chatUi.onlyUser; chatUi.menuOpen = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("会话详情") },
                                    onClick = { chatUi.detailsOpen = true; chatUi.menuOpen = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("编辑名称与标签") },
                                    onClick = { chatUi.editOpen = true; chatUi.menuOpen = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("导出为 Markdown") },
                                    leadingIcon = { Icon(Icons.Outlined.IosShare, null) },
                                    onClick = {
                                        chatUi.menuOpen = false
                                        val conv = state.selected
                                        if (conv != null) {
                                            val ok = Exporter.shareMarkdown(
                                                context, conv, state.messages, state.convAttachments
                                            )
                                            vm.toast(if (ok) "已生成 Markdown，选择应用分享" else "导出失败")
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("删除对话", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.DeleteOutline,
                                            null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        chatUi.menuOpen = false
                                        chatUi.deleteOpen = true
                                    }
                                )
                            }
                        }
                    } else {
                        IconButton(onClick = {
                            vm.closeConversation()
                            vm.setScreen(Screen.SEARCH)
                        }) {
                            Icon(Icons.Outlined.Search, "全文搜索")
                        }
                        Box {
                            IconButton(onClick = { importMenu = true }) {
                                Icon(Icons.Outlined.CreateNewFolder, "导入")
                            }
                            DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("导入文件夹") },
                                    leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) },
                                    onClick = { importMenu = false; onPickFolder() }
                                )
                                DropdownMenuItem(
                                    text = { Text("导入 ZIP 压缩包") },
                                    leadingIcon = { Icon(Icons.Outlined.FolderZip, null) },
                                    onClick = { importMenu = false; onPickZip() }
                                )
                                DropdownMenuItem(
                                    text = { Text("导入 Markdown") },
                                    leadingIcon = { Icon(Icons.Outlined.Description, null) },
                                    onClick = { importMenu = false; onPickMarkdown() }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (chatOpen) {
                    ChatScreen(vm = vm, state = state, ui = chatUi)
                } else {
                    when (state.screen) {
                        Screen.CHATS -> ChatsHome(state) { id -> vm.openConversation(id) }
                        Screen.SEARCH -> SearchScreen(vm, state) { hit ->
                            if (hit.convId.isNotBlank()) {
                                val jumpId = hit.msgId.ifBlank { null }
                                vm.openConversation(
                                    hit.convId,
                                    jumpId,
                                    if (jumpId != null) state.query else null
                                )
                            }
                        }
                        Screen.CLIPS -> ClipsScreen(vm, state) { convId, msgId ->
                            vm.openConversation(
                                convId,
                                msgId.ifBlank { null },
                                state.clipQuery.takeIf { it.isNotBlank() }
                            )
                        }
                        Screen.CODE -> CodeScreen(vm, state) { convId, msgId ->
                            vm.openConversation(
                                convId,
                                msgId.ifBlank { null },
                                state.codeQuery.takeIf { it.isNotBlank() }
                            )
                        }
                        Screen.STATS -> StatsScreen(vm, state)
                        Screen.FILES -> FilesScreen(state)
                        Screen.SETTINGS -> SettingsScreen(
                            vm, state, onPickFolder, onPickZip, onPickMarkdown, onBackup, onRestore
                        )
                    }
                }
            }
        }
    }

    if (wide) {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(
                    Modifier.width(330.dp),
                    drawerContainerColor = MaterialTheme.colorScheme.surface
                ) {
                    DrawerPanel(
                        vm = vm,
                        state = state,
                        onPickFolder = onPickFolder,
                        onPickZip = onPickZip,
                        onPickMarkdown = onPickMarkdown,
                        closeDrawer = {}
                    )
                }
            }
        ) { content() }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = true,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.width(330.dp)
                ) {
                    DrawerPanel(
                        vm = vm,
                        state = state,
                        onPickFolder = onPickFolder,
                        onPickZip = onPickZip,
                        onPickMarkdown = onPickMarkdown,
                        closeDrawer = { scope.launch { drawerState.close() } }
                    )
                }
            }
        ) { content() }
    }

    val editingConversation = state.selected
    if (chatUi.editOpen && editingConversation != null) {
        EditConvDialog(
            conv = editingConversation,
            allTags = state.tags.map { it.name },
            onDismiss = { chatUi.editOpen = false },
            onSave = { alias, tags ->
                vm.saveConvMeta(editingConversation.id, alias, tags)
                chatUi.editOpen = false
            }
        )
    }

    if (chatUi.deleteOpen && state.selected != null) {
        AlertDialog(
            onDismissRequest = { chatUi.deleteOpen = false },
            title = { Text("删除这个会话？") },
            text = {
                Text("会同时删除消息、附件实体、摘录、代码块和标签关联，且不可恢复。原始导出包不受影响。")
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = state.selected?.id
                    chatUi.deleteOpen = false
                    if (id != null) vm.deleteConversation(id)
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { chatUi.deleteOpen = false }) { Text("取消") }
            }
        )
    }

    state.importReport?.let { report ->
        ImportReportSheet(
            report = report,
            onDismiss = vm::consumeImportReport,
            onOpenConversation = { convId, msgId ->
                vm.consumeImportReport()
                vm.openConversation(convId, msgId.ifBlank { null })
            },
            onViewFile = { reportViewer = it }
        )
    }
    reportViewer?.let { a -> AttachmentViewer(a) { reportViewer = null } }
}

private fun screenTitle(screen: Screen): String = when (screen) {
    Screen.CHATS -> "会话"
    Screen.SEARCH -> "全文搜索"
    Screen.CLIPS -> "摘录与书签"
    Screen.CODE -> "代码片段"
    Screen.STATS -> "统计图表"
    Screen.FILES -> "附件与文件"
    Screen.SETTINGS -> "设置"
}

@Composable
private fun DrawerPanel(
    vm: AppViewModel,
    state: UiState,
    onPickFolder: () -> Unit,
    onPickZip: () -> Unit,
    onPickMarkdown: () -> Unit,
    closeDrawer: () -> Unit
) {
    var filterOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var editingConv by remember { mutableStateOf<Conversation?>(null) }
    var deletingConv by remember { mutableStateOf<Conversation?>(null) }
    LazyColumn(Modifier.fillMaxHeight()) {
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("CV", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleSmall)
                    }
                    Column(Modifier.padding(start = 10.dp)) {
                        Text("ChatVault", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${state.totalConvs} 个会话 · ${Fmt.compact(state.totalMsgs)} 条消息",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallAction("文件夹", Icons.Outlined.FolderOpen, Modifier.weight(1f)) {
                        closeDrawer(); onPickFolder()
                    }
                    SmallAction("ZIP", Icons.Outlined.FolderZip, Modifier.weight(1f)) {
                        closeDrawer(); onPickZip()
                    }
                    SmallAction("MD", Icons.Outlined.Description, Modifier.weight(1f)) {
                        closeDrawer(); onPickMarkdown()
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                DrawerNav("会话", Icons.Outlined.ChatBubbleOutline, state.screen == Screen.CHATS) {
                    vm.closeConversation(); vm.setScreen(Screen.CHATS); closeDrawer()
                }
                DrawerNav("全文搜索", Icons.Outlined.Search, state.screen == Screen.SEARCH) {
                    vm.closeConversation(); vm.setScreen(Screen.SEARCH); closeDrawer()
                }
                DrawerNav(
                    "摘录与书签", Icons.Outlined.BookmarkBorder, state.screen == Screen.CLIPS,
                    badge = if (state.clipCount > 0) state.clipCount else null
                ) {
                    vm.closeConversation(); vm.setScreen(Screen.CLIPS); closeDrawer()
                }
                DrawerNav("代码片段", Icons.Outlined.Code, state.screen == Screen.CODE) {
                    vm.closeConversation(); vm.setScreen(Screen.CODE); closeDrawer()
                }
                DrawerNav("统计图表", Icons.Outlined.BarChart, state.screen == Screen.STATS) {
                    vm.closeConversation(); vm.setScreen(Screen.STATS); closeDrawer()
                }
                DrawerNav("附件与文件", Icons.Outlined.FolderOpen, state.screen == Screen.FILES) {
                    vm.closeConversation(); vm.setScreen(Screen.FILES); closeDrawer()
                }
                DrawerNav("设置", Icons.Outlined.Settings, state.screen == Screen.SETTINGS) {
                    vm.closeConversation(); vm.setScreen(Screen.SETTINGS); closeDrawer()
                }
            }
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("会话列表", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { filterOpen = true }) {
                    Icon(
                        Icons.Outlined.FilterList,
                        null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        " 筛选" + if (state.filterCount > 0) " ${state.filterCount}" else "",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = state.starredOnly,
                    onClick = { vm.setStarredOnly(!state.starredOnly) },
                    label = { Text("收藏", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(
                            if (state.starredOnly) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                            null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                )
            }
        }

        if (state.tags.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = state.tagFilter == null,
                        onClick = {
                            if (state.screen != Screen.CHATS) vm.setScreen(Screen.CHATS)
                            vm.setTagFilter(null)
                        },
                        label = { Text("全部标签", style = MaterialTheme.typography.labelSmall) }
                    )
                    state.tags.take(12).forEach { t ->
                        FilterChip(
                            selected = state.tagFilter == t.name,
                            onClick = {
                                if (state.screen != Screen.CHATS) vm.setScreen(Screen.CHATS)
                                vm.setTagFilter(if (state.tagFilter == t.name) null else t.name)
                            },
                            label = { Text("#${t.name} ${t.count}", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }

        if (state.conversations.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.ChatBubbleOutline, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(30.dp)
                    )
                    Text(
                        if (state.loading) "正在读取…" else "还没有数据\n点上方按钮导入导出包",
                        Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(state.conversations, key = { it.id }) { c ->
                Box {
                    ConvRow(
                        c = c,
                        selected = c.id == state.selectedId,
                        onClick = {
                            if (state.screen != Screen.CHATS) vm.setScreen(Screen.CHATS)
                            vm.openConversation(c.id)
                            closeDrawer()
                        },
                        onLongClick = { menuFor = if (menuFor == c.id) null else c.id }
                    )
                    DropdownMenu(
                        expanded = menuFor == c.id,
                        onDismissRequest = { menuFor = null }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (c.starred) "取消收藏" else "收藏") },
                            leadingIcon = {
                                Icon(
                                    if (c.starred) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                    null
                                )
                            },
                            onClick = {
                                vm.setStar(c.id, !c.starred)
                                menuFor = null
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("编辑名称与标签") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = {
                                editingConv = c
                                menuFor = null
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("删除对话", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                deletingConv = c
                                menuFor = null
                            }
                        )
                    }
                }
            }
        }

        item { Column(Modifier.height(16.dp)) {} }
    }

    editingConv?.let { conv ->
        EditConvDialog(
            conv = conv,
            allTags = state.tags.map { it.name },
            onDismiss = { editingConv = null },
            onSave = { alias, tags ->
                vm.saveConvMeta(conv.id, alias, tags)
                editingConv = null
            }
        )
    }

    deletingConv?.let { conv ->
        AlertDialog(
            onDismissRequest = { deletingConv = null },
            title = { Text("删除《${conv.displayTitle}》？") },
            text = { Text("会同时删除消息、附件实体、摘录、代码块和标签关联，且不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    deletingConv = null
                    vm.deleteConversation(conv.id)
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingConv = null }) { Text("取消") }
            }
        )
    }

    if (filterOpen) {
        FilterSheet(
            sort = state.sort,
            year = state.year,
            category = state.category,
            model = state.model,
            dateFrom = state.dateFrom,
            dateTo = state.dateTo,
            years = state.filterYears,
            categories = state.filterCategories,
            models = state.filterModels,
            onApply = { s, y, c, m, from, to -> vm.applyFilters(s, y, c, m, from, to) },
            onReset = { vm.resetFilters() },
            onDismiss = { filterOpen = false }
        )
    }
}

private fun sortLabel(m: SortMode): String = when (m) {
    SortMode.NEWEST -> "最新优先"
    SortMode.OLDEST -> "最早优先"
    SortMode.MESSAGES -> "消息最多"
    SortMode.CHARS -> "字数最多"
    SortMode.TITLE -> "标题 A-Z"
}

@Composable
private fun DrawerNav(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    badge: Int? = null,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        icon = { Icon(icon, null, modifier = Modifier.size(19.dp)) },
        selected = selected,
        onClick = onClick,
        badge = if (badge != null && badge > 0) {
            { Text("$badge", style = MaterialTheme.typography.labelSmall) }
        } else null,
        modifier = Modifier.padding(vertical = 1.dp)
    )
}

@Composable
private fun SmallAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
        Text(" $label", style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConvRow(
    c: Conversation,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 18.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .padding(end = 10.dp)
                .size(7.dp)
                .background(categoryColor(c.category), CircleShape)
        )
        Column(Modifier.weight(1f)) {
            Text(
                c.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                "${Fmt.date(c.createTs)} · ${c.msgCount} 条" +
                    if (c.attachmentCount > 0) " · ${c.attachmentCount} 附件" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (c.tags.isNotEmpty()) {
                Text(
                    c.tags.take(3).joinToString(" ") { "#$it" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (c.starred) {
            Icon(
                Icons.Outlined.Star, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
private fun EditConvDialog(
    conv: Conversation,
    allTags: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, List<String>) -> Unit
) {
    var alias by remember(conv.id) { mutableStateOf(conv.alias ?: "") }
    var tags by remember(conv.id) { mutableStateOf(conv.tags) }
    var input by remember { mutableStateOf("") }

    fun addTag() {
        val t = input.trim().take(30)
        if (t.isNotBlank() && tags.none { it.equals(t, true) }) {
            tags = tags + t
        }
        input = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("名称与标签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("别名（留空则用原标题）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Text("原标题：${conv.title}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tags.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        tags.forEach { t ->
                            InputChip(
                                selected = true,
                                onClick = { tags = tags - t },
                                label = { Text(t, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
                val availableTags = allTags.filter { name -> tags.none { it.equals(name, true) } }
                if (availableTags.isNotEmpty()) {
                    Text(
                        "已有标签（点击添加）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        availableTags.take(20).forEach { t ->
                            FilterChip(
                                selected = false,
                                onClick = { tags = tags + t },
                                label = { Text("#$t", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("新标签") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    IconButton(onClick = { addTag() }) {
                        Icon(Icons.Outlined.Add, "添加标签")
                    }
                }
                Text(
                    "标签会显示在会话列表，也能在抽屉里筛选；重新导入不会丢。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(alias, tags) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ChatsHome(state: UiState, onOpen: (String) -> Unit) {
    if (state.conversations.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("还没有导入数据", style = MaterialTheme.typography.titleMedium)
            Text(
                "点右上角「导入」，选择 ChatGPT 导出包的文件夹、.zip，\n或者选择一份 Markdown 聊天记录。",
                Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "导入后可离线浏览全部会话、搜索正文与代码、查看附件与统计图表。",
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                HomeStat("会话", Fmt.compact(state.totalConvs), Modifier.weight(1f))
                HomeStat("消息", Fmt.compact(state.totalMsgs), Modifier.weight(1f))
                HomeStat("附件", Fmt.compact(state.totalAtts), Modifier.weight(1f))
            }
            Text(
                "最近会话",
                Modifier.padding(top = 20.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(state.conversations.take(12), key = { it.id }) { c ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(c.id) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).background(categoryColor(c.category), CircleShape)) {}
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        c.displayTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${Fmt.date(c.createTs)} · ${c.msgCount} 条 · ${c.category}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    Fmt.compact(c.charCount) + " 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item {
            Text(
                "更多会话、筛选与排序在左侧抽屉中。",
                Modifier.padding(top = 14.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HomeStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, fontSize = 20.sp, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
