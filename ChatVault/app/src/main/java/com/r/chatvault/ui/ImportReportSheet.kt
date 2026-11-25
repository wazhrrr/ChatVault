package com.r.chatvault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.r.chatvault.data.Attachment
import com.r.chatvault.data.Fmt
import com.r.chatvault.data.ImportReport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportReportSheet(
    report: ImportReport,
    onDismiss: () -> Unit,
    onOpenConversation: (String, String) -> Unit,
    onViewFile: (Attachment) -> Unit
) {
    val s = report.summary
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "导入完成",
                    Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("关闭", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MiniStat("新增", s.added)
                MiniStat("更新", s.updated)
                MiniStat("跳过", s.skipped)
                MiniStat("消息", s.messages)
            }
            Text(
                buildString {
                    append("附件实体 ${s.attachments} 个")
                    if (s.messages > 0 || s.mdFiles > 0) {
                        if (s.mdFiles > 0) append(" · Markdown ${s.mdFiles} 个")
                    }
                    if (s.orphanFiles > 0) append(" · 未引用文件 ${s.orphanFiles} 个")
                },
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(top = 8.dp)) {
                if (report.missing.isNotEmpty()) {
                    item {
                        SectionHeader(
                            "缺失附件 ${report.missing.size} 个",
                            "导出包里只有记录、没有实体文件；点一项可跳到原消息"
                        )
                    }
                    items(report.missing, key = { "m-${it.id}" }) { a ->
                        ReportRow(a, missing = true) {
                            val conv = a.convId
                            val msg = a.msgId
                            if (!conv.isNullOrBlank() && !msg.isNullOrBlank()) {
                                onOpenConversation(conv, msg)
                                onDismiss()
                            } else {
                                onViewFile(a)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }

                if (report.orphans.isNotEmpty()) {
                    item {
                        SectionHeader(
                            "未被引用的文件 ${report.orphans.size} 个",
                            "磁盘上有实体，但没有任何会话引用；点一项可直接查看"
                        )
                    }
                    items(report.orphans, key = { "o-${it.id}" }) { a ->
                        ReportRow(a, missing = false) { onViewFile(a) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }

                if (report.missing.isEmpty() && report.orphans.isEmpty()) {
                    item {
                        Text(
                            "没有缺失附件，也没有未引用文件。",
                            Modifier.padding(vertical = 18.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                item { Column(Modifier.padding(bottom = 26.dp)) {} }
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: Int) {
    Column(
        Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(9.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("$value", style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionHeader(title: String, sub: String) {
    Column(Modifier.padding(top = 14.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.ErrorOutline,
                null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(15.dp)
            )
            Text(" $title", style = MaterialTheme.typography.labelMedium)
        }
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReportRow(a: Attachment, missing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (missing) Icons.Outlined.InsertDriveFile else iconFor(a.kind),
            null,
            tint = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(19.dp)
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                a.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${Fmt.bytes(a.size)} · ${if (missing) "文件缺失" else a.kind}",
                style = MaterialTheme.typography.labelSmall,
                color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
