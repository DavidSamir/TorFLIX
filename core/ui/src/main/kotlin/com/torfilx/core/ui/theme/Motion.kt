package com.torfilx.core.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Timing. Soft and unhurried, like a page settling, but focus itself never waits: what changes on
 * focus (colour, the frame appearing) happens on the key press, and only the movement that follows is
 * eased out over [SETTLE_MS].
 */
object TorfilxMotion {
    /** Short moves that follow focus: a label's arrow, a chip's underline. */
    const val FOCUS_MS = 220

    /** Longer moves that follow focus: a poster lifting, a link's underline drawing across. */
    const val SETTLE_MS = 350

    /** One featured title giving way to the next. */
    const val CROSSFADE_MS = 450

    /** Starts at once and lands softly. */
    val Ease: Easing = CubicBezierEasing(0.3f, 0.7f, 0.1f, 1f)
}

/**
 * True when the viewer turned on "Reduce motion" in Settings.
 *
 * Meant for the slowest sticks, where every animated frame competes with the torrent engine for one
 * weak CPU. With it on: the hero does not auto-advance or crossfade, focus moves land in one frame,
 * and loading placeholders do not pulse. Nothing relies on motion to show where focus is.
 *
 * Static on purpose: it changes only from Settings, and a change should redraw everything.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * How far into its focused look a control is: 0 at rest, 1 focused, eased in between.
 *
 * Read it only inside a draw-phase lambda — `graphicsLayer {}`, `drawBehind {}`, or the modifiers in
 * Decoration.kt, which do exactly that. Then a focus change animates by redrawing one layer, and
 * nothing is recomposed or measured again for any of its frames.
 */
@Composable
fun animateFocus(focused: Boolean, durationMs: Int = TorfilxMotion.SETTLE_MS, label: String = "focus"): State<Float> {
    val reduceMotion = LocalReduceMotion.current
    return animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(durationMs, easing = TorfilxMotion.Ease),
        label = label,
    )
}
