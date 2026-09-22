package com.torfilx.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/**
 * Type scale for a 10-foot UI (plan.md §4).
 *
 * Nothing is smaller than 14 sp; body copy is 20 sp. These are absolute sizes rather than a scaled
 * phone scale — a TV is read from three metres away, not thirty centimetres.
 */
private val TorfilxTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        lineHeight = 56.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 48.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 30.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 26.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

private val TorfilxColorScheme = darkColorScheme(
    primary = TorfilxColors.Accent,
    onPrimary = TorfilxColors.TextOnAccent,
    secondary = TorfilxColors.SurfaceHigh,
    onSecondary = TorfilxColors.TextPrimary,
    background = TorfilxColors.Background,
    onBackground = TorfilxColors.TextPrimary,
    surface = TorfilxColors.Surface,
    onSurface = TorfilxColors.TextPrimary,
    surfaceVariant = TorfilxColors.SurfaceHigh,
    onSurfaceVariant = TorfilxColors.TextSecondary,
    error = TorfilxColors.Error,
    border = TorfilxColors.Focus,
)

/**
 * Layout constants for the 960 × 540 dp TV canvas.
 *
 * `overscanHorizontal`/`overscanVertical` are the safe-area insets: older TVs crop the outer ~5% of
 * the picture, so nothing readable or focusable may sit outside them (plan.md §4).
 */
data class TorfilxDimens(
    val overscanHorizontal: Dp = 48.dp,
    val overscanVertical: Dp = 27.dp,
    val rowSpacing: Dp = 28.dp,
    val cardSpacing: Dp = 16.dp,
    val posterWidth: Dp = 150.dp,
    val posterHeight: Dp = 225.dp,
    val landscapeWidth: Dp = 240.dp,
    val landscapeHeight: Dp = 135.dp,
    val episodeCardWidth: Dp = 300.dp,
    val focusBorderWidth: Dp = 3.dp,
    val cornerRadius: Dp = 8.dp,
)

val LocalTorfilxDimens = staticCompositionLocalOf { TorfilxDimens() }

/** Focus scale factor shared by every focusable surface, so focus feels uniform. */
const val FOCUS_SCALE = 1.08f

/** Duration of focus/selection animations in milliseconds. */
const val FOCUS_ANIMATION_MS = 160

@Composable
fun TorfilxTheme(
    dimens: TorfilxDimens = TorfilxDimens(),
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    // The app is dark-only by design: a bright UI in a dark room is hostile, and a TV is watched in
    // the dark. The system light/dark setting is intentionally not consulted.
    CompositionLocalProvider(
        LocalTorfilxDimens provides dimens,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = TorfilxColorScheme,
            typography = TorfilxTypography,
            content = content,
        )
    }
}
