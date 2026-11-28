package com.r.chatvault.ui

import com.r.chatvault.R
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r.chatvault.data.UiNode
import com.r.chatvault.data.UiTemplate
import com.r.chatvault.data.uiMarkerIndex

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Bullets(val items: List<String>, val ordered: Boolean) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    /** ChatGPT 生成式 UI 模板块（badge / 表单 / 卡片 / 表格…），见 UiTemplate */
    data class Ui(val nodes: List<UiNode>) : MdBlock
    data object Divider : MdBlock
}

fun parseMarkdown(src: String): List<MdBlock> {
    // 先把 UI 模板整块摘出来，正文仍按 Markdown 解析；代码围栏内不动
    val extracted = UiTemplate.extract(src)
    val blocks = extracted.blocks
    val vars = extracted.vars
    val lines = extracted.text.replace("\r\n", "\n").split("\n")
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
        val raw = line.trim()

        val uiIdx = uiMarkerIndex(raw)
        if (uiIdx != null) {
            val nodes = blocks.getOrNull(uiIdx)
            if (nodes != null) {
                flushParagraph(); flushBullets()
                out.add(MdBlock.Ui(nodes))
                i++
                continue
            }
        }

        val trimmed = UiTemplate.inlineText(raw, vars)

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
                sb.append(UiTemplate.inlineText(lines[i].trim().removePrefix(">").trim(), vars)).append(' ')
                i++
            }
            out.add(MdBlock.Quote(sb.toString().trim()))
            continue
        }

        if (isTableStart(lines, i)) {
            flushParagraph(); flushBullets()
            val rows = ArrayList<List<String>>()
            while (i < lines.size && isTableLine(lines[i].trim())) {
                val cells = lines[i].trim().trim('|').split('|').map { UiTemplate.inlineText(it.trim(), vars) }
                val isSeparator = cells.isNotEmpty() && cells.all { it.matches(Regex(":?-{2,}:?")) }
                if (cells.isNotEmpty() && !isSeparator) rows.add(cells)
                i++
            }
            if (rows.size >= 2) out.add(MdBlock.Table(rows))
            else if (rows.size == 1) out.add(MdBlock.Paragraph(trimmed))
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

private fun isTableLine(t: String): Boolean {
    if (t.startsWith("```") || t.startsWith("~~~")) return false
    return t.count { it == '|' } >= 1
}

/** 两列表格可能没有外侧竖线，需要看下一行是不是分隔行 */
private fun isTableStart(lines: List<String>, index: Int): Boolean {
    val t = lines[index].trim()
    if (t.startsWith("```") || t.startsWith("~~~")) return false
    val pipes = t.count { it == '|' }
    if (pipes >= 2) return true
    if (pipes == 1) {
        val next = lines.getOrNull(index + 1)?.trim() ?: return false
        val cells = next.trim('|').split('|').map { it.trim() }
        return cells.isNotEmpty() && cells.all { it.matches(Regex(":?-{2,}:?")) }
    }
    return false
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
    wrapCode: Boolean = true,
    selectionKey: Any? = null
) {
    val colors = LocalChatColors.current
    val blocks = remember(text) { parseMarkdown(text) }
    val linkColor = MaterialTheme.colorScheme.secondary
    val codeColor = MaterialTheme.colorScheme.primary
    // 只重建选择容器，不重解析 Markdown：外部换 key 即可清掉文字选择
    key(selectionKey) {
    SelectionContainer(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Heading -> {
                        val size = when (block.level) {
                            1 -> 22.sp
                            2 -> 19.sp
                            3 -> 17.sp
                            else -> 16.sp
                        }
                        Text(
                            text = inlineAnnotated(block.text, codeColor, linkColor, highlight, colors.highlight),
                            fontSize = size,
                            lineHeight = size * 1.35f,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = if (block.level <= 2) 10.dp else 8.dp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    is MdBlock.Paragraph -> Text(
                        text = inlineAnnotated(block.text, codeColor, linkColor, highlight, colors.highlight),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        block.items.forEachIndexed { idx, item ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    if (block.ordered) "${idx + 1}. " else "· ",
                                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    inlineAnnotated(item, codeColor, linkColor, highlight, colors.highlight),
                                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
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
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    is MdBlock.Ui -> UiTemplateView(block.nodes)

                    is MdBlock.Code -> CodeBlock(block.lang, block.code, wrapCode, highlight, colors.highlight)

                    is MdBlock.Table -> TableBlock(block.rows, highlight, colors.highlight)

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
}

@Composable
private fun CodeBlock(
    lang: String,
    code: String,
    wrap: Boolean,
    highlight: List<String>,
    highlightColor: Color
) {
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
                    contentDescription = stringResource(R.string.common_copy),
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(" " + stringResource(R.string.common_copy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)
        ) {
            Text(
                highlightAll(code, highlight, highlightColor),
                style = MonoStyle,
                softWrap = wrap,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
internal fun TableBlock(
    rows: List<List<String>>,
    highlight: List<String>,
    highlightColor: Color
) {
    if (rows.isEmpty()) return
    val cols = rows.maxOf { it.size }.coerceAtMost(12)
    val many = cols > 4
    val codeColor = MaterialTheme.colorScheme.primary
    val linkColor = MaterialTheme.colorScheme.secondary
    if (many) {
        Column(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            rows.forEachIndexed { rIdx, row ->
                Row {
                    for (c in 0 until cols) {
                        TableCell(
                            row.getOrElse(c) { "" },
                            header = rIdx == 0,
                            modifier = Modifier.width(140.dp).padding(end = 6.dp),
                            codeColor = codeColor,
                            linkColor = linkColor,
                            highlight = highlight,
                            highlightColor = highlightColor
                        )
                    }
                }
            }
        }
    } else {
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 12.dp, vertical = 2.dp)
        ) {
            rows.forEachIndexed { rIdx, row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                    for (c in 0 until cols) {
                        TableCell(
                            row.getOrElse(c) { "" },
                            header = rIdx == 0,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            codeColor = codeColor,
                            linkColor = linkColor,
                            highlight = highlight,
                            highlightColor = highlightColor
                        )
                    }
                }
                if (rIdx < rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    header: Boolean,
    modifier: Modifier,
    codeColor: Color,
    linkColor: Color,
    highlight: List<String>,
    highlightColor: Color
) {
    Text(
        inlineAnnotated(text, codeColor, linkColor, highlight, highlightColor),
        modifier = modifier,
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
        fontWeight = if (header) FontWeight.Medium else FontWeight.Normal,
        color = if (header) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    )
}
