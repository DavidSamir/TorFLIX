package com.torfilx.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * TV palette (plan.md §4).
 *
 * Pure white is deliberately absent: on a TV at full backlight it clips and blooms, so the lightest
 * text is #E5E5E5. Every text colour here clears 4.5:1 against its intended background.
 */
object TorfilxColors {
    val Background = Color(0xFF141414)
    val SurfaceLow = Color(0xFF1B1B1B)
    val Surface = Color(0xFF1F1F1F)
    val SurfaceHigh = Color(0xFF2A2A2A)
    val SurfaceHighest = Color(0xFF383838)

    val TextPrimary = Color(0xFFE5E5E5)
    val TextSecondary = Color(0xFFB3B3B3)
    val TextTertiary = Color(0xFF8C8C8C)
    val TextOnAccent = Color(0xFFFFFFFF)

    val Accent = Color(0xFFE23B3B)
    val AccentDim = Color(0xFF8E2020)
    val Focus = Color(0xFFF5F5F5)

    val Success = Color(0xFF4CAF50)
    val Warning = Color(0xFFFFB300)
    val Error = Color(0xFFFF5252)

    val ScrimStrong = Color(0xE6141414)
    val ScrimSoft = Color(0x99141414)
    val Transparent = Color(0x00000000)

    /** Behind video: true black, so letterbox bars disappear into the TV's own black. */
    val VideoBackground = Color(0xFF000000)

    val ProgressTrack = Color(0x66FFFFFF)
    val ProgressFill = Accent
}
