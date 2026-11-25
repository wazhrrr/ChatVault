package com.r.chatvault.data

/**
 * Markdown 聊天记录解析。
 *
 * 目标不是实现完整 Markdown，而是识别常见的「角色分隔」写法：
 *  - `## 我` / `## ChatGPT`（本项目导出的格式）
 *  - `**You:**` / `**Assistant:**`
 *  - `User:` / `ChatGPT:`
 *  - `### Human` / `### AI`
 * 代码块内部的 `# 注释`、`**粗体**` 不会被误判成角色。
 */
object MarkdownChat {

    data class Msg(val role: String, val text: String, val reasoning: String?)

    data class Parsed(val title: String, val messages: List<Msg>)

    private data class RoleHit(val role: String, val inline: String)

    private val userNames = setOf(
        "我", "用户", "提问", "你", "you", "user", "human", "me"
    )
    private val aiNames = setOf(
        "chatgpt", "gpt", "assistant", "ai", "助手", "回答", "回复", "model", "claude", "机器人", "bot"
    )

    private val detailsRe = Regex(
        "(?is)<details[^>]*>\\s*<summary[^>]*>\\s*(?:思考过程|思维链|thinking|reasoning|thoughts?)\\s*</summary>(.*?)</details>"
    )

    fun parse(raw: String, fallbackTitle: String): Parsed {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split("\n")

        // 标题：第一处一级标题，且不能是角色行
        var title = fallbackTitle
        var titleLine = -1
        for ((i, line) in lines.withIndex()) {
            val t = line.trim()
            if (t.startsWith("# ") && !t.startsWith("## ")) {
                val candidate = t.removePrefix("#").trim()
                if (detectRole(line) == null && candidate.isNotBlank()) {
                    title = candidate
                    titleLine = i
                }
                break
            }
        }

        val messages = ArrayList<Msg>()
        val buf = StringBuilder()
        val preamble = StringBuilder()
        var currentRole: String? = null
        var inFence = false

        fun flush() {
            val role = currentRole ?: return
            val body = buf.toString().trim()
            buf.setLength(0)
            if (body.isBlank()) return
            var main = body
            var reasoning: String? = null
            if (role == "assistant") {
                detailsRe.find(body)?.let { m ->
                    reasoning = m.groupValues[1].trim().ifBlank { null }
                    main = body.removeRange(m.range).trim()
                }
            }
            if (main.isBlank() && reasoning.isNullOrBlank()) return
            messages.add(Msg(role, main, reasoning))
        }

        for ((i, line) in lines.withIndex()) {
            if (i == titleLine) continue
            val trimmed = line.trim()

            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence
                if (currentRole != null) buf.append(line).append('\n')
                else if (!isMetadataLine(trimmed)) preamble.append(line).append('\n')
                continue
            }

            if (!inFence) {
                val hit = detectRole(line)
                if (hit != null) {
                    flush()
                    currentRole = hit.role
                    if (hit.inline.isNotBlank()) buf.append(hit.inline).append('\n')
                    continue
                }
            }

            if (currentRole != null) {
                buf.append(line).append('\n')
            } else if (!isMetadataLine(trimmed)) {
                // 第一个角色之前的内容（比如文档开头的表格）也保留下来
                preamble.append(line).append('\n')
            }
        }
        flush()

        if (messages.isEmpty()) {
            val body = lines.filterIndexed { i, _ -> i != titleLine }.joinToString("\n").trim()
            if (body.isNotBlank()) messages.add(Msg("assistant", body, null))
        } else if (preamble.isNotBlank()) {
            messages.add(0, Msg("assistant", preamble.toString().trim(), null))
        }
        return Parsed(title, messages)
    }

    private fun isMetadataLine(t: String): Boolean {
        if (t.isBlank()) return true
        if (t == "---" || t == "***" || t == "___") return true
        return t.startsWith("> 创建：") || t.startsWith("> 更新：") ||
            t.startsWith("> 更新时间") || t.startsWith("> 标签：") ||
            t.startsWith("> 主题：") || t.startsWith("> 消息") ||
            t.startsWith("**附件**：") || t.startsWith("**标签**：")
    }

    private fun detectRole(line: String): RoleHit? {        val t = line.trim()
        if (t.isEmpty() || t.length > 160) return null

        // 标题：## 我 / ### ChatGPT
        if (t.startsWith("#")) {
            var p = 0
            while (p < t.length && t[p] == '#') p++
            if (p in 1..6 && p < t.length && t[p] == ' ') {
                val role = roleOf(t.substring(p).trim())
                if (role != null) return RoleHit(role, "")
            }
            return null
        }

        // 整行加粗：**You:** / **ChatGPT**
        val bold = Regex("^\\*\\*(.+?)\\*\\*\\s*[:：]?\\s*$").find(t)
        if (bold != null) {
            val inner = bold.groupValues[1].trim()
            val role = roleOf(inner)
            if (role != null) return RoleHit(role, "")
        }

        // 行首角色 + 冒号：You: 你好 / ChatGPT：……
        val plain = Regex("^([^:：]{1,16})[:：]\\s*(.*)$").find(t)
        if (plain != null) {
            val role = roleOf(plain.groupValues[1])
            if (role != null) return RoleHit(role, plain.groupValues[2])
        }

        // 单独一行的角色名
        if (t.length <= 12) {
            val role = roleOf(t)
            if (role != null) return RoleHit(role, "")
        }
        return null
    }

    private fun roleOf(raw: String): String? {
        val n = raw.lowercase()
            .replace("*", "")
            .replace("_", "")
            .replace("：", "")
            .replace(":", "")
            .trim()
            .removeSuffix(" said")
            .removeSuffix("说")
            .trim()
        if (n.isEmpty()) return null
        if (n in userNames) return "user"
        if (n in aiNames) return "assistant"
        return null
    }
}
