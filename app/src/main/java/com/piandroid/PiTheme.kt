package com.piandroid

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class PiThemeMode(val storageKey: String, val displayName: String, val description: String) {
    Dark("dark", "暗色", "原有纯黑主题"),
    Light("light", "亮色", "明亮背景与深色文字"),
    Gray("gray", "灰色", "低饱和深灰主题");

    companion object {
        fun fromStorage(value: String?): PiThemeMode = entries.firstOrNull { it.storageKey == value } ?: Dark
    }
}

data class PiColors(
    val bg: Color,
    val headerBg: Color,
    val panelBg: Color,
    val cardBg: Color,
    val toolBg: Color,
    val userBg: Color,
    val border: Color,
    val accent: Color,
    val blue: Color,
    val textMain: Color,
    val textMuted: Color,
    val thinkingText: Color,
    val danger: Color,
    val scrollBg: Color,
    val scrollBorder: Color,
    val scrollText: Color,
    val scrollDivider: Color,
    val headerDivider: Color,
    val composerBg: Color,
    val disabledAction: Color,
    val stopButtonBg: Color,
    val markdownText: Color,
    val markdownMuted: Color,
    val markdownAccent: Color,
    val markdownCyan: Color,
    val markdownBorder: Color,
    val markdownCodeBg: Color,
    val markdownStrong: Color,
    val markdownInlineCodeBg: Color,
    val markdownCodeText: Color,
    val markdownQuoteBg: Color
)

// Keep this palette byte-for-byte equivalent to the original dark UI.
val DarkPiColors = PiColors(
    bg = Color(0xFF000000),
    headerBg = Color(0xFF05080A),
    panelBg = Color(0xFF0D1116),
    cardBg = Color(0xFF171B21),
    toolBg = Color(0xFF263229),
    userBg = Color(0xFF30313A),
    border = Color(0xFF284864),
    accent = Color(0xFF70E69A),
    blue = Color(0xFF79C5FF),
    textMain = Color(0xFFE8EAF0),
    textMuted = Color(0xFF858C96),
    thinkingText = Color(0xFF9A9A9A),
    danger = Color(0xFFFF8D8D),
    scrollBg = Color(0xDD41464C),
    scrollBorder = Color(0xFF626970),
    scrollText = Color(0xFFD2D5D8),
    scrollDivider = Color(0xFF686E74),
    headerDivider = Color(0xFF151B21),
    composerBg = Color(0xFF050607),
    disabledAction = Color(0xFF4B535C),
    stopButtonBg = Color(0xFF6B3030),
    markdownText = Color(0xFFD8D6E3),
    markdownMuted = Color(0xFF9491A3),
    markdownAccent = Color(0xFFC5A3FF),
    markdownCyan = Color(0xFF63D1D1),
    markdownBorder = Color(0xFF77738E),
    markdownCodeBg = Color(0xFF171620),
    markdownStrong = Color(0xFFF0EEF7),
    markdownInlineCodeBg = Color(0xFF252432),
    markdownCodeText = Color(0xFFC9E6E2),
    markdownQuoteBg = Color(0xFF1C1B27)
)

val LightPiColors = PiColors(
    bg = Color(0xFFF7F8FA),
    headerBg = Color(0xFFFFFFFF),
    panelBg = Color(0xFFFFFFFF),
    cardBg = Color(0xFFEDF0F3),
    toolBg = Color(0xFFE5F1E8),
    userBg = Color(0xFFE7EDF5),
    border = Color(0xFF8EB4CE),
    accent = Color(0xFF16723A),
    blue = Color(0xFF0068A5),
    textMain = Color(0xFF17191C),
    textMuted = Color(0xFF5E6670),
    thinkingText = Color(0xFF666A70),
    danger = Color(0xFFB4232C),
    scrollBg = Color(0xE6E3E7EB),
    scrollBorder = Color(0xFF9DA5AD),
    scrollText = Color(0xFF30363C),
    scrollDivider = Color(0xFFAAB1B8),
    headerDivider = Color(0xFFD9DEE3),
    composerBg = Color(0xFFF3F5F7),
    disabledAction = Color(0xFFA6ADB5),
    stopButtonBg = Color(0xFFB64040),
    markdownText = Color(0xFF28262F),
    markdownMuted = Color(0xFF666270),
    markdownAccent = Color(0xFF6842A8),
    markdownCyan = Color(0xFF08777A),
    markdownBorder = Color(0xFFB1ADBC),
    markdownCodeBg = Color(0xFFEDEAF1),
    markdownStrong = Color(0xFF111014),
    markdownInlineCodeBg = Color(0xFFE3E0E8),
    markdownCodeText = Color(0xFF175E59),
    markdownQuoteBg = Color(0xFFF0EDF4)
)

val GrayPiColors = PiColors(
    bg = Color(0xFF202124),
    headerBg = Color(0xFF282A2D),
    panelBg = Color(0xFF2D2F33),
    cardBg = Color(0xFF383B40),
    toolBg = Color(0xFF343A37),
    userBg = Color(0xFF3B3D43),
    border = Color(0xFF686D73),
    accent = Color(0xFFB7D8C0),
    blue = Color(0xFFB6C8D8),
    textMain = Color(0xFFF0F1F2),
    textMuted = Color(0xFFA9AEB4),
    thinkingText = Color(0xFFB3B3B3),
    danger = Color(0xFFE5A0A0),
    scrollBg = Color(0xE64B4E53),
    scrollBorder = Color(0xFF777C82),
    scrollText = Color(0xFFE2E4E6),
    scrollDivider = Color(0xFF747980),
    headerDivider = Color(0xFF3A3D41),
    composerBg = Color(0xFF26282B),
    disabledAction = Color(0xFF6F747A),
    stopButtonBg = Color(0xFF704343),
    markdownText = Color(0xFFE5E3E9),
    markdownMuted = Color(0xFFB0ADB5),
    markdownAccent = Color(0xFFD0C3E1),
    markdownCyan = Color(0xFFB7D2D2),
    markdownBorder = Color(0xFF777980),
    markdownCodeBg = Color(0xFF303236),
    markdownStrong = Color(0xFFFFFFFF),
    markdownInlineCodeBg = Color(0xFF44464B),
    markdownCodeText = Color(0xFFD3E4E2),
    markdownQuoteBg = Color(0xFF35363A)
)

fun colorsFor(mode: PiThemeMode): PiColors = when (mode) {
    PiThemeMode.Dark -> DarkPiColors
    PiThemeMode.Light -> LightPiColors
    PiThemeMode.Gray -> GrayPiColors
}

val LocalPiColors = staticCompositionLocalOf { DarkPiColors }
