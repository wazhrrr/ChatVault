package com.r.chatvault.util

/** 从 Markdown 正文里抽取围栏代码块，供「代码片段」页使用 */
object CodeFences {

    data class Block(val lang: String, val code: String)

    fun extract(text: String): List<Block> {
        if (!text.contains("```")) return emptyList()
        val lines = text.replace("\r\n", "\n").split("\n")
        val out = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (!trimmed.startsWith("```")) {
                i++
                continue
            }
            val info = trimmed.removePrefix("```").trim()
            val lang = info.substringBefore(' ').substringBefore('\t').lowercase()
            val sb = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                sb.append(lines[i]).append('\n')
                i++
            }
            if (i < lines.size) i++
            val code = sb.toString().trimEnd('\n')
            if (code.isNotBlank()) out.add(Block(lang.ifBlank { "text" }, code))
        }
        return out
    }
}
