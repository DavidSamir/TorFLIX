package com.torfilx.core.ui.focus

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps a composed neighbour beyond the focused item so D-pad navigation can never dead-end.
 *
 * This is the single most important thing about lazy layouts on a television. Focus can only move to
 * a node that **exists**, and a lazy layout composes roughly what is on screen. When focus reaches
 * the last composed item there is nothing beyond it to move to, focus search fails, and the list
 * stops dead — with no scrollbar, no bounce and nothing on screen to say the content continues. It
 * is indistinguishable from having reached the end.
 *
 * That is what made a 2000-title catalogue look like about 25 titles in the browse grid, and a
 * 217-title genre row look like the two films at the front of it.
 *
 * Nudging the scroll by one step whenever focus lands on the first or last visible item means the
 * neighbour is always composed before it is needed. The middle of the list is left alone, so this
 * neither fights Compose's own bring-into-view behaviour nor makes scrolling feel jumpy.
 *
 * @param index this item's index in the list.
 * @param itemCount total items, used to clamp the target.
 * @param step how far one D-pad press moves — 1 for a row or column of items.
 */
fun Modifier.keepNeighbourComposed(
    index: Int,
    itemCount: Int,
    state: LazyListState,
    scope: CoroutineScope,
    step: Int = 1,
): Modifier = this.onFocusChanged { focusState ->
    // hasFocus, not isFocused: this sits on the item's container while the focusable node is inside
    // it. isFocused reports only the node the modifier is attached to, so it is always false here
    // and the whole mechanism would silently do nothing.
    if (!focusState.hasFocus) return@onFocusChanged
    scope.launch {
        val visible = state.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: return@launch
        val last = visible.lastOrNull()?.index ?: return@launch
        val target = when {
            index + step > last -> index + step
            index - step < first -> index - step
            else -> return@launch
        }
        runCatching {
            state.animateScrollToItem(target.coerceIn(0, (itemCount - 1).coerceAtLeast(0)))
        }
    }
}
