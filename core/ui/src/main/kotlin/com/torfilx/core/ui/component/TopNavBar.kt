package com.torfilx.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.theme.TorfilxType
import com.torfilx.core.ui.theme.ruleBelow

/** A destination in the top navigation bar. */
data class TopNavItem(
    val id: String,
    val label: String,
)

private val MASTHEAD_HEIGHT = 60.dp

/**
 * The masthead: the italic wordmark, the sections in spaced capitals, and a hairline rule beneath.
 *
 * The current section is set in ivory; the focused one also draws an underline, so the two are never
 * confused. Selection follows OK, not focus: moving across the tabs must not tear down the screen
 * below and destroy the row you were about to come back to (plan.md §5.2 rule 6).
 */
@Composable
fun TopNavBar(
    items: List<TopNavItem>,
    selectedId: String,
    onSelect: (TopNavItem) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val dimens = LocalTorfilxDimens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(MASTHEAD_HEIGHT)
            .background(TorfilxColors.Background)
            .padding(horizontal = dimens.overscanHorizontal)
            .ruleBelow(dimens.hairline)
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Torfilx",
            style = TorfilxType.Masthead,
            color = TorfilxColors.TextPrimary,
            modifier = Modifier.padding(end = 36.dp),
        )
        items.forEach { item ->
            TvChip(
                text = item.label,
                selected = item.id == selectedId,
                onClick = { onSelect(item) },
            )
        }
        Box(Modifier.weight(1f))
        trailing?.invoke()
    }
}
