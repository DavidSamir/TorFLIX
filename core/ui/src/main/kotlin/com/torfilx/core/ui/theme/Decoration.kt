package com.torfilx.core.ui.theme

import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/*
 * The drawn parts of the look: hairline rules, and what focus draws — a lift, a frame that stands off
 * the edge, an underline, a rule down a row's edge.
 *
 * None of these lays anything out, so none adds a node or moves a neighbour. The focus ones take the
 * State from [animateFocus] and read it inside their draw lambda: every frame of a focus animation
 * redraws one layer, and nothing is recomposed or measured again.
 */

/** A hairline rule across the top edge. */
fun Modifier.ruleAbove(thickness: Dp, color: Color = TorfilxColors.Rule): Modifier = drawBehind {
    drawRect(color, size = Size(size.width, thickness.toPx().coerceAtLeast(1f)))
}

/** A hairline rule across the bottom edge. */
fun Modifier.ruleBelow(thickness: Dp, color: Color = TorfilxColors.Rule): Modifier = drawBehind {
    val height = thickness.toPx().coerceAtLeast(1f)
    drawRect(color, topLeft = Offset(0f, size.height - height), size = Size(size.width, height))
}

/** Raises the element by up to [lift] as [focus] goes to 1. */
fun Modifier.focusLift(focus: State<Float>, lift: Dp): Modifier = graphicsLayer {
    translationY = -lift.toPx() * focus.value
}

/** Moves the element right by up to [distance] as [focus] goes to 1: the arrow after a focused link. */
fun Modifier.focusNudge(focus: State<Float>, distance: Dp): Modifier = graphicsLayer {
    translationX = distance.toPx() * focus.value
}

/**
 * A frame [width] thick standing [gap] outside the element's edge, fading in with [focus].
 *
 * It is drawn outside the element's bounds, so nothing around it may clip within [gap] + [width].
 */
fun Modifier.focusFrame(
    focus: State<Float>,
    width: Dp,
    gap: Dp,
    color: Color = TorfilxColors.FocusFrame,
): Modifier = drawWithCache {
    val strokeWidth = width.toPx().coerceAtLeast(1f)
    val inset = gap.toPx() + strokeWidth / 2
    val topLeft = Offset(-inset, -inset)
    val frameSize = Size(size.width + inset * 2, size.height + inset * 2)
    val stroke = Stroke(strokeWidth)
    onDrawBehind {
        val alpha = focus.value
        if (alpha > 0f) drawRect(color, topLeft, frameSize, alpha = alpha, style = stroke)
    }
}

/** An underline along the bottom edge that draws across from the left as [focus] goes to 1. */
fun Modifier.focusUnderline(focus: State<Float>, thickness: Dp, color: Color = TorfilxColors.Focus): Modifier =
    drawBehind {
        val progress = focus.value
        if (progress > 0f) {
            val height = thickness.toPx().coerceAtLeast(1f)
            drawRect(color, topLeft = Offset(0f, size.height - height), size = Size(size.width * progress, height))
        }
    }

/**
 * A focused list row: a faint wash fades in behind it, and a rule [thickness] wide grows down its left
 * edge from the middle.
 */
fun Modifier.focusRowMark(focus: State<Float>, thickness: Dp): Modifier = drawBehind {
    val progress = focus.value
    if (progress > 0f) {
        drawRect(TorfilxColors.FocusWash, alpha = progress)
        val edge = size.height * progress
        drawRect(
            TorfilxColors.Focus,
            topLeft = Offset(0f, (size.height - edge) / 2),
            size = Size(thickness.toPx().coerceAtLeast(1f), edge),
        )
    }
}
