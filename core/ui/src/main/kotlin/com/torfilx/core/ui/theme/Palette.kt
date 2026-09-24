package com.torfilx.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The raw inks of the editorial look: warm ivory on warm black, like a printed film magazine.
 *
 * Nothing outside the theme uses these. Screens use the roles in [TorfilxColors], so re-inking the app
 * is a change here, and a role that needs a different ink is a change there.
 *
 * Pure white and pure black are absent from the page on purpose: white blooms on a TV at full
 * backlight, and the warm near-black keeps the page from looking like a switched-off screen. Every
 * text ink clears 4.5:1 on [Ink].
 */
internal object Palette {
    // The page, and the panels laid on it.
    val Ink = Color(0xFF0F0E0C)
    val InkRaised = Color(0xFF161411)
    val InkHigh = Color(0xFF1F1C18)
    val InkHighest = Color(0xFF2B2722)

    // Type. Headlines are ivory; reading text and captions a step softer; labels and metadata dim.
    val Ivory = Color(0xFFEFE8DB)
    val IvorySoft = Color(0xFFCFC7B9)
    val Dim = Color(0xFF9F978A)
    val Faint = Color(0xFF857E73)

    // Ivory at low strength: hairline rules, the focus frame, a focused row's wash, idle numerals.
    val IvoryRule = Color(0x29EFE8DB) // 16 %
    val IvoryFrame = Color(0xB3EFE8DB) // 70 %
    val IvoryWash = Color(0x0FEFE8DB) // 6 %
    val IvoryNumeral = Color(0x4DEFE8DB) // 30 %
    val IvoryTrack = Color(0x40EFE8DB) // 25 %

    val InkScrim = Color(0xE60F0E0C) // 90 %
    val InkVeil = Color(0x990F0E0C) // 60 %

    // Status, muted to sit with the ivory rather than shout over it.
    val Sage = Color(0xFF9DB58A)
    val Ochre = Color(0xFFD9A94E)
    val Terracotta = Color(0xFFE08868)

    val Black = Color(0xFF000000)
    val Clear = Color(0x00000000)
}
