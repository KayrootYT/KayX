package com.kayx.manager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kayx.manager.data.settings.AccentChoice
import com.kayx.manager.data.settings.SettingsStore
import com.kayx.manager.data.settings.ThemeMode

/** Resolved colours for the current theme (Light / Dark / AMOLED). */
@Immutable
class KayXPalette(
    val dark: Boolean,
    val amoled: Boolean,
    val bg: Color,
    val surface: Color,
    val dialog: Color,
    val groupFill: Color,
    val separator: Color,
    val fg: Color,
    val muted: Color,
    val accent: Color,
    val accent2: Color,
    val segTrack: Color,
    val segThumb: Color,
    val switchOff: Color
)

internal fun buildPalette(mode: ThemeMode, systemDark: Boolean, accent: AccentChoice): KayXPalette {
    val resolved = if (mode == ThemeMode.SYSTEM) (if (systemDark) ThemeMode.DARK else ThemeMode.LIGHT) else mode
    return when (resolved) {
        ThemeMode.LIGHT -> KayXPalette(
            dark = false, amoled = false,
            bg = Color(0xFFF2F2F7), surface = Color.White, dialog = Color.White,
            groupFill = Color.White, separator = Color(0x293C3C43),
            fg = Color(0xFF1C1C1E), muted = Color(0xFF6E6E73),
            accent = accent.light, accent2 = Color(0xFF5856D6),
            segTrack = Color(0x1F767680), segThumb = Color.White, switchOff = Color(0xFFE5E5EA)
        )
        ThemeMode.AMOLED -> KayXPalette(
            dark = true, amoled = true,
            bg = Color.Black, surface = Color.Black, dialog = Color(0xFF1C1C1E),
            groupFill = Color(0xFF1C1C1E), separator = Color(0xFF38383A),
            fg = Color.White, muted = Color(0xFF8E8E93),
            accent = accent.dark, accent2 = Color(0xFF9B83FF),
            segTrack = Color(0xFF2C2C2E), segThumb = Color(0xFF636366), switchOff = Color(0xFF39393D)
        )
        else -> KayXPalette(
            dark = true, amoled = false,
            bg = Color(0xFF080D18), surface = Color(0xFF111B2B), dialog = Color(0xFF1A2536),
            groupFill = Color.White.copy(alpha = .08f), separator = Color.White.copy(alpha = .10f),
            fg = Color(0xFFF3F7FF), muted = Color(0xFF9AAAC2),
            accent = accent.dark, accent2 = Color(0xFF9B83FF),
            segTrack = Color.White.copy(alpha = .10f), segThumb = Color(0xFF4A5A78), switchOff = Color(0xFF39455A)
        )
    }
}

val LocalPalette = staticCompositionLocalOf { buildPalette(ThemeMode.DARK, true, AccentChoice.BLUE) }

// Theme-aware colours. Same names as before, so existing imports keep working.
val Accent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
val Accent2: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent2
val Bg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.bg
val GlassSurface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface
val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.fg
val TextMuted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
/** Main foreground colour: white in Dark/AMOLED, near-black in Light. */
val Fg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.fg

/** True when the resolved theme is dark (used for status/navigation bar icon colours). */
@Composable
fun isKayXDark(): Boolean = when (SettingsStore.themeMode) {
    ThemeMode.DARK, ThemeMode.AMOLED -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

private val KayXTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(fontSize = 17.sp)
    )
}

@Composable
fun KayXTheme(content: @Composable () -> Unit) {
    val palette = buildPalette(SettingsStore.themeMode, isSystemInDarkTheme(), SettingsStore.accent)
    val scheme = if (palette.dark) darkColorScheme(
        primary = palette.accent, onPrimary = Color(0xFF06101F), secondary = palette.accent2,
        background = palette.bg, surface = palette.surface,
        onSurface = palette.fg, onBackground = palette.fg,
        surfaceVariant = palette.segTrack, onSurfaceVariant = palette.muted,
        outline = palette.separator,
        surfaceContainer = palette.dialog, surfaceContainerHigh = palette.dialog
    ) else lightColorScheme(
        primary = palette.accent, onPrimary = Color.White, secondary = palette.accent2,
        background = palette.bg, surface = palette.surface,
        onSurface = palette.fg, onBackground = palette.fg,
        surfaceVariant = palette.segTrack, onSurfaceVariant = palette.muted,
        outline = palette.separator,
        surfaceContainer = palette.dialog, surfaceContainerHigh = palette.dialog
    )
    // Slightly longer long-press (default is ~400 ms) so a normal tap/scroll never opens selection by accident.
    val baseVc = LocalViewConfiguration.current
    val vc = remember(baseVc) {
        object : ViewConfiguration by baseVc { override val longPressTimeoutMillis: Long get() = 650L }
    }
    CompositionLocalProvider(LocalPalette provides palette, LocalContentColor provides palette.fg, LocalViewConfiguration provides vc) {
        MaterialTheme(colorScheme = scheme, typography = KayXTypography, content = content)
    }
}
