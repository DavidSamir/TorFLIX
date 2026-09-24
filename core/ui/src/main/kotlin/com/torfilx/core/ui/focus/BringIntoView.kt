package com.torfilx.core.ui.focus

import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView as bringNodeIntoView

/**
 * When anything inside this element takes focus, scrolls the whole element into view rather than
 * just the focused control.
 *
 * On a TV, Compose scrolls a focused control to about a third of the way down its list. For a link
 * inside a tall header — the hero, a details spread — that scrolled the top of the header, its kicker
 * and title, off the screen the moment the page opened, and again on every return to it. Asked to show
 * the whole header instead, a list whose first item it is stays at its top while the header has focus.
 *
 * Use it only on something that fits the screen with its focusable controls: a header taller than
 * the screen is shown from its top, so a control below the fold would not be scrolled to.
 */
fun Modifier.bringIntoViewAsWhole(): Modifier = this then BringIntoViewAsWholeElement

/**
 * Scrolling for a page made of sections, such as Home: a section brought into view is set whole near
 * the top of the page, [topMarginPx] below it. The list cannot scroll above its start, so the first
 * section simply stays where it is.
 *
 * Paired with [bringIntoViewAsWhole] on each section, the page moves a section at a time, and the
 * header of the next section always shows beneath the focused one. That matters for more than looks:
 * a lazy list composes only what it shows, and the D-pad can only move to what is composed. Under the
 * TV default — the focused control a third of the way down — a row with captions pushed the next row
 * out of the list entirely, and Down had nowhere to go.
 *
 * Give it to the page's list only: rows inside it should keep the platform's scrolling, so that a
 * focused card settles a little way in from the left edge rather than against it.
 */
class SectionBringIntoViewSpec(private val topMarginPx: Float) : BringIntoViewSpec {
    // Always the same target, wherever the section is now. The scroll animation asks again on every
    // frame, and a rule like "leave it if it is already on screen" turns true halfway there and stops
    // the page short.
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - topMarginPx
}

private data object BringIntoViewAsWholeElement : ModifierNodeElement<BringIntoViewAsWholeNode>() {
    override fun create() = BringIntoViewAsWholeNode()
    override fun update(node: BringIntoViewAsWholeNode) = Unit
    override fun InspectorInfo.inspectableProperties() {
        name = "bringIntoViewAsWhole"
    }
}

private class BringIntoViewAsWholeNode : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        // Whichever child asked, pass the request on for all of this node's bounds.
        bringNodeIntoView()
    }
}
