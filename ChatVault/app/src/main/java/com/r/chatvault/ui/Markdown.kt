package com.r.chatvault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Bullets(val items: List<String>, val ordered: Boolean) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Divider : MdBlock
}

fun parseMarkdown(src: String): List<MdBlock> {
    val lines = src.replace("\r\n", "\n").split("\n")
    val out = ArrayList<MdBlock>()
    val paragraph = StringBuilder()
    val bullets = ArrayList<String>()
    var ordered = false

    fun flushParagraph() {
        if (paragraph.isNotBlank()) {
            out.add(MdBlock.Paragraph(paragraph.toString().trim()))
            paragraph.clear()
        }
    }

    fun flushBullets() {
        if (bullets.isNotEmpty()) {
            out.add(MdBlock.Bullets(bullets.toList(), ordered))
            bullets.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            flushParagraph(); flushBullets()
            val lang = trimmed.removePrefix("```").trim()
            val sb = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                sb.append(lines[i]).append('\n')
                i++
            }
            out.add(MdBlock.Code(lang, sb.toString().trimEnd('\n')))
            i++
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph(); flushBullets()
            i++
            continue
        }

        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            flushParagraph(); flushBullets()
            out.add(MdBlock.Divider)
            i++
            continue
        }

        val heading = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
        if (heading != null) {
            flushParagraph(); flushBullets()
            out.add(MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2]))
            i++
            continue
        }

        if (trimmed.startsWith(">")) {
            flushParagraph(); flushBullets()
            val sb = StringBuilder()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                sb.append(lines[i].trim().removePrefix(">").trim()).append(' ')
                i++
            }
            out.add(MdBlock.Quote(sb.toString().trim()))
            continue
        }

        if (trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.count { it == '|' } >= 2) {
            flushParagraph(); flushBullets()
            val rows = ArrayList<List<String>>()
            while (i < lines.size && lines[i].trim().startsWith("|")) {
                val cells = lines[i].trim().trim('|').split('|').map { it.trim() }
                val isSeparator = cells.all { it.matches(Regex(":?-{2,}:?")) }
                if (cells.isNotEmpty() && !isSeparator) rows.add(cells)
                i++
            }
            if (rows.isNotEmpty()) out.add(MdBlock.Table(rows))
            continue
        }

        val bullet = Regex("^([-*+]|\\d{1,2}[.)])\\s+(.*)$").find(trimmed)
        if (bullet != null) {
            flushParagraph()
            val isOrdered = bullet.groupValues[1].first().isDigit()
            if (bullets.isNotEmpty() && ordered != isOrdered) flushBullets()
            ordered = isOrdered
            bullets.add(bullet.groupValues[2])
            i++
            continue
        }

        paragraph.append(trimmed).append(' ')
        i++
    }
    flushParagraph(); flushBullets()
    return out
}

/** 行内格式：**粗体**、`代码`、[文字](链接)，并可叠加搜索高亮 */
fun inlineAnnotated(
    text: String,
    codeColor: Color,
    linkColor: Color,
    highlight: List<String> = emptyList(),
    highlightColor: Color = Color.Transparent
): AnnotatedString {
    val base = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val boldStart = text.indexOf("**", i)
            val codeStart = text.indexOf('`', i)
            val linkStart = text.indexOf('[', i)
            val next = listOf(boldStart, codeStart, linkStart).filter { it >= 0 }.minOrNull() ?: -1
            if (next < 0) {
                append(text.substring(i))
                break
            }
            if (next > i) append(text.substring(i, next))
            when (next) {
                boldStart -> {
                    val end = text.indexOf("**", boldStart + 2)
                    if (end < 0) {
                        append(text.substring(boldStart)); i = text.length
                    } else {
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                            append(text.substring(boldStart + 2, end))
                        }
                        i = end + 2
                    }
                }

                codeStart -> {
                    val end = text.indexOf('`', codeStart + 1)
                    if (end < 0) {
                        append(text.substring(codeStart)); i = text.length
                    } else {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor, fontSize = 13.sp)) {
                            append(text.substring(codeStart + 1, end))
                        }
                        i = end + 1
                    }
                }

                else -> {
                    val close = text.indexOf(']', linkStart)
                    val open = if (close > 0) text.indexOf('(', close) else -1
                    val end = if (open > 0) text.indexOf(')', open) else -1
                    if (close > 0 && open == close + 1 && end > 0) {
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                            append(text.substring(linkStart + 1, close))
                        }
                        i = end + 1
                    } else {
                        append(text.substring(linkStart, linkStart + 1)); i = linkStart + 1
                    }
                }
            }
        }
    }

    if (highlight.isEmpty() || highlightColor == Color.Transparent) return base
    val plain = base.text
    val extra = ArrayList<AnnotatedString.Range<SpanStyle>>()
    for (kw in highlight) {
        if (kw.isBlank()) continue
        var from = 0
        while (from <= plain.length) {
            val idx = plain.indexOf(kw, from, ignoreCase = true)
            if (idx < 0) break
            extra.add(AnnotatedString.Range(SpanStyle(background = highlightColor), idx, idx + kw.length))
            from = idx + kw.length
            if (kw.isEmpty()) break
        }
    }
    if (extra.isEmpty()) return base
    return AnnotatedString(base.text, base.spanStyles + extra, base.paragraphStyles)
}

@Composable
fun MarkdownView(
    text: String,
    modifier: Modifier = Modifier,
    highlight: List<String> = emptyList(),
    wrapCode: Boolean = true
) {
    val colors = LocalChatColors.current
    val blocks = remember(text) { parseMarkdown(text) }
    val linkColor = MaterialTheme.colorScheme.secondary
    val codeColor = MaterialTheme.colorScheme.primary
    SelectionContainer(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Heading -> Text(
                        text = inlineAnnotated(block.text, codeColor, linkColor, highlight, colors.highlight),
                        fontSize = when (block.level) {
                            1 -> 20.sp
                            2 -> 18.sp
                            3 -> 16.sp
                            else -> 15.sp
                        },
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    is MdBlock.Paragraph -> Text(
                        text = inlineAnnotated(block.text, codeColor, linkColor, highlight, colors.highlight),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        block.items.forEachIndexed { idx, item ->
                            Row {
                                Text(
                                    if (block.ordered) "${idx + 1}. " else "· ",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    inlineAnnotated(item, codeColor, linkColor, highlight, colors.highlight),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(
                            Modifier
                                .padding(end = 10.dp)
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outline)
                        ) {}
                        Text(
                            inlineAnnotated(block.text, codeColor, linkColor, highlight, colors.highlight),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    is MdBlock.Code -> CodeBlock(block.lang, block.code, wrapCode)

                    is MdBlock.Table -> TableBlock(block.rows)

                    MdBlock.Divider -> Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .height(0.5.dp)
                            .background(MaterialTheme.colorScheme.outline)
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String, wrap: Boolean) {
    val colors = LocalChatColors.current
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.codeBg, RoundedCornerShape(10.dp))
            .border(0.5.dp, colors.codeBorder, RoundedCornerShape(10.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                lang.ifBlank { "code" }.lowercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "复制",
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(" 复制", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
        ) {
            Text(
                code,
                style = MonoStyle,
                softWrap = wrap,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun TableBlock(rows: List<List<String>>) {
    val cols = rows.maxOf { it.size }.coerceAtMost(6)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        rows.forEachIndexed { rIdx, row ->
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until cols) {
                    Text(
                        row.getOrElse(c) { "" },
                        style = if (rIdx == 0) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                        fontWeight = if (rIdx == 0) FontWeight.Medium else FontWeight.Normal,
                        color = if (rIdx == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(end = 6.dp)
                    )
                }
            }
        }
    }
}
