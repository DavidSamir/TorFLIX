package com.torfilx.core.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True when the viewer turned on "Reduce motion" in Settings.
 *
 * Meant for the slowest sticks, where every animated frame competes with the torrent engine for one
 * weak CPU. With it on: the hero does not auto-advance or crossfade, focused surfaces do not zoom,
 * and loading placeholders do not pulse. Focus stays visible through the border, which every
 * focusable surface draws anyway, so nothing relies on motion to show where focus is.
 *
 * Static on purpose: it changes only from Settings, and a change should redraw everything.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** The scale a focusable surface draws at: zoomed while focused, unless motion is reduced. */
@Composable
fun animateFocusScale(focused: Boolean, focusedScale: Float = FOCUS_SCALE, label: String): State<Float> {
    val reduceMotion = LocalReduceMotion.current
    return animateFloatAsState(
        targetValue = if (focused && !reduceMotion) focusedScale else 1f,
        animationSpec = if (reduceMotion) snap() else tween(FOCUS_ANIMATION_MS),
        label = label,
    )
}
