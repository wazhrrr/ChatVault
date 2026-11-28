package com.r.chatvault.data

import android.content.Context
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("chatvault", Context.MODE_PRIVATE)

    var themeMode: Int
        get() = sp.getInt("theme", 1)
        set(v) = sp.edit().putInt("theme", v).apply()

    var fontScale: Float
        get() = sp.getFloat("fontScale", 1f)
        set(v) = sp.edit().putFloat("fontScale", v).apply()

    var codeWrap: Boolean
        get() = sp.getBoolean("codeWrap", true)
        set(v) = sp.edit().putBoolean("codeWrap", v).apply()

    var bubbleWidth: Int
        get() = sp.getInt("bubbleWidth", 88)
        set(v) = sp.edit().putInt("bubbleWidth", v).apply()

    /** 应用内语言："" = 跟随系统，其它值为 BCP-47 标签（zh / en） */
    var langTag: String
        get() = sp.getString("langTag", "") ?: ""
        set(v) = sp.edit().putString("langTag", v).apply()
}

object Fmt {
    // 日期用固定数字格式，避免不同语言的月份缩写导致列宽跳动；只有月份标题走资源里的 pattern
    private val dt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
    private val d = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    private val hm = SimpleDateFormat("HH:mm", Locale.ROOT)
    private val ym = SimpleDateFormat("yyyy-MM", Locale.ROOT)

    fun dateTime(sec: Long): String = if (sec <= 0) "—" else dt.format(Date(sec * 1000))
    fun date(sec: Long): String = if (sec <= 0) "—" else d.format(Date(sec * 1000))
    fun time(sec: Long): String = if (sec <= 0) "" else hm.format(Date(sec * 1000))

    /** 代码片段按月分组：2026-10 */
    fun monthKey(sec: Long): String = if (sec <= 0) "0000-00" else ym.format(Date(sec * 1000))

    fun bytes(size: Long): String = when {
        size <= 0 -> "0 B"
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", size / 1024.0)
        else -> String.format(Locale.ROOT, "%.1f MB", size / 1048576.0)
    }

    fun compact(n: Int): String = when {
        n < 1000 -> n.toString()
        n < 10000 -> String.format(Locale.ROOT, "%.1fk", n / 1000.0)
        else -> String.format(Locale.ROOT, "%.0fk", n / 1000.0)
    }

    fun monthShort(key: String): String {
        val parts = key.split("-")
        return if (parts.size == 2) "${parts[0].takeLast(2)}.${parts[1]}" else key
    }
}

object Palette {
    val blue = Color(0xFF4C8DFF)
    val teal = Color(0xFF2DBE8E)
    val amber = Color(0xFFE8A33D)
    val purple = Color(0xFF9B8CF5)
    val coral = Color(0xFFE8735A)
    val gray = Color(0xFF8A8A85)

    fun series(i: Int): Color = listOf(blue, teal, amber, purple, coral, gray)[i % 6]
}
