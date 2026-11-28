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
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.r.chatvault.R
import com.r.chatvault.data.ClipItem
import com.r.chatvault.data.Fmt
import com.r.chatvault.vm.AppViewModel
import com.r.chatvault.vm.UiState

@Composable
fun ClipsScreen(vm: AppViewModel, state: UiState, onOpen: (String, String) -> Unit) {
    var editing by remember { mutableStateOf<ClipItem?>(null) }
    var deleting by remember { mutableStateOf<ClipItem?>(null) }
    var menuFor by remember { mutableStateOf<Long?>(null) }

    val tags = state.clipTags
    val keywords = remember(state.clipQuery) {
        state.clipQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.clipQuery,
            onValueChange = vm::setClipQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.clip_search_hint), style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall
        )

        if (tags.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = state.clipTag == null,
                    onClick = { vm.setClipTag(null) },
                    label = { Text(stringResource(R.string.common_all), style = MaterialTheme.typography.labelSmall) }
                )
                tags.forEach { tag ->
                    FilterChip(
                        selected = state.clipTag == tag,
                        onClick = { vm.setClipTag(if (state.clipTag == tag) null else tag) },
                        label = { Text("#$tag", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }

        Text(
            stringResource(R.string.clip_count, state.clips.size) +
                if (state.clipCount > state.clips.size) {
                    " " + stringResource(R.string.clip_showing, state.clips.size, state.clipCount)
                } else "",
            Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.clips.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.clip_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.clips, key = { it.id }) { clip ->
                    ClipCard(
                        clip = clip,
                        keywords = keywords,
                        menuOpen = menuFor == clip.id,
                        onMenu = { menuFor = if (menuFor == clip.id) null else clip.id },
                        onDismissMenu = { menuFor = null },
                        onOpen = {
                            val conv = clip.convId
                            if (!conv.isNullOrBlank()) onOpen(conv, clip.msgId ?: "")
                        },
                        onEdit = { editing = clip; menuFor = null },
                        onDelete = { deleting = clip; menuFor = null },
                        onRevert = {
                            vm.revertClip(clip.id)
                            menuFor = null
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    editing?.let { clip ->
        ClipEditDialog(
            clip = clip,
            allTags = state.clipTags,
            onDismiss = { editing = null },
            onSave = { note, tagsText ->
                vm.updateClip(clip.id, note, tagsText)
                editing = null
            }
        )
    }

    deleting?.let { clip ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.clip_delete_title)) },
            text = { Text(stringResource(R.string.clip_delete_body)) },
            confirmButton = {
                TextButton(onClick = { vm.deleteClip(clip.id); deleting = null }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

@Composable
private fun ClipCard(
    clip: ClipItem,
    keywords: List<String>,
    menuOpen: Boolean,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRevert: () -> Unit
) {
    val highlightColor = LocalChatColors.current.highlight
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (clip.kind == "bookmark") Icons.Outlined.BookmarkBorder else Icons.Outlined.FormatQuote,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                clip.convTitle ?: stringResource(R.string.no_chat_linked),
                Modifier.padding(start = 6.dp).weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                Fmt.date(clip.createdTs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box {
                IconButton(onClick = onMenu, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.common_more), modifier = Modifier.size(17.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_note_tags)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = onEdit
                    )
                    if (clip.canRevert) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.revert_last)) },
                            leadingIcon = { Icon(Icons.Outlined.Restore, null) },
                            onClick = onRevert
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete)) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                        onClick = onDelete
                    )
                }
            }
        }

        if (clip.text.isNotBlank()) {
            Text(
                highlightAll(clip.text, keywords, highlightColor),
                Modifier.padding(top = 6.dp, end = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (!clip.note.isNullOrBlank()) {
            Text(
                highlightAll(clip.note, keywords, highlightColor),
                Modifier
                    .padding(top = 8.dp, end = 8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (clip.tagList.isNotEmpty()) {
            Row(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                clip.tagList.take(6).forEach { tag ->
                    Text(
                        highlightAll("#$tag", keywords, highlightColor),
                        Modifier
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipEditDialog(
    clip: ClipItem,
    allTags: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var note by remember(clip.id) { mutableStateOf(clip.note ?: "") }
    var tags by remember(clip.id) { mutableStateOf(clip.tags) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_note_tags)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.common_note)) },
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
                                onClick = { tags = if (tags.isBlank()) t else "$tags,$t" },
                                label = { Text("#$t", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(note, tags) }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    note = clip.note ?: ""
                    tags = clip.tags
                }) { Text(stringResource(R.string.common_revert)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    )
}
