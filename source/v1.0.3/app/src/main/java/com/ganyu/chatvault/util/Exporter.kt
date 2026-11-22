package com.ganyu.chatvault.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.ganyu.chatvault.data.Attachment
import com.ganyu.chatvault.data.Conversation
import com.ganyu.chatvault.data.Fmt
import com.ganyu.chatvault.data.Message
import java.io.File

object Exporter {

    fun buildMarkdown(conv: Conversation, messages: List<Message>, attachments: List<Attachment>): String {
        val sb = StringBuilder()
        sb.append("# ").append(conv.title).append("\n\n")
        sb.append("> 创建：").append(Fmt.dateTime(conv.createTs))
            .append(" · 更新：").append(Fmt.dateTime(conv.updateTs))
            .append(" · 消息 ").append(messages.size).append(" 条")
            .append(" · 主题：").append(conv.category).append("\n\n")
        if (attachments.isNotEmpty()) {
            sb.append("**附件**：")
            sb.append(attachments.joinToString("、") { it.name })
            sb.append("\n\n---\n\n")
        }
        messages.forEach { m ->
            sb.append(if (m.role == "user") "## 我\n\n" else "## ChatGPT\n\n")
            if (!m.reasoning.isNullOrBlank()) {
                sb.append("<details><summary>思考过程</summary>\n\n").append(m.reasoning).append("\n\n</details>\n\n")
            }
            sb.append(m.text.trim()).append("\n\n")
        }
        return sb.toString()
    }

    fun shareMarkdown(context: Context, conv: Conversation, messages: List<Message>, attachments: List<Attachment>): Boolean {
        return runCatching {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val safe = conv.title.replace(Regex("[\\\\/:*?\"<>|\\n]"), "_").take(48).ifBlank { "conversation" }
            val file = File(dir, "$safe.md")
            file.writeText(buildMarkdown(conv, messages, attachments))
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, conv.title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "导出会话"))
        }.isSuccess
    }
}
