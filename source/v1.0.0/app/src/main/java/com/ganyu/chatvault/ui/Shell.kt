package com.ganyu.chatvault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ganyu.chatvault.data.Conversation
import com.ganyu.chatvault.data.Fmt
import com.ganyu.chatvault.data.Palette
import com.ganyu.chatvault.data.SortMode
import com.ganyu.chatvault.vm.AppViewModel
import com.ganyu.chatvault.vm.Screen
import com.ganyu.chatvault.vm.UiState
import kotlinx.coroutines.launch

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
    onPickZip: () -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var exportMenu by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(
        enabled = state.selectedId != null || drawerState.isOpen || state.screen != Screen.CHATS
    ) {
        when {
            state.selectedId != null -> vm.closeConversation()
            drawerState.isOpen -> scope.launch { drawerState.close() }
            state.screen != Screen.CHATS -> vm.setScreen(Screen.CHATS)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = state.selectedId == null,
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
                    closeDrawer = { scope.launch { drawerState.close() } }
                )
            }
        }
    ) {
        if (state.selectedId != null) {
            ChatScreen(
                vm = vm,
                state = state,
                onBack = { vm.closeConversation() },
                showBack = true
            )
        } else {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                TopAppBar(
                    title = {
                        Text(
                            when (state.screen) {
                                Screen.CHATS -> "会话"
                                Screen.SEARCH -> "全文搜索"
                                Screen.STATS -> "统计图表"
                                Screen.FILES -> "附件与文件"
                                Screen.SETTINGS -> "设置"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Outlined.Menu, "打开菜单")
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.setScreen(Screen.SEARCH) }) {
                            Icon(Icons.Outlined.Search, "搜索")
                        }
                        Box {
                            IconButton(onClick = { exportMenu = true }) {
                                Icon(Icons.Outlined.CreateNewFolder, "导入")
                            }
                            DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("导入文件夹") },
                                    leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) },
                                    onClick = { exportMenu = false; onPickFolder() }
                                )
                                DropdownMenuItem(
                                    text = { Text("导入 ZIP 压缩包") },
                                    leadingIcon = { Icon(Icons.Outlined.FolderZip, null) },
                                    onClick = { exportMenu = false; onPickZip() }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )

                when (state.screen) {
                    Screen.CHATS -> ChatsHome(state) { id -> vm.openConversation(id) }
                    Screen.SEARCH -> SearchScreen(vm, state, onOpen = { id, msgId ->
                        vm.openConversation(id)
                        vm.jumpToMessage(msgId)
                    })
                    Screen.STATS -> StatsScreen(vm, state)
                    Screen.FILES -> FilesScreen(state)
                    Screen.SETTINGS -> SettingsScreen(vm, state, onPickFolder, onPickZip)
                }
            }
        }
    }
}

@Composable
private fun DrawerPanel(
    vm: AppViewModel,
    state: UiState,
    onPickFolder: () -> Unit,
    onPickZip: () -> Unit,
    closeDrawer: () -> Unit
) {
    var sortMenu by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxHeight()) {
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("档", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
                    }
                    Column(Modifier.padding(start = 10.dp)) {
                        Text("聊天档案", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${state.totalConvs} 个会话 · ${Fmt.compact(state.totalMsgs)} 条消息",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallAction("导入文件夹", Icons.Outlined.FolderOpen, Modifier.weight(1f)) { closeDrawer(); onPickFolder() }
                    SmallAction("导入 ZIP", Icons.Outlined.FolderZip, Modifier.weight(1f)) { closeDrawer(); onPickZip() }
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                DrawerNav("会话", Icons.Outlined.ChatBubbleOutline, state.screen == Screen.CHATS && state.selectedId == null) {
                    vm.closeConversation(); vm.setScreen(Screen.CHATS); closeDrawer()
                }
                DrawerNav("全文搜索", Icons.Outlined.Search, state.screen == Screen.SEARCH) {
                    vm.closeConversation(); vm.setScreen(Screen.SEARCH); closeDrawer()
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
                Box {
                    TextButton(onClick = { sortMenu = true }) {
                        Text(
                            when (state.sort) {
                                SortMode.NEWEST -> "最新优先"
                                SortMode.OLDEST -> "最早优先"
                                SortMode.MESSAGES -> "消息最多"
                                SortMode.CHARS -> "字数最多"
                                SortMode.TITLE -> "标题 A-Z"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortMode.entries.forEach { m ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (m) {
                                            SortMode.NEWEST -> "最新优先"
                                            SortMode.OLDEST -> "最早优先"
                                            SortMode.MESSAGES -> "消息最多"
                                            SortMode.CHARS -> "字数最多"
                                            SortMode.TITLE -> "标题 A-Z"
                                        }
                                    )
                                },
                                onClick = { vm.setSort(m); sortMenu = false }
                            )
                        }
                    }
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
                if (state.year != null) {
                    FilterChip(
                        selected = true,
                        onClick = { vm.setYear(null) },
                        label = { Text("${state.year} 年 ✕", style = MaterialTheme.typography.labelSmall) }
                    )
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
                ConvRow(c, c.id == state.selectedId) {
                    vm.openConversation(c.id)
                    closeDrawer()
                }
            }
        }

        item { Column(Modifier.height(16.dp)) {} }
    }
}

@Composable
private fun DrawerNav(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        icon = { Icon(icon, null, modifier = Modifier.size(19.dp)) },
        selected = selected,
        onClick = onClick,
        badge = null,
        modifier = Modifier.padding(vertical = 1.dp)
    )
}

@Composable
private fun SmallAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
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

@Composable
private fun ConvRow(c: Conversation, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent
            )
            .clickable(onClick = onClick)
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
                c.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                "${Fmt.date(c.createTs)} · ${c.msgCount} 条" + if (c.attachmentCount > 0) " · ${c.attachmentCount} 附件" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
private fun ChatsHome(state: UiState, onOpen: (String) -> Unit) {
    if (state.conversations.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("还没有导入数据", style = MaterialTheme.typography.titleMedium)
            Text(
                "点右上角「导入」，选择 ChatGPT 导出包的文件夹，\n或直接选择导出的 .zip 压缩包。",
                Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "导入后可离线浏览全部会话、搜索正文、查看附件与统计图表。",
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
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
                    Text(c.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${Fmt.date(c.createTs)} · ${c.msgCount} 条 · ${c.category}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(Fmt.compact(c.charCount) + " 字", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
