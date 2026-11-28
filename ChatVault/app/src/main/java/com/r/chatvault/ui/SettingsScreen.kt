package com.r.chatvault.ui

import android.app.Activity
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r.chatvault.BuildConfig
import com.r.chatvault.R
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
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val repoUrl = "https://github.com/wazhrrr/ChatVault"

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {

        SectionCard(stringResource(R.string.settings_source)) {
            Text(
                stringResource(R.string.settings_source_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingButton(
                    stringResource(R.string.folder),
                    Icons.Outlined.FolderOpen,
                    Modifier.weight(1f),
                    onPickFolder
                )
                SettingButton(
                    stringResource(R.string.settings_zip),
                    Icons.Outlined.FolderZip,
                    Modifier.weight(1f),
                    onPickZip
                )
            }
            SettingButton(
                stringResource(R.string.settings_import_md),
                Icons.Outlined.Description,
                Modifier.fillMaxWidth().padding(top = 8.dp),
                onPickMarkdown
            )
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_library), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(
                            R.string.settings_library_stats,
                            state.totalConvs,
                            Fmt.compact(state.totalMsgs),
                            state.totalAtts
                        ),
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
                    Text(
                        stringResource(R.string.settings_clear),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Text(
                stringResource(R.string.settings_local_note),
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(stringResource(R.string.settings_backup)) {
            Text(
                stringResource(R.string.settings_backup_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingButton(
                    stringResource(R.string.settings_backup_to),
                    Icons.Outlined.Backup,
                    Modifier.weight(1f),
                    onBackup
                )
                SettingButton(
                    stringResource(R.string.settings_restore_from),
                    Icons.Outlined.Restore,
                    Modifier.weight(1f)
                ) {
                    confirmRestore = true
                }
            }
            Text(
                stringResource(R.string.settings_backup_note),
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(stringResource(R.string.settings_appearance)) {
            Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.labelMedium)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    0 to R.string.settings_theme_system,
                    1 to R.string.settings_theme_dark,
                    2 to R.string.settings_theme_light
                ).forEach { (v, labelRes) ->
                    FilterChip(
                        selected = state.themeMode == v,
                        onClick = { vm.setThemeMode(v) },
                        label = {
                            Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall)
                        }
                    )
                }
            }

            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.settings_font),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium
                )
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
                    Text(
                        stringResource(R.string.settings_code_wrap),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.settings_code_wrap_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = state.codeWrap, onCheckedChange = { vm.setCodeWrap(it) })
            }
        }

        SectionCard(stringResource(R.string.settings_language)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "" to stringResource(R.string.settings_language_system),
                    "zh" to "中文",
                    "en" to "English"
                ).forEach { (tag, label) ->
                    FilterChip(
                        selected = state.langTag == tag,
                        onClick = {
                            if (state.langTag != tag) {
                                vm.setLanguage(tag)
                                // 资源由 attachBaseContext 按 langTag 加载，重建界面即可生效
                                (context as? Activity)?.recreate()
                            }
                        },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            Text(
                stringResource(R.string.settings_language_note),
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(stringResource(R.string.settings_about)) {
            Text(
                "ChatVault ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                stringResource(R.string.settings_about_desc),
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(
                Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { runCatching { uriHandler.openUri(repoUrl) } }
            ) {
                Text(
                    stringResource(R.string.github_repo),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    repoUrl,
                    Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline
                )
            }
        }

        Column(Modifier.height(24.dp)) {}
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings_clear_title)) },
            text = { Text(stringResource(R.string.settings_clear_body)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.clearAll() }) {
                    Text(
                        stringResource(R.string.settings_clear_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.settings_restore_title)) },
            text = { Text(stringResource(R.string.settings_restore_body)) },
            confirmButton = {
                TextButton(onClick = { confirmRestore = false; onRestore() }) {
                    Text(
                        stringResource(R.string.settings_pick_backup),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
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
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
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
