package com.r.chatvault.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * 应用内语言。不依赖 AppCompat：
 *  - Prefs.langTag 为 "" 时跟随系统，否则是 BCP-47 标签（zh / en）
 *  - MainActivity.attachBaseContext 用 wrap() 包一层，改语言后 recreate() 生效
 *  - 非 Compose 代码（ViewModel / 导入器 / 备份）用 str() 取本地化字符串
 *
 * 新增语言：加 res/values-<语言>/strings.xml，并在 Supported 里补一项。
 */
object AppLocale {

    /** 界面里可选的语言（"" 表示跟随系统，单独处理） */
    val Supported = listOf("zh", "en")

    private const val PREFS = "chatvault"
    private const val KEY = "langTag"

    fun savedTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    /** 系统当前语言（用于「跟随系统」以及切换回来时复位） */
    fun systemLocale(): Locale {
        val locales = Resources.getSystem().configuration.locales
        return if (locales.isEmpty) Locale.getDefault() else locales[0]
    }

    private fun targetLocale(context: Context): Locale {
        val tag = savedTag(context)
        return if (tag.isBlank()) systemLocale() else Locale.forLanguageTag(tag)
    }

    /** 用目标语言包装 Context；同时把 Locale.setDefault 设好，供 SimpleDateFormat 使用 */
    fun wrap(base: Context): Context {
        val locale = targetLocale(base)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** 非 Compose 代码取字符串（按应用内语言解析资源） */
    fun str(context: Context, resId: Int): String = wrap(context).getString(resId)

    fun str(context: Context, resId: Int, vararg args: Any): String =
        wrap(context).getString(resId, *args)

    /** 当前生效的 Locale，供日期/数字格式化 */
    fun locale(context: Context): Locale = targetLocale(context)
}
