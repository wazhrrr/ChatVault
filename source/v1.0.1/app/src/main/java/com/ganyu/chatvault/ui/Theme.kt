package com.ganyu.chatvault.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF19B98C),
    onPrimary = Color(0xFF04241C),
    primaryContainer = Color(0xFF133C33),
    onPrimaryContainer = Color(0xFFB8F2DE),
    secondary = Color(0xFF4C8DFF),
    onSecondary = Color(0xFF06142B),
    background = Color(0xFF0E0F11),
    onBackground = Color(0xFFE9E9E6),
    surface = Color(0xFF15171A),
    onSurface = Color(0xFFE9E9E6),
    surfaceVariant = Color(0xFF1C1F24),
    onSurfaceVariant = Color(0xFFA2A5A1),
    outline = Color(0xFF2C3036),
    outlineVariant = Color(0xFF23262B),
    error = Color(0xFFE8735A)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0E8A6C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F3E8),
    onPrimaryContainer = Color(0xFF04352A),
    secondary = Color(0xFF2F6FD0),
    onSecondary = Color.White,
    background = Color(0xFFF6F6F3),
    onBackground = Color(0xFF14161A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14161A),
    surfaceVariant = Color(0xFFEFF0EC),
    onSurfaceVariant = Color(0xFF5B6066),
    outline = Color(0xFFD8D9D4),
    outlineVariant = Color(0xFFE6E7E2),
    error = Color(0xFFC4472F)
)

@Immutable
data class ChatColors(
    val isDark: Boolean,
    val userBubble: Color,
    val userText: Color,
    val assistantBubble: Color,
    val codeBg: Color,
    val codeBorder: Color,
    val highlight: Color,
    val reasoningBg: Color,
    val grid: Color
)

val LocalChatColors = staticCompositionLocalOf {
    ChatColors(
        isDark = true,
        userBubble = Color(0xFF1F3A33),
        userText = Color(0xFFE9E9E6),
        assistantBubble = Color(0xFF17191C),
        codeBg = Color(0xFF101215),
        codeBorder = Color(0xFF2A2E34),
        highlight = Color(0x66E8A33D),
        reasoningBg = Color(0xFF14161A),
        grid = Color(0x14FFFFFF)
    )
}

private val BaseTypography = Typography(
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp)

@Composable
fun ChatVaultTheme(mode: Int, fontScale: Float, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        1 -> true
        2 -> false
        else -> systemDark
    }
    val scheme = if (dark) DarkScheme else LightScheme
    val chatColors = if (dark) {
        ChatColors(
            isDark = true,
            userBubble = Color(0xFF1E3A33),
            userText = Color(0xFFE9E9E6),
            assistantBubble = Color(0xFF17191C),
            codeBg = Color(0xFF0F1114),
            codeBorder = Color(0xFF2A2E34),
            highlight = Color(0x59E8A33D),
            reasoningBg = Color(0xFF131518),
            grid = Color(0x14FFFFFF)
        )
    } else {
        ChatColors(
            isDark = false,
            userBubble = Color(0xFFDCF3EA),
            userText = Color(0xFF10322A),
            assistantBubble = Color(0xFFFFFFFF),
            codeBg = Color(0xFFF3F4F0),
            codeBorder = Color(0xFFDFE0D9),
            highlight = Color(0x66E8A33D),
            reasoningBg = Color(0xFFF6F6F2),
            grid = Color(0x14000000)
        )
    }

    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * fontScale.coerceIn(0.8f, 1.4f)),
        LocalChatColors provides chatColors
    ) {
        MaterialTheme(colorScheme = scheme, typography = BaseTypography, content = content)
    }
}
