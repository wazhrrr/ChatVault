package com.r.chatvault.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.r.chatvault.R
import com.r.chatvault.data.CategoryRes
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 数据库里存的是中文值（主题名、"未记录"），展示时按当前语言映射到资源。
 * 不要改库里的值，否则筛选、统计和导入增量判断都会失效。
 */
@Composable
fun categoryLabel(raw: String): String =
    CategoryRes.idOf(raw)?.let { stringResource(it) } ?: raw

@Composable
fun modelLabel(raw: String): String =
    if (raw == "未记录") stringResource(R.string.common_not_recorded) else raw

/** 代码片段按月分组标题：中文「2026年10月」/ 英文「Oct 2026」，格式来自资源里的 pattern */
@Composable
fun monthLabel(sec: Long): String {
    if (sec <= 0) return stringResource(R.string.unknown_time)
    val pattern = stringResource(R.string.month_label_pattern)
    val locale = LocalConfiguration.current.locales[0]
    return remember(sec, pattern, locale) {
        runCatching { SimpleDateFormat(pattern, locale).format(Date(sec * 1000)) }
            .getOrDefault(pattern)
    }
}
