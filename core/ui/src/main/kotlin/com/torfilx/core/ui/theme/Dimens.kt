package com.torfilx.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Measures for the 960 × 540 dp TV canvas.
 *
 * `overscanHorizontal`/`overscanVertical` are the safe-area insets: older TVs crop the outer ~5% of
 * the picture, so nothing readable or focusable may sit outside them (plan.md §4).
 *
 * A CompositionLocal so that a device class can be given other measures; a static one, because they
 * never change while the app runs and a static read costs nothing to track.
 */
@Immutable
data class TorfilxDimens(
    val overscanHorizontal: Dp = 48.dp,
    val overscanVertical: Dp = 27.dp,

    // Rhythm: the space between rows on a page, and between cards in a row.
    val rowSpacing: Dp = 40.dp,
    val cardSpacing: Dp = 24.dp,

    // Cards. A caption sits under every card, [captionGap] below it.
    val posterWidth: Dp = 150.dp,
    val posterHeight: Dp = 225.dp,
    val landscapeWidth: Dp = 240.dp,
    val landscapeHeight: Dp = 135.dp,
    val captionGap: Dp = 12.dp,

    // Lines: section and table rules, and the underline under a focused label.
    val hairline: Dp = 1.dp,
    val underline: Dp = 2.dp,

    // Focus on a card: it rises by [focusLift], and a frame [focusFrame] thick stands [focusFrameGap] off it.
    val focusLift: Dp = 6.dp,
    val focusFrame: Dp = 1.5.dp,
    val focusFrameGap: Dp = 6.dp,
)

val LocalTorfilxDimens = staticCompositionLocalOf { TorfilxDimens() }
