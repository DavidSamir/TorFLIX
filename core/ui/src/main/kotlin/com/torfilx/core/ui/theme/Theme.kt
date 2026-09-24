package com.torfilx.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/*
 * The theme, one concern per file:
 *
 *   Palette.kt     the raw inks (internal)
 *   Color.kt       TorfilxColors: what each colour is for
 *   Type.kt        TorfilxFonts, TorfilxType (the editorial styles) and the Material type slots
 *   Dimens.kt      TorfilxDimens: overscan, rhythm, card sizes, line and focus measures
 *   Shapes.kt      TorfilxShapes: square corners
 *   Motion.kt      TorfilxMotion, reduce motion, and animateFocus
 *   Decoration.kt  rules and focus drawing: lift, frame, underline, row mark
 *
 * Colours, type, shapes and timings are plain objects, read as static fields: the app has one look,
 * so there is nothing for Compose to provide or track. Only the measures and reduce motion, which a
 * device or a setting can change, are CompositionLocals, and static ones.
 */

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
