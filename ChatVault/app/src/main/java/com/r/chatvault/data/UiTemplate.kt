package com.r.chatvault.data

/**
 * ChatGPT 生成式 UI 模板（DIL）的极简解析器。
 *
 * 导出包里的助手消息会夹带这样的东西：
 * ```
 * {@body const [goal,setGoal] = DIL.useState("map")}
 * <box border radius="xl" padding={4} gap={4}>
 *   **问题** <badge color="success">推荐</badge>
 *   <radio-group value={goal} onChange={setGoal} direction="col">
 *     <radio value="map">地图测试</radio>
 *   </radio-group>
 *   <divider color="subtle"/>
 * </box>
 * ```
 * 这里只做「读懂并变成节点树」，不执行 JS：
 *  - 属性里的 `{expr}` 只解析简单的标识符/字面量，能取到 useState 的默认值；
 *  - `{#if}` 取第一支、`{#each}` 渲染一次模板；
 *  - 求不出的表达式返回空串，绝不把原文再吐出来。
 *
 * 代码围栏（```）里的内容一律不碰，避免误伤用户自己的 HTML/XML 代码。
 */
sealed interface UiNode {
    data class Text(val text: String) : UiNode
    data class El(val tag: String, val attrs: Map<String, String>, val children: List<UiNode>) : UiNode
    data class If(val branches: List<UiBranch>) : UiNode
    data class Each(val item: String, val children: List<UiNode>) : UiNode
}

data class UiBranch(val cond: String?, val children: List<UiNode>)

object UiTemplate {

    private const val MARK_START = '\uE010'
    private const val MARK_END = '\uE011'

    /** 会被提升为独立块的标签（出现在行首时） */
    private val BLOCK_TAGS = setOf(
        "box", "row", "grid", "grid-item", "list", "list-item",
        "table", "table-row", "table-cell", "radio-group", "radio",
        "segmented-control", "button", "input", "textarea", "slider", "checkbox",
        "label", "title", "caption", "divider", "badge", "icon", "text",
        "asyncimage", "asyncimagegroup", "asyncvideocarousel", "chart", "pressable"
    )

    /** 行内可能出现的标签，命中才走模板清洗，避免误伤普通文本里的 < > */
    private val INLINE_HINT = Regex(
        "<(box|row|grid|grid-item|list|list-item|table|table-row|table-cell|radio-group|radio|" +
            "segmented-control|button|input|textarea|slider|checkbox|label|title|caption|divider|" +
            "badge|icon|text|pressable|Chart|Entity|Cite|Link|AsyncImage\\w*)\\b"
    )

    data class Extracted(
        val text: String,
        val blocks: List<List<UiNode>>,
        val vars: Map<String, String>
    )

    // ---------------------------------------------------------------- 入口

    fun extract(src: String): Extracted {
        val vars = collectVars(src)
        val cleaned = removeBodyDecls(src)
        val blocks = ArrayList<List<UiNode>>()
        val out = StringBuilder()

        var fence = false
        var buf = StringBuilder()

        fun flushNormal() {
            if (buf.isEmpty()) return
            out.append(scanChunk(buf.toString(), vars, blocks))
            buf = StringBuilder()
        }

        for (line in cleaned.split("\n")) {
            if (line.trim().startsWith("```")) {
                if (!fence) {
                    flushNormal()
                } else {
                    // 围栏结束：整段原样输出
                    buf.append(line).append('\n')
                    out.append(buf)
                    buf = StringBuilder()
                    fence = false
                    continue
                }
                fence = true
                buf.append(line).append('\n')
                continue
            }
            buf.append(line).append('\n')
        }
        if (fence) out.append(buf) else flushNormal()

        return Extracted(out.toString(), blocks, vars)
    }

    /**
     * 行内清洗：把 Link/Entity/badge 之类换成可读文本，Link 转成 Markdown 链接语法，
     * 让现有渲染器继续给它加下划线。没命中已知标签时原样返回。
     */
    fun inlineText(src: String, vars: Map<String, String>): String {
        if (!INLINE_HINT.containsMatchIn(src)) return src
        return runCatching {
            val p = Parser(src, vars, 0)
            val nodes = p.parseNodes().nodes
            serialize(nodes)
        }.getOrDefault(src)
    }

    /** 节点树 → 纯文本（给 title/text/button 之类只取文字的地方用） */
    fun serialize(nodes: List<UiNode>): String = buildString { nodes.forEach { append(plain(it)) } }

    fun plain(node: UiNode): String = when (node) {
        is UiNode.Text -> node.text
        is UiNode.If -> plainAll(node.branches.firstOrNull()?.children ?: emptyList())
        is UiNode.Each -> plainAll(node.children)
        is UiNode.El -> when (node.tag.lowercase()) {
            "link" -> {
                val title = node.attrs["title"]?.takeIf { it.isNotBlank() }
                    ?: plainAll(node.children)
                "[$title](ref)"
            }

            "entity" -> node.attrs["value"] ?: plainAll(node.children)
            "cite", "icon", "divider", "asyncimage", "asyncimagegroup",
            "asyncvideocarousel", "chart" -> ""
            else -> plainAll(node.children)
        }
    }

    private fun plainAll(nodes: List<UiNode>): String =
        buildString { nodes.forEach { append(plain(it)) } }

    // ---------------------------------------------------------------- 块提取

    private fun scanChunk(
        chunk: String,
        vars: Map<String, String>,
        blocks: MutableList<List<UiNode>>
    ): String {
        val sb = StringBuilder()
        var i = 0
        var lineStart = true
        while (i < chunk.length) {
            if (lineStart) {
                var j = i
                while (j < chunk.length && (chunk[j] == ' ' || chunk[j] == '\t')) j++
                if (j < chunk.length && isBlockStart(chunk, j)) {
                    val parser = Parser(chunk, vars, j)
                    val node = runCatching { parser.parseOne() }.getOrNull()
                    if (node != null && parser.pos > j) {
                        blocks.add(listOf(node))
                        sb.append('\n').append(MARK_START).append(blocks.size - 1).append(MARK_END)
                        sb.append('\n')
                        i = parser.pos
                        // 跳过紧跟的换行，避免多出一个空行
                        if (i < chunk.length && chunk[i] == '\n') {
                            i++
                        }
                        lineStart = true
                        continue
                    }
                }
            }
            val c = chunk[i]
            sb.append(c)
            i++
            lineStart = c == '\n'
        }
        return sb.toString()
    }

    private fun isBlockStart(s: String, at: Int): Boolean {
        if (s[at] == '{') {
            return s.startsWith("{#if", at) || s.startsWith("{#each", at)
        }
        if (s[at] != '<') return false
        var i = at + 1
        if (i >= s.length || !s[i].isLetter()) return false
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '-' || s[i] == '_')) i++
        return s.substring(at + 1, i).lowercase() in BLOCK_TAGS
    }

    // ---------------------------------------------------------------- 状态

    /** 抓 `{@body const [x,setX] = DIL.useState("默认值")}` 里的变量和默认值 */
    fun collectVars(src: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < src.length) {
            val k = src.indexOf("{@body", i)
            if (k < 0) break
            val end = readBracesEnd(src, k) ?: break
            val inner = src.substring(k + 1, end)
            parseBody(inner)?.let { (name, value) -> out[name] = value }
            i = end + 1
        }
        return out
    }

    private fun parseBody(inner: String): Pair<String, String>? {
        val m = Regex(
            """@body\s+const\s*\[\s*([A-Za-z_$][\w$]*)\s*,[^\]]*]\s*=\s*[\w.$]*[Uu]seState\s*\(\s*([\s\S]*?)\s*\)\s*$"""
        ).find(inner) ?: return null
        return m.groupValues[1] to literal(m.groupValues[2])
    }

    private fun removeBodyDecls(src: String): String {
        if (!src.contains("{@body")) return src
        val sb = StringBuilder()
        var i = 0
        while (i < src.length) {
            val k = src.indexOf("{@body", i)
            if (k < 0) {
                sb.append(src, i, src.length)
                break
            }
            sb.append(src, i, k)
            val end = readBracesEnd(src, k)
            if (end == null) {
                sb.append(src, k, src.length)
                break
            }
            i = end + 1
        }
        return sb.toString()
    }

    private fun literal(raw: String): String {
        val t = raw.trim()
        if (t.length >= 2 && (t.first() == '"' || t.first() == '\'') && t.last() == t.first()) {
            return t.substring(1, t.length - 1)
        }
        if (t == "true" || t == "false") return t
        if (t.matches(Regex("-?\\d+(\\.\\d+)?"))) return t
        return t
    }

    // ---------------------------------------------------------------- 词法

    private fun readBracesEnd(s: String, start: Int): Int? {
        var i = start
        var depth = 0
        var quote = '\u0000'
        while (i < s.length) {
            val c = s[i]
            if (quote != '\u0000') {
                if (c == '\\') i++ else if (c == quote) quote = '\u0000'
            } else {
                when (c) {
                    '"', '\'' -> quote = c
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return null
    }

    internal class Parser(
        private val src: String,
        private val vars: Map<String, String>,
        var pos: Int = 0
    ) {

        data class Parsed(val nodes: List<UiNode>, val stop: String?)

        fun parseNodes(untilTag: String? = null): Parsed {
            val nodes = ArrayList<UiNode>()
            while (pos < src.length) {
                if (src.startsWith("</", pos)) {
                    val name = readName(pos + 2)
                    var j = pos + 2 + name.length
                    while (j < src.length && src[j].isWhitespace()) j++
                    if (j < src.length && src[j] == '>') {
                        pos = j + 1
                        if (untilTag != null && name.equals(untilTag, true)) {
                            return Parsed(nodes, null)
                        }
                        continue
                    }
                }
                val c = src[pos]
                if (c == '{') {
                    val end = readBracesEnd(src, pos)
                    if (end == null) {
                        nodes.add(UiNode.Text(src.substring(pos)))
                        pos = src.length
                        break
                    }
                    val inner = src.substring(pos + 1, end).trim()
                    pos = end + 1
                    when {
                        inner.startsWith("@body") -> Unit
                        inner.startsWith("#if") -> nodes.add(parseIf(inner))
                        inner.startsWith("#each") -> nodes.add(parseEach(inner))
                        inner.startsWith("/") || inner.startsWith(":else") -> {
                            return Parsed(nodes, inner)
                        }

                        inner.isEmpty() -> Unit
                        else -> {
                            val v = resolve(inner)
                            if (v.isNotEmpty()) nodes.add(UiNode.Text(v))
                        }
                    }
                    continue
                }
                if (c == '<' && pos + 1 < src.length && src[pos + 1].isLetter()) {
                    val el = parseElement()
                    if (el != null) {
                        nodes.add(el)
                        continue
                    }
                }
                var next = pos + 1
                while (next < src.length && src[next] != '<' && src[next] != '{') next++
                nodes.add(UiNode.Text(src.substring(pos, next)))
                pos = next
            }
            return Parsed(nodes, null)
        }

        /** 从行首解析一个完整块（元素或指令） */
        fun parseOne(): UiNode? {
            if (pos >= src.length) return null
            if (src[pos] == '<') {
                if (pos + 1 >= src.length || !src[pos + 1].isLetter()) return null
                return parseElement()
            }
            if (src[pos] == '{') {
                val end = readBracesEnd(src, pos) ?: return null
                val inner = src.substring(pos + 1, end).trim()
                return when {
                    inner.startsWith("#if") -> {
                        pos = end + 1
                        parseIf(inner)
                    }

                    inner.startsWith("#each") -> {
                        pos = end + 1
                        parseEach(inner)
                    }

                    else -> null
                }
            }
            return null
        }

        private fun parseElement(): UiNode? {
            val start = pos
            pos++ // <
            val name = readName(pos)
            if (name.isEmpty()) {
                pos = start
                return null
            }
            pos += name.length
            val attrs = LinkedHashMap<String, String>()
            var selfClose = false
            while (pos < src.length) {
                while (pos < src.length && src[pos].isWhitespace()) pos++
                if (pos >= src.length) break
                if (src.startsWith("/>", pos)) {
                    pos += 2
                    selfClose = true
                    break
                }
                if (src[pos] == '>') {
                    pos++
                    break
                }
                val attr = readAttrName(pos)
                if (attr.isEmpty()) {
                    pos++
                    continue
                }
                pos += attr.length
                while (pos < src.length && src[pos].isWhitespace()) pos++
                if (pos < src.length && src[pos] == '=') {
                    pos++
                    while (pos < src.length && src[pos].isWhitespace()) pos++
                    attrs[attr] = readAttrValue()
                } else {
                    attrs[attr] = "true"
                }
            }
            if (selfClose) return UiNode.El(name, attrs, emptyList())
            val body = parseNodes(untilTag = name)
            return UiNode.El(name, attrs, body.nodes)
        }

        private fun parseIf(head: String): UiNode {
            val branches = ArrayList<UiBranch>()
            var cond: String? = head.removePrefix("#if").trim()
            while (true) {
                val r = parseNodes()
                branches.add(UiBranch(cond, r.nodes))
                val stop = r.stop ?: break
                when {
                    stop.startsWith(":else if") -> cond = stop.removePrefix(":else if").trim()
                    stop.startsWith(":else") -> {
                        val r2 = parseNodes()
                        branches.add(UiBranch(null, r2.nodes))
                        break
                    }

                    else -> break
                }
            }
            return UiNode.If(branches)
        }

        private fun parseEach(head: String): UiNode {
            val m = Regex(
                """#each\s+[\s\S]+?\s+as\s+([A-Za-z_$][\w$]*)(?:\s*,\s*([A-Za-z_$][\w$]*))?"""
            ).find(head)
            val item = m?.groupValues?.get(1) ?: "item"
            val r = parseNodes()
            return UiNode.Each(item, r.nodes)
        }

        private fun readAttrValue(): String {
            if (pos >= src.length) return ""
            val q = src[pos]
            if (q == '"' || q == '\'') {
                val end = src.indexOf(q, pos + 1)
                if (end < 0) {
                    val v = src.substring(pos + 1)
                    pos = src.length
                    return v
                }
                val v = src.substring(pos + 1, end)
                pos = end + 1
                return v
            }
            if (q == '{') {
                val end = readBracesEnd(src, pos) ?: return ""
                val inner = src.substring(pos + 1, end).trim()
                pos = end + 1
                return resolve(inner)
            }
            val start = pos
            while (pos < src.length && !src[pos].isWhitespace() &&
                src[pos] != '>' && !src.startsWith("/>", pos)
            ) pos++
            return src.substring(start, pos)
        }

        private fun readName(at: Int): String {
            var i = at
            while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '-' || src[i] == '_')) i++
            return src.substring(at, i)
        }

        private fun readAttrName(at: Int): String {
            var i = at
            while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '-' || src[i] == '_' || src[i] == ':')) i++
            return src.substring(at, i)
        }

        private fun resolve(expr: String): String {
            val t = expr.trim()
            if (t.isEmpty()) return ""
            if (t.length >= 2 && (t.first() == '"' || t.first() == '\'') && t.last() == t.first()) {
                return t.substring(1, t.length - 1)
            }
            if (t.matches(Regex("""[A-Za-z_$][\w$]*"""))) {
                return vars[t] ?: ""
            }
            if (t == "true" || t == "false") return t
            if (t.matches(Regex("""-?\d+(\.\d+)?"""))) return t
            return ""
        }
    }
}

/** 判断一行是不是 UI 块的占位标记，返回块下标 */
fun uiMarkerIndex(line: String): Int? {
    if (line.length < 3) return null
    if (line.first() != '\uE010' || line.last() != '\uE011') return null
    return line.substring(1, line.length - 1).toIntOrNull()
}
