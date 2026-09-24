package com.torfilx.core.ui.component

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.torfilx.core.model.MediaCard
import com.torfilx.core.ui.focus.keepNeighbourComposed
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.theme.TorfilxType
import com.torfilx.core.ui.theme.ruleAbove
import com.torfilx.core.ui.util.Format

/**
 * A section of Home: a numbered header over a horizontal row of cards.
 *
 * Three things make it feel right on a remote (plan.md §5.2):
 * - `focusRestorer()` + stable `key`s, so returning from Details lands on the card you left;
 * - `focusGroup()`, so vertical navigation treats the row as one unit;
 * - `contentPadding` equal to the overscan inset, so the first card is not clipped by the TV bezel,
 *   and room above the cards for a focused one to rise into with its frame.
 *
 * [section] numbers the header ("iii."); [ranked] also numbers each poster ("01", "02").
 */
@Composable
fun MediaRow(
    title: String,
    items: List<MediaCard>,
    onCardClick: (MediaCard) -> Unit,
    modifier: Modifier = Modifier,
    onCardLongClick: ((MediaCard) -> Unit)? = null,
    landscape: Boolean = false,
    section: Int? = null,
    ranked: Boolean = false,
    headerTrailing: @Composable (() -> Unit)? = null,
    totalItems: Int = items.size,
    /** Where the rest of a capped row is browsed: "Movies", "Shows", or "Movies and Shows". */
    seeAllIn: String = "Movies",
) {
    if (items.isEmpty()) return
    val dimens = LocalTorfilxDimens.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxWidth()) {
        // A capped row looks identical to a complete one, which is how "Animation" showing a preview
        // of 217 films read as a catalogue containing that handful. When the row is a preview, the
        // header says so and points at where the rest lives.
        RowHeader(
            title = title,
            numeral = section?.let(Format::sectionNumeral),
            subtitle = if (totalItems > items.size) {
                "${items.size} of $totalItems · all in $seeAllIn"
            } else {
                if (totalItems == 1) "1 title" else "$totalItems titles"
            },
            trailing = headerTrailing,
        )
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing),
            contentPadding = PaddingValues(
                start = dimens.overscanHorizontal,
                end = dimens.overscanHorizontal,
                // Room for a focused card to rise, with its frame standing off it.
                top = dimens.focusLift + dimens.focusFrameGap + dimens.focusFrame + 6.dp,
                bottom = 8.dp,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup()
                .focusRestorer(),
        ) {
            items(
                count = items.size,
                key = { index -> items[index].playableId },
                contentType = { if (landscape) "landscape" else "poster" },
            ) { index ->
                val card = items[index]
                // Without this the row stops at the last card the lazy layout happened to compose,
                // which on a 1080p screen is about five of them — a 217-title genre row looked like
                // the handful at its front, with nothing on screen to suggest otherwise.
                val focusNudge = Modifier.keepNeighbourComposed(
                    index = index,
                    itemCount = items.size,
                    state = listState,
                    scope = scope,
                )
                if (landscape) {
                    LandscapeCard(
                        card = card,
                        onClick = { onCardClick(card) },
                        onLongClick = onCardLongClick?.let { handler -> { handler(card) } },
                        modifier = focusNudge,
                    )
                } else {
                    PosterCard(
                        card = card,
                        onClick = { onCardClick(card) },
                        onLongClick = onCardLongClick?.let { handler -> { handler(card) } },
                        numeral = if (ranked) Format.rank(index + 1) else null,
                        modifier = focusNudge,
                    )
                }
            }
        }
    }
}

/**
 * A section header: a hairline rule across the page, then the section's italic numeral, its title,
 * and a spaced count at the far end. [inset] is the page margin; pass 0 inside a column that has one.
 */
@Composable
fun RowHeader(
    title: String,
    modifier: Modifier = Modifier,
    numeral: String? = null,
    subtitle: String? = null,
    inset: Dp = LocalTorfilxDimens.current.overscanHorizontal,
    trailing: @Composable (() -> Unit)? = null,
) {
    val dimens = LocalTorfilxDimens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .ruleAbove(dimens.hairline)
            .padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        numeral?.let {
            Text(text = it, style = TorfilxType.SectionNumeral, color = TorfilxColors.TextTertiary)
        }
        Text(
            text = title,
            style = TorfilxType.SectionTitle,
            color = TorfilxColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // The title gives way to the count, which always sits at the far end of the rule.
            modifier = Modifier.weight(1f),
        )
        subtitle?.let {
            CapsText(text = it, style = TorfilxType.MetaCaps, color = TorfilxColors.TextTertiary)
        }
        trailing?.invoke()
    }
}
