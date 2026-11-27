package com.neekhar.pccontrol.core.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat

enum class ThemeMode(val storageValue: String) {
    System("system"),
    Dark("dark"),
    Light("light");

    companion object {
        fun fromStorage(raw: String?): ThemeMode = when (raw?.lowercase()) {
            "dark" -> Dark
            "light" -> Light
            else -> System
        }
    }
}

/**
 * PC Control design tokens ("A · Native", docs/DESIGN.md).
 * True black dark default, clean neutral light mode, mint accent used sparingly.
 * Hard rule: no blue, purple, or indigo; no gradients or glow.
 */
@Immutable
data class PcColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val hairline: Color,
    val meterTrack: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val ok: Color,
    val okSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val bad: Color,
    val badSoft: Color,
    val overlay: Color,
    val media: Color,
    val onMedia: Color,
    val onMediaDim: Color,
    val mediaChip: Color,
    val mediaChipBorder: Color,
    val ripple: Color,
)

val DarkPcColors = PcColors(
    isDark = true,
    bg = Color(0xFF000000),
    surface = Color(0xFF141414),
    surfaceRaised = Color(0xFF1F1F1F),
    hairline = Color(0xFF262626),
    meterTrack = Color(0xFF2C2C2C),
    text = Color(0xFFF5F5F5),
    textDim = Color(0xFFA8A8A8),
    textFaint = Color(0xFF707070),
    accent = Color(0xFF3DDC97),
    accentSoft = Color(0x293DDC97), // rgba(61, 220, 151, 0.16)
    onAccent = Color(0xFF000000),
    ok = Color(0xFF3DDC97),
    okSoft = Color(0x293DDC97),
    warn = Color(0xFFF2B544),
    warnSoft = Color(0x26F2B544), // rgba(242, 181, 68, 0.15)
    bad = Color(0xFFF2685A),
    badSoft = Color(0x26F2685A), // rgba(242, 104, 90, 0.15)
    overlay = Color(0x8C000000),
    media = Color(0xFF000000),
    onMedia = Color(0xFFFFFFFF),
    onMediaDim = Color(0xB8FFFFFF),
    mediaChip = Color(0x8C000000),
    mediaChipBorder = Color(0x2EFFFFFF),
    ripple = Color(0x14FFFFFF),
)

val LightPcColors = PcColors(
    isDark = false,
    bg = Color(0xFFF4F4F4),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFEBEBEB),
    hairline = Color(0xFFE2E2E2),
    meterTrack = Color(0xFFE0E0E0),
    text = Color(0xFF111111),
    textDim = Color(0xFF555555),
    textFaint = Color(0xFF8A8A8A),
    accent = Color(0xFF159A63),
    accentSoft = Color(0x1F159A63), // rgba(21, 154, 99, 0.12)
    onAccent = Color(0xFF000000),
    ok = Color(0xFF159A63),
    okSoft = Color(0x1F159A63),
    warn = Color(0xFFB87800),
    warnSoft = Color(0x24B87800), // rgba(184, 120, 0, 0.14)
    bad = Color(0xFFD2392B),
    badSoft = Color(0x1FD2392B), // rgba(210, 57, 43, 0.12)
    overlay = Color(0x73000000),
    media = Color(0xFF000000),
    onMedia = Color(0xFFFFFFFF),
    onMediaDim = Color(0xB8FFFFFF),
    mediaChip = Color(0x8C000000),
    mediaChipBorder = Color(0x2EFFFFFF),
    ripple = Color(0x14000000),
)

val LocalPcColors = staticCompositionLocalOf { DarkPcColors }

object PcTheme {
    val colors: PcColors
        @Composable
        @ReadOnlyComposable
        get() = LocalPcColors.current

    val type: PcType
        @Composable
        @ReadOnlyComposable
        get() = LocalPcType.current
}

object PcDimens {
    val ScreenPad = 18.dp
    val RadiusSm = 8.dp
    val RadiusMd = 12.dp
    val RadiusLg = 16.dp
    val RadiusXl = 24.dp
    val RadiusPill = 999.dp
    val Space1 = 4.dp
    val Space2 = 8.dp
    val Space3 = 12.dp
    val Space4 = 16.dp
    val Space6 = 24.dp
    val Space8 = 32.dp
}

private fun PcColors.toMaterialColorScheme() = if (isDark) {
    darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = accent,
        secondary = textDim,
        onSecondary = bg,
        secondaryContainer = surfaceRaised,
        onSecondaryContainer = text,
        tertiary = warn,
        onTertiary = bg,
        tertiaryContainer = warnSoft,
        onTertiaryContainer = warn,
        background = bg,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surfaceRaised,
        onSurfaceVariant = textDim,
        surfaceContainerLowest = bg,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceRaised,
        surfaceContainerHighest = surfaceRaised,
        outline = hairline,
        outlineVariant = hairline,
        error = bad,
        onError = bg,
        errorContainer = badSoft,
        onErrorContainer = bad,
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = accent,
        secondary = textDim,
        onSecondary = surface,
        secondaryContainer = surfaceRaised,
        onSecondaryContainer = text,
        tertiary = warn,
        onTertiary = surface,
        tertiaryContainer = warnSoft,
        onTertiaryContainer = warn,
        background = bg,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surfaceRaised,
        onSurfaceVariant = textDim,
        surfaceContainerLowest = surface,
        surfaceContainerLow = bg,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceRaised,
        surfaceContainerHighest = surfaceRaised,
        outline = hairline,
        outlineVariant = hairline,
        error = bad,
        onError = surface,
        errorContainer = badSoft,
        onErrorContainer = bad,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcControlTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val insetsController = WindowInsetsControllerCompat(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }
    val colors = if (isDark) DarkPcColors else LightPcColors
    val type = if (isDark) DarkPcType else LightPcType
    val rippleConfig = RippleConfiguration(
        color = colors.text,
        rippleAlpha = RippleAlpha(
            draggedAlpha = 0.06f,
            focusedAlpha = 0.06f,
            hoveredAlpha = 0.04f,
            pressedAlpha = 0.07f,
        ),
    )

    CompositionLocalProvider(
        LocalPcColors provides colors,
        LocalPcType provides type,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialColorScheme(),
            typography = PcTypography,
        ) {
            CompositionLocalProvider(
                LocalRippleConfiguration provides rippleConfig,
                content = content,
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
