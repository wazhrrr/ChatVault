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
}

object Fmt {
    private val dt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    private val d = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val hm = SimpleDateFormat("HH:mm", Locale.CHINA)

    fun dateTime(sec: Long): String = if (sec <= 0) "—" else dt.format(Date(sec * 1000))
    fun date(sec: Long): String = if (sec <= 0) "—" else d.format(Date(sec * 1000))
    fun time(sec: Long): String = if (sec <= 0) "" else hm.format(Date(sec * 1000))

    fun ago(sec: Long): String {
        if (sec <= 0) return "—"
        val diff = System.currentTimeMillis() / 1000 - sec
        return when {
            diff < 60 -> "刚刚"
            diff < 3600 -> "${diff / 60} 分钟前"
            diff < 86400 -> "${diff / 3600} 小时前"
            diff < 86400 * 30 -> "${diff / 86400} 天前"
            diff < 86400 * 365 -> "${diff / (86400 * 30)} 个月前"
            else -> "${diff / (86400 * 365)} 年前"
        }
    }

    fun bytes(size: Long): String = when {
        size <= 0 -> "0 B"
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format(Locale.CHINA, "%.1f KB", size / 1024.0)
        else -> String.format(Locale.CHINA, "%.1f MB", size / 1048576.0)
    }

    fun compact(n: Int): String = when {
        n < 1000 -> n.toString()
        n < 10000 -> String.format(Locale.CHINA, "%.1fk", n / 1000.0)
        else -> String.format(Locale.CHINA, "%.0fk", n / 1000.0)
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
