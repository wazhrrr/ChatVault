package com.r.chatvault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r.chatvault.BuildConfig
import com.r.chatvault.data.Fmt
import com.r.chatvault.vm.AppViewModel
import com.r.chatvault.vm.UiState
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    vm: AppViewModel,
    state: UiState,
    onPickFolder: () -> Unit,
    onPickZip: () -> Unit,
    onPickMarkdown: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {

        SectionCard("数据源") {
            Text(
                "导入 ChatGPT 官方导出包，或直接导入 Markdown 聊天记录。已导入的会话按 update_time 增量更新，不会重复写入。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingButton("文件夹", Icons.Outlined.FolderOpen, Modifier.weight(1f), onPickFolder)
                SettingButton("ZIP 包", Icons.Outlined.FolderZip, Modifier.weight(1f), onPickZip)
            }
            SettingButton(
                "导入 Markdown（可多选，时间取文件修改时间）",
                Icons.Outlined.Description,
                Modifier.fillMaxWidth().padding(top = 8.dp),
                onPickMarkdown
            )
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("本地库", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${state.totalConvs} 个会话 · ${Fmt.compact(state.totalMsgs)} 条消息 · ${state.totalAtts} 个附件实体",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { confirmClear = true }) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text("清空", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                "所有数据只存在本机（SQLite + 私有目录），不联网、不上传。\n" +
                    "1.2.0 起会保留被编辑废弃的对话分支；旧数据重新导入一次导出包即可补齐（收藏、标签、摘录都会保留）。",
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard("备份与恢复") {
            Text(
                "把数据库和已还原的附件打包成一个 zip。换机、重装或升级前建议先备份一份。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingButton("备份到文件", Icons.Outlined.Backup, Modifier.weight(1f), onBackup)
                SettingButton("从备份恢复", Icons.Outlined.Restore, Modifier.weight(1f)) {
                    confirmRestore = true
                }
            }
            Text(
                "数据库结构升级前会自动在应用私有目录留一份快照；恢复失败会自动回滚到原数据。",
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard("外观") {
            Text("主题", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "跟随系统", 1 to "深色", 2 to "浅色").forEach { (v, label) ->
                    FilterChip(
                        selected = state.themeMode == v,
                        onClick = { vm.setThemeMode(v) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("字号", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text(
                    "${(state.fontScale * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = state.fontScale,
                onValueChange = { vm.setFontScale(it) },
                valueRange = 0.85f..1.3f
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("代码块自动换行", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "关闭后代码块左右滑动查看，长命令更易读",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = state.codeWrap, onCheckedChange = { vm.setCodeWrap(it) })
            }
        }

        SectionCard("关于") {
            Text(
                "ChatVault ${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "一个完全离线的 ChatGPT 导出浏览器：会话阅读、全文搜索、附件查看、统计图表。\n" +
                    "支持文件夹、ZIP 与 Markdown 三种导入方式；保留被编辑废弃的分支；" +
                    "自动抽取代码片段和文本文档索引；可备份、可恢复。",
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "阅读顺序建议：先导入 → 抽屉里按主题翻 → 搜索定位 → 摘录沉淀 → 统计看节奏。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(Modifier.height(24.dp)) {}
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空本地数据？") },
            text = { Text("会删除全部会话、消息索引、摘录、代码片段、已还原的附件文件，以及升级前的本地数据库快照（原始导出包不受影响）。") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.clearAll() }) {
                    Text("确认清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("从备份恢复？") },
            text = {
                Text(
                    "恢复会覆盖当前全部会话、摘录、标签与附件。\n" +
                        "失败会自动回滚，但为稳妥起见，建议先做一次备份。"
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRestore = false; onRestore() }) {
                    Text("选择备份文件", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

@Composable
private fun SettingButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(" $label", style = MaterialTheme.typography.labelMedium, fontSize = 13.sp)
    }
}
