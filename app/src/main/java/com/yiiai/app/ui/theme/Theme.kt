package com.yiiai.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class ThemeMode(val storageKey: String, val label: String) {
    /** 这个模式在当前系统设置下是否解析为深色。 */
    fun resolvesToDark(context: android.content.Context): Boolean = when (this) {
        DARK -> true
        LIGHT -> false
        else -> (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        val LIGHT = ThemeMode("light", "浅色")
        val DARK = ThemeMode("dark", "深色")
        val SYSTEM = ThemeMode("system", "跟随系统")
        val all = listOf(LIGHT, DARK, SYSTEM)

        fun of(key: String?): ThemeMode = all.firstOrNull { it.storageKey == key } ?: LIGHT
    }
}

/** 界面用色。浅色为默认，深色是同一套语义的暗部版本。 */
@Immutable
data class YiiPalette(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val text: Color,
    val textDim: Color,
    val accent: Color,
    val accentSoft: Color,
    val userBubble: Color,
    val aiBubble: Color,
    val danger: Color,
    val positive: Color,
    val orbIdle: Color,
    val orbUser: Color,
    val orbAssistant: Color,
    val orbHalo: Color,
    val divider: Color,
    val isDark: Boolean,
)

val LightPalette = YiiPalette(
    background = Color(0xFFF7F5EC),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFEFEDE1),
    text = Color(0xFF1B2117),
    textDim = Color(0xFF7D8473),
    accent = Color(0xFF4F6B24),
    accentSoft = Color(0xFFE7EFCF),
    userBubble = Color(0xFFDCEAE3),
    aiBubble = Color(0xFFEDEBDD),
    danger = Color(0xFFD94F45),
    positive = Color(0xFF2E9E6B),
    orbIdle = Color(0xFFB9C39A),
    orbUser = Color(0xFF4FB8A8),
    orbAssistant = Color(0xFFD99A3E),
    orbHalo = Color(0xFF9DBE4A),
    divider = Color(0xFFE3E1D3),
    isDark = false,
)

val DarkPalette = YiiPalette(
    background = Color(0xFF0B0F0C),
    surface = Color(0xFF151A14),
    surfaceRaised = Color(0xFF232A21),
    text = Color(0xFFF2EFE4),
    textDim = Color(0xFF99A18E),
    accent = Color(0xFFD9F26A),
    accentSoft = Color(0xFF2A331F),
    userBubble = Color(0xFF233D38),
    aiBubble = Color(0xFF20261D),
    danger = Color(0xFFFF6B5E),
    positive = Color(0xFF6FE0A0),
    orbIdle = Color(0xFF4A5342),
    orbUser = Color(0xFF7FE3D4),
    orbAssistant = Color(0xFFFFC46B),
    orbHalo = Color(0xFFD9F26A),
    divider = Color(0xFF262E23),
    isDark = true,
)

val LocalYiiPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun YiiTheme(mode: ThemeMode = ThemeMode.LIGHT, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    val palette = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) darkColorScheme(
        primary = palette.accent,
        secondary = palette.orbUser,
        background = palette.background,
        onBackground = palette.text,
        surface = palette.surface,
        onSurface = palette.text,
        surfaceVariant = palette.surfaceRaised,
        onSurfaceVariant = palette.textDim,
        error = palette.danger,
        outline = palette.divider,
    ) else lightColorScheme(
        primary = palette.accent,
        secondary = palette.orbUser,
        background = palette.background,
        onBackground = palette.text,
        surface = palette.surface,
        onSurface = palette.text,
        surfaceVariant = palette.surfaceRaised,
        onSurfaceVariant = palette.textDim,
        error = palette.danger,
        outline = palette.divider,
    )
    CompositionLocalProvider(LocalYiiPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
