package com.r.chatvault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r.chatvault.R
import com.r.chatvault.data.Palette
import com.r.chatvault.data.UiNode
import com.r.chatvault.data.UiTemplate

/**
 * 渲染 ChatGPT 生成式 UI 模板（见 [com.r.chatvault.data.UiTemplate]）。
 * 只读：不执行事件、不跑 JS，按钮/输入框只呈现静态样式。
 */
@Composable
fun UiTemplateView(nodes: List<UiNode>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        nodes.forEach { UiNodeView(it) }
    }
}

@Composable
private fun UiNodeView(
    node: UiNode,
    modifier: Modifier = Modifier,
    inRow: Boolean = false,
    groupValue: String? = null
) {
    when (node) {
        is UiNode.Text -> {
            // 容器里的文字也可能带 Markdown（表格/列表/小标题），不能只当纯文本画
            UiTextBody(node.text, modifier)
        }

        is UiNode.If -> node.branches.firstOrNull()?.children?.forEach {
            UiNodeView(it, groupValue = groupValue)
        }

        is UiNode.Each -> node.children.forEach { UiNodeView(it, groupValue = groupValue) }

        is UiNode.El -> UiElement(node, modifier, inRow, groupValue)
    }
}

@Composable
private fun UiElement(
    el: UiNode.El,
    modifier: Modifier,
    inRow: Boolean,
    groupValue: String?
) {
    val a = el.attrs
    when (el.tag.lowercase()) {
        "box" -> {
            val pad = paddingOf(a["padding"])
            val gap = gapOf(a["gap"]) ?: 6.dp
            val shape = RoundedCornerShape(radiusOf(a["radius"]))
            val bg = colorOf(a["background"])
            Column(
                modifier
                    .then(if (inRow) Modifier else Modifier.fillMaxWidth())
                    .then(pxOf(a["height"])?.let { Modifier.height(it) } ?: Modifier)
                    .then(pxOf(a["width"])?.let { Modifier.width(it) } ?: Modifier)
                    .then(if (bg != null) Modifier.background(bg, shape) else Modifier)
                    .then(
                        if (a.containsKey("border")) {
                            Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                        } else {
                            Modifier
                        }
                    )
                    .padding(pad),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                el.children.forEach { UiNodeView(it, groupValue = groupValue) }
            }
        }

        "row" -> {
            val gap = gapOf(a["gap"]) ?: 8.dp
            val vAlign = when (a["align"]) {
                "center" -> Alignment.CenterVertically
                "end", "bottom" -> Alignment.Bottom
                else -> Alignment.Top
            }
            val arrangement = when (a["justify"]) {
                "between" -> Arrangement.SpaceBetween
                "center" -> Arrangement.Center
                "end", "right" -> Arrangement.End
                else -> Arrangement.spacedBy(gap)
            }
            Row(
                (if (inRow) modifier else modifier.fillMaxWidth()),
                horizontalArrangement = arrangement,
                verticalAlignment = vAlign
            ) {
                el.children.forEach { child ->
                    if (child is UiNode.El && child.tag.equals("spacer", true)) {
                        // <spacer/> 在 row 里是「把后面的内容推到另一头」
                        Spacer(Modifier.weight(1f))
                    } else {
                        val flex = (child as? UiNode.El)?.attrs?.containsKey("flex") == true
                        UiNodeView(
                            child,
                            if (flex) Modifier.weight(1f) else Modifier,
                            inRow = true,
                            groupValue = groupValue
                        )
                    }
                }
            }
        }

        "grid", "grid-item" -> {
            if (el.tag.lowercase() == "grid-item") {
                Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    el.children.forEach { UiNodeView(it, groupValue = groupValue) }
                }
            } else {
                val cols = a["columns"]?.toIntOrNull()?.coerceIn(1, 4) ?: 2
                val gap = gapOf(a["gap"]) ?: 6.dp
                Column((if (inRow) modifier else modifier.fillMaxWidth()), verticalArrangement = Arrangement.spacedBy(gap)) {
                    el.children.chunked(cols).forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            line.forEach { cell ->
                                UiNodeView(cell, Modifier.weight(1f), inRow = true, groupValue = groupValue)
                            }
                            repeat(cols - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        "title" -> Text(
            text = plain(el),
            fontSize = sizeOf(a["size"], 18.sp),
            lineHeight = sizeOf(a["size"], 18.sp) * 1.3f,
            fontWeight = FontWeight.SemiBold,
            color = colorOf(a["color"]) ?: MaterialTheme.colorScheme.onSurface,
            modifier = if (inRow) modifier else modifier.fillMaxWidth()
        )

        "text" -> RichText(
            plain(el),
            MaterialTheme.typography.bodyMedium.copy(
                fontSize = sizeOf(a["size"], 15.sp)
            ),
            modifier,
            colorOf(a["color"]) ?: MaterialTheme.colorScheme.onSurface
        )

        "caption" -> RichText(
            plain(el),
            MaterialTheme.typography.labelSmall,
            modifier,
            MaterialTheme.colorScheme.onSurfaceVariant
        )

        "badge" -> {
            val tone = colorOf(a["color"]) ?: MaterialTheme.colorScheme.primary
            Box(
                modifier
                    .background(tone.copy(alpha = 0.16f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(plain(el), style = MaterialTheme.typography.labelMedium, color = tone)
            }
        }

        "divider" -> HorizontalDivider(
            modifier.padding(vertical = 2.dp),
            color = if (a["color"] == "subtle") MaterialTheme.colorScheme.outlineVariant
            else MaterialTheme.colorScheme.outline
        )

        "icon" -> {
            val size = sizeOf(a["size"], 18.sp).value.dp
            val tint = colorOf(a["color"]) ?: MaterialTheme.colorScheme.primary
            val vector = iconFor(a["name"])
            if (vector != null) {
                Icon(vector, null, modifier.size(size), tint = tint)
            } else {
                Box(
                    modifier
                        .padding(top = 6.dp, end = 2.dp)
                        .size(8.dp)
                        .background(tint, RoundedCornerShape(50))
                )
            }
        }

        "list" -> {
            val numbered = a["marker"] == "number"
            val gap = gapOf(a["gap"]) ?: 8.dp
            Column((if (inRow) modifier else modifier.fillMaxWidth()), verticalArrangement = Arrangement.spacedBy(gap)) {
                var order = 0
                el.children.forEach { child ->
                    if (child is UiNode.El && child.tag.equals("list-item", true)) {
                        order++
                        ListItem(child, if (numbered) order else null, groupValue)
                    } else {
                        UiNodeView(child, groupValue = groupValue)
                    }
                }
            }
        }

        "list-item" -> ListItem(el, null, groupValue)

        "radio-group" -> {
            val selected = a["value"] ?: ""
            val gap = gapOf(a["gap"]) ?: 4.dp
            // 导出包里 39 个 radio-group 有 36 个显式 direction="col"，
            // 没写的默认也应该竖排；只有明确写了 row 才横排。
            if (a["direction"] == "row") {
                Row((if (inRow) modifier else modifier.fillMaxWidth()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    el.children.forEach { UiNodeView(it, inRow = true, groupValue = selected) }
                }
            } else {
                Column(
                    (if (inRow) modifier else modifier.fillMaxWidth()),
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {
                    el.children.forEach { UiNodeView(it, groupValue = selected) }
                }
            }
        }

        "radio" -> {
            val on = a["value"] != null && a["value"] == groupValue
            Row(
                (if (inRow) modifier else modifier.fillMaxWidth()).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = on, onClick = null)
                RichText(
                    plain(el),
                    MaterialTheme.typography.bodyMedium,
                    if (inRow) Modifier.padding(start = 6.dp)
                    else Modifier.padding(start = 6.dp).weight(1f)
                )
            }
        }

        "checkbox" -> {
            val on = a["checked"] == "true"
            Row(
                (if (inRow) modifier else modifier.fillMaxWidth()).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = on, onCheckedChange = null)
                RichText(
                    plain(el),
                    MaterialTheme.typography.bodyMedium,
                    if (inRow) Modifier.padding(start = 6.dp)
                    else Modifier.padding(start = 6.dp).weight(1f)
                )
            }
        }

        "button" -> {
            val label = plain(el).ifBlank { a["label"] ?: "" }
            if (label.isNotBlank()) {
                Surface(
                    modifier = modifier.then(if (a.containsKey("block")) Modifier.fillMaxWidth() else Modifier),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Text(
                        label,
                        Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        "input", "textarea" -> {
            val value = a["value"] ?: a["placeholder"] ?: ""
            Box(
                modifier
                    .then(if (inRow) Modifier else Modifier.fillMaxWidth())
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        "label" -> Text(
            plain(el),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )

        "slider" -> {
            val percent = a["value"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f
            Box(
                modifier
                    .then(if (inRow) Modifier else Modifier.fillMaxWidth())
                    .height(22.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                )
                Box(
                    Modifier
                        .fillMaxWidth(percent)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                )
            }
        }

        "segmented-control" -> {
            val selected = a["value"] ?: ""
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
                el.children.forEach { child ->
                    val value = (child as? UiNode.El)?.attrs?.get("value") ?: plainOf(listOf(child))
                    val on = value == selected
                    Box(
                        Modifier
                            .background(
                                if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                0.5.dp,
                                if (on) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            plainOf(listOf(child)),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (on) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        "table" -> {
            val rows = el.children.filterIsInstance<UiNode.El>()
                .filter { it.tag.equals("table-row", true) || it.tag.equals("tr", true) }
            if (rows.isEmpty()) {
                Column((if (inRow) modifier else modifier.fillMaxWidth())) { el.children.forEach { UiNodeView(it, groupValue = groupValue) } }
            } else {
                Column(
                    modifier
                        .then(if (inRow) Modifier else Modifier.fillMaxWidth())
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    rows.forEachIndexed { idx, row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            row.children.filterIsInstance<UiNode.El>().forEach { cell ->
                                Box(Modifier.weight(1f).padding(end = 8.dp)) {
                                    UiNodeView(cell, groupValue = groupValue)
                                }
                            }
                        }
                        if (idx < rows.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }

        "table-row", "tr" -> Row((if (inRow) modifier else modifier.fillMaxWidth())) {
            el.children.forEach { cell -> UiNodeView(cell, Modifier.weight(1f), inRow = true, groupValue = groupValue) }
        }

        "table-cell", "td" -> RichText(
            plain(el),
            MaterialTheme.typography.bodySmall,
            modifier,
            MaterialTheme.colorScheme.onSurface
        )

        "asyncimage" -> ImagePlaceholder(
            query = a["query"],
            ratio = ratioOf(a["aspectRatio"]),
            modifier = modifier,
            // 导出包里 80 张图：46 张 maxWidth（缩略图）、22 张 width="100%"、12 张没写
            fixedWidth = pxOf(a["width"]) ?: pxOf(a["maxWidth"]) ?: pxOf(a["maxwidth"]),
            fixedHeight = pxOf(a["height"]),
            inRow = inRow
        )

        "asyncimagegroup", "asyncvideocarousel" -> {
            Row((if (inRow) modifier else modifier.fillMaxWidth()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) {
                    ImagePlaceholder(
                        query = a["query"],
                        ratio = 1f,
                        modifier = Modifier.weight(1f),
                        compact = true
                    )
                }
            }
        }

        "chart" -> ChartPlaceholder(modifier)

        "pressable" -> Column(
            modifier
                .then(if (inRow) Modifier else Modifier.fillMaxWidth())
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            el.children.forEach { UiNodeView(it, groupValue = groupValue) }
        }

        "spacer" -> Spacer(Modifier.height(gapOf(a["size"]) ?: 8.dp))

        "entity" -> Text(
            plain(el),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier
        )

        "cite" -> Unit

        "link" -> Text(
            a["title"] ?: plain(el),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            textDecoration = TextDecoration.Underline,
            modifier = modifier
        )

        else -> el.children.forEach { UiNodeView(it, groupValue = groupValue) }
    }
}

/** 容器里的文字：含表格/列表时按 Markdown 块渲染，普通文字仍走轻量行内样式 */
@Composable
private fun UiTextBody(text: String, modifier: Modifier = Modifier) {
    val raw = text.trim()
    if (raw.isBlank()) return
    val blocks = remember(raw) {
        val blocky = raw.contains('|') || raw.contains("\n- ") || raw.contains("\n* ") ||
            raw.contains("\n#") || raw.contains("```")
        if (blocky) parseMarkdown(raw) else null
    }
    if (blocks == null || (blocks.size == 1 && blocks[0] is MdBlock.Paragraph)) {
        RichText(raw, MaterialTheme.typography.bodyMedium, modifier)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Table -> TableBlock(block.rows, emptyList(), Color.Transparent)
                is MdBlock.Paragraph -> RichText(block.text, MaterialTheme.typography.bodyMedium)
                is MdBlock.Heading -> RichText(
                    block.text,
                    MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )

                is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    block.items.forEachIndexed { idx, item ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                if (block.ordered) "${idx + 1}." else "·",
                                Modifier.width(if (block.ordered) 20.dp else 14.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            RichText(item, MaterialTheme.typography.bodyMedium, Modifier.weight(1f))
                        }
                    }
                }

                is MdBlock.Quote -> RichText(
                    block.text,
                    MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                is MdBlock.Code -> Text(
                    block.code,
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurface
                )

                is MdBlock.Divider -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                is MdBlock.Ui -> UiTemplateView(block.nodes)
            }
        }
    }
}

@Composable
private fun ListItem(el: UiNode.El, order: Int?, groupValue: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (order != null) {
            Text(
                "$order.",
                Modifier.width(22.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                "·",
                Modifier.width(14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            el.children.forEach { UiNodeView(it, groupValue = groupValue) }
        }
    }
}

@Composable
private fun ImagePlaceholder(
    query: String?,
    ratio: Float,
    modifier: Modifier = Modifier,
    fixedWidth: Dp? = null,
    fixedHeight: Dp? = null,
    inRow: Boolean = false,
    compact: Boolean = false
) {
    // 带 maxWidth/width 的是缩略图，按比例给固定尺寸；否则铺满可用宽度
    val width = fixedWidth ?: if (inRow) 88.dp else null
    val height = fixedHeight ?: when {
        width != null -> (width.value / ratio).coerceIn(36f, 280f).dp
        compact -> 74.dp
        ratio >= 1.5f -> 170.dp
        ratio >= 1.1f -> 190.dp
        else -> 210.dp
    }
    val tiny = compact || (width != null && width < 120.dp)
    Column(
        modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(height)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                RoundedCornerShape(10.dp)
            )
            .border(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(10.dp)
            ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.Image,
            contentDescription = query,
            modifier = Modifier.size(if (tiny) 18.dp else 26.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!tiny) {
            Text(
                stringResource(R.string.md_image),
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChartPlaceholder(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                RoundedCornerShape(10.dp)
            )
            .border(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(10.dp)
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.BarChart,
            null,
            Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.ui_chart),
            Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RichText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified
) {
    if (text.isBlank()) return
    val codeColor = MaterialTheme.colorScheme.primary
    val linkColor = MaterialTheme.colorScheme.secondary
    Text(
        text = inlineAnnotated(text.trim(), codeColor, linkColor),
        modifier = modifier,
        style = style,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    )
}

private fun plain(el: UiNode.El): String = UiTemplate.serialize(el.children).trim()

private fun plainOf(nodes: List<UiNode>): String = UiTemplate.serialize(nodes).trim()

// ---------------------------------------------------------------- 属性解析

private fun gapOf(raw: String?): Dp? = raw?.toFloatOrNull()?.let { (it * 4f).dp }

private fun paddingOf(raw: String?): Dp {
    val v = raw?.toFloatOrNull() ?: 0f
    return (v * 4f).dp
}

private fun pxOf(raw: String?): Dp? {
    val v = raw?.trim()?.removeSuffix("px")?.toFloatOrNull() ?: return null
    return v.dp
}

private fun radiusOf(raw: String?): Dp = when (raw) {
    "sm" -> 6.dp
    "md" -> 8.dp
    "lg" -> 12.dp
    "xl" -> 16.dp
    "full" -> 24.dp
    else -> 10.dp
}

private fun sizeOf(raw: String?, default: TextUnit): TextUnit = when (raw) {
    "xs" -> 11.sp
    "sm" -> 13.sp
    "md" -> 15.sp
    "lg" -> 17.sp
    "xl" -> 20.sp
    "2xl" -> 24.sp
    else -> default
}

/** 颜色 token：success / warning / danger / info / secondary / subtle，或 rgba() / #hex */
private fun colorOf(raw: String?): Color? {
    val v = raw?.trim() ?: return null
    if (v.isEmpty()) return null
    return when (v) {
        "success" -> Palette.teal
        "warning" -> Palette.amber
        "danger", "error" -> Palette.coral
        "info" -> Palette.blue
        "primary" -> Color(0xFF4C8DFF)
        "secondary", "muted" -> Color(0xFF8A8A85)
        "subtle" -> Color(0xFF6E6E73)
        else -> parseColor(v)
    }
}

private fun parseColor(raw: String): Color? {
    val v = raw.trim()
    if (v.startsWith("#")) {
        val hex = v.removePrefix("#")
        return runCatching {
            when (hex.length) {
                6 -> Color(0xFF000000 or hex.toLong(16))
                8 -> Color(hex.toLong(16))
                else -> null
            }
        }.getOrNull()
    }
    val m = Regex("""rgba?\(([^)]+)\)""").find(v) ?: return null
    val parts = m.groupValues[1].split(",").map { it.trim() }
    if (parts.size < 3) return null
    val r = parts[0].toIntOrNull() ?: return null
    val g = parts[1].toIntOrNull() ?: return null
    val b = parts[2].toIntOrNull() ?: return null
    val a = parts.getOrNull(3)?.toFloatOrNull() ?: 1f
    return Color(r, g, b, (a * 255).toInt().coerceIn(0, 255))
}

private fun ratioOf(raw: String?): Float {
    val parts = raw?.split(":") ?: return 16f / 9f
    if (parts.size != 2) return 16f / 9f
    val w = parts[0].toFloatOrNull() ?: return 16f / 9f
    val h = parts[1].toFloatOrNull() ?: return 16f / 9f
    if (h <= 0f) return 16f / 9f
    return (w / h).coerceIn(0.6f, 2.4f)
}

private fun iconFor(name: String?): ImageVector? = when (name?.lowercase()) {
    "check", "check-circle", "success", "done" -> Icons.Outlined.CheckCircle
    "info" -> Icons.Outlined.Info
    "warning", "alert" -> Icons.Outlined.Warning
    "close", "x", "cancel" -> Icons.Outlined.Close
    "star" -> Icons.Outlined.StarBorder
    "bulb", "idea", "lightbulb" -> Icons.Outlined.Lightbulb
    "arrow-right", "arrow_forward" -> Icons.Outlined.ArrowForward
    "search" -> Icons.Outlined.Search
    "settings", "gear" -> Icons.Outlined.Settings
    "person", "user" -> Icons.Outlined.Person
    "book", "menu-book" -> Icons.Outlined.MenuBook
    "chart", "bar-chart" -> Icons.Outlined.BarChart
    "phone", "smartphone" -> Icons.Outlined.Smartphone
    "lock", "shield" -> Icons.Outlined.Lock
    "play" -> Icons.Outlined.PlayArrow
    else -> null
}
