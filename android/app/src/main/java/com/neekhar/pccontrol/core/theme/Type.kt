package com.neekhar.pccontrol.core.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.neekhar.pccontrol.R

/**
 * PC Control typography scale plus tabular metric and control styles.
 * System Roboto only, restricted to 400 / 500 / 600 weights (never 700/800), with an
 * `11.sp` minimum font size across every role.
 *
 * Color-bearing roles ([sectionLabel], [caption], [label], [micro], [metricUnit]) default to
 * their semantic theme tones (`textDim` / `textFaint`) and can still be overridden via
 * `Text(color = ...)`.
 */
@Immutable
data class PcType(
    val screenTitle: TextStyle,
    val collapsedTitle: TextStyle,
    val sectionLabel: TextStyle,
    val cardTitle: TextStyle,
    val body: TextStyle,
    val caption: TextStyle,
    val label: TextStyle,
    val chip: TextStyle,
    val micro: TextStyle,
    val metricHero: TextStyle,
    val metricValue: TextStyle,
    val metricUnit: TextStyle,
)

/** Tabular numerals text style helper (`fontFeatureSettings = "tnum"`). */
val TabularNumStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontFeatureSettings = "tnum",
)

/**
 * Bundled JetBrains Mono font family so code blocks and inline backtick code spans always
 * render in true monospace (even on OEM skins like Samsung OneUI that override generic monospace).
 */
val PcMonoFontFamily: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono_semibold, FontWeight.Bold),
)

/** Monospace text style for logs, URLs, and code blocks only. */
val MonoTextStyle = TextStyle(
    fontFamily = PcMonoFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 19.sp,
)

fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

fun pcTypeFor(colors: PcColors): PcType = PcType(
    screenTitle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
    collapsedTitle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp,
    ),
    sectionLabel = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
        color = colors.textDim,
    ),
    cardTitle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.5.sp,
        lineHeight = 21.sp,
    ),
    caption = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        color = colors.textDim,
    ),
    label = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = colors.textDim,
    ),
    chip = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = "tnum",
    ),
    micro = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        color = colors.textFaint,
    ),
    metricHero = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp,
        lineHeight = 50.sp,
        letterSpacing = (-1.0).sp,
        fontFeatureSettings = "tnum",
    ),
    metricValue = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        fontFeatureSettings = "tnum",
    ),
    metricUnit = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = colors.textDim,
        fontFeatureSettings = "tnum",
    ),
)

val DarkPcType: PcType = pcTypeFor(DarkPcColors)
val LightPcType: PcType = pcTypeFor(LightPcColors)

val LocalPcType = staticCompositionLocalOf { DarkPcType }

/**
 * Material 3 [Typography] backed by the [PcType] 7-role scale (with [Color.Unspecified]
 * so Material components inherit [androidx.compose.material3.LocalContentColor]).
 */
val PcTypography: Typography = run {
    val base = DarkPcType
    Typography(
        displayLarge = base.metricHero,
        headlineLarge = base.screenTitle,
        headlineMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            letterSpacing = (-0.3).sp,
        ),
        headlineSmall = base.metricValue,
        titleLarge = base.collapsedTitle,
        titleMedium = base.cardTitle,
        titleSmall = base.cardTitle.copy(
            fontSize = 13.5.sp,
            lineHeight = 18.sp,
        ),
        bodyLarge = base.body,
        bodyMedium = base.body.copy(
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
        ),
        bodySmall = base.caption.copy(color = Color.Unspecified),
        labelLarge = base.cardTitle.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.label.copy(color = Color.Unspecified),
        labelSmall = base.micro.copy(color = Color.Unspecified),
    )
}
