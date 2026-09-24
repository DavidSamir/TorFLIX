package com.torfilx.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.torfilx.core.model.MediaCard
import com.torfilx.core.ui.focus.bringIntoViewAsWhole
import com.torfilx.core.ui.focus.onMenuKey
import com.torfilx.core.ui.image.Artwork
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.theme.TorfilxShapes
import com.torfilx.core.ui.theme.TorfilxType
import com.torfilx.core.ui.theme.animateFocus
import com.torfilx.core.ui.theme.focusFrame
import com.torfilx.core.ui.theme.focusLift
import com.torfilx.core.ui.util.Format

/** How far a row number hangs off the poster's left edge, and below its foot. */
private val NUMERAL_OVERHANG_X = 14.dp
private val NUMERAL_OVERHANG_Y = 16.dp

/**
 * The poster card used in every row and grid.
 *
 * Focus (plan.md §4, §5): the poster rises and a thin ivory frame appears around it, standing off its
 * edge, and its caption brightens — never colour alone. The accessible description says what the card
 * is and how far it has been watched (VoiceView reads it aloud on Fire TV).
 *
 * [numeral] sets a row number ("01") in large italics across the poster's foot, for ranked rows.
 */
@Composable
fun PosterCard(
    card: MediaCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    numeral: String? = null,
) {
    val dimens = LocalTorfilxDimens.current
    FocusableMediaCard(
        card = card,
        onClick = onClick,
        onLongClick = onLongClick,
        widthDp = dimens.posterWidth,
        heightDp = dimens.posterHeight,
        artworkUrl = card.item.images.poster,
        modifier = modifier,
        interactionSource = interactionSource,
        numeral = numeral,
    )
}

/** 16:9 card used for Continue Watching and episodes. */
@Composable
fun LandscapeCard(
    card: MediaCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val dimens = LocalTorfilxDimens.current
    FocusableMediaCard(
        card = card,
        onClick = onClick,
        onLongClick = onLongClick,
        widthDp = dimens.landscapeWidth,
        heightDp = dimens.landscapeHeight,
        // An episode card shows that episode's still when the catalogue has one, and the show's art
        // otherwise.
        artworkUrl = card.episode?.image
            ?: card.item.images.thumb
            ?: card.item.images.backdrop
            ?: card.item.images.poster,
        modifier = modifier,
        interactionSource = interactionSource,
    )
}

@Composable
private fun FocusableMediaCard(
    card: MediaCard,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    widthDp: Dp,
    heightDp: Dp,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    numeral: String? = null,
) {
    val dimens = LocalTorfilxDimens.current
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val isFocused by source.collectIsFocusedAsState()
    // Drives the lift and the frame from the draw phase: a focus change recomposes this card once, for
    // the caption's colour, and every frame of the movement after that is only a redraw.
    val focus = animateFocus(isFocused, label = "cardFocus")
    val description = remember(card) { card.accessibilityDescription() }

    // Brought into view whole, caption and all: the caption is part of how focus shows.
    Column(modifier = modifier.width(widthDp).bringIntoViewAsWhole()) {
        // The focus target stays where it is laid out; only what it draws rises. Focus search measures
        // from the focused card, and a card lifted in place would make every neighbour count as
        // "below" it, so Down would step sideways along the row instead of to the next one.
        Box(
            modifier = Modifier
                .size(widthDp, heightDp)
                .clickable(
                    interactionSource = source,
                    indication = null,
                    onClick = onClick,
                )
                // The Fire TV remote's Menu key is the TV equivalent of a long press: it opens the
                // contextual actions for the focused card (plan.md §5.1).
                .onMenuKey { onLongClick?.invoke() }
                .semantics { contentDescription = description },
        ) {
            CardFace(
                card = card,
                artworkUrl = artworkUrl,
                widthDp = widthDp,
                heightDp = heightDp,
                focused = isFocused,
                numeral = numeral,
                modifier = Modifier
                    .matchParentSize()
                    .focusLift(focus, dimens.focusLift)
                    .focusFrame(focus, dimens.focusFrame, dimens.focusFrameGap)
                    .background(TorfilxColors.SurfaceLow, TorfilxShapes.Card),
            )
        }

        CardCaption(card = card, focused = isFocused, topGap = dimens.captionGap)
    }
}

/** What a card shows: its artwork, how far it has been watched, and its row number. */
@Composable
private fun CardFace(
    card: MediaCard,
    artworkUrl: String?,
    widthDp: Dp,
    heightDp: Dp,
    focused: Boolean,
    numeral: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Artwork(
            url = artworkUrl,
            title = card.item.title,
            seed = card.item.id,
            widthDp = widthDp,
            heightDp = heightDp,
            modifier = Modifier.fillMaxSize(),
        )

        card.progress?.takeIf { it.fraction > 0f && !it.watched }?.let { progress ->
            CardProgressBar(
                fraction = progress.fraction,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }

        if (card.isWatched) {
            WatchedBadge(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
        }

        numeral?.let {
            Text(
                text = it,
                style = TorfilxType.Numeral,
                color = if (focused) TorfilxColors.TextPrimary else TorfilxColors.Numeral,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = -NUMERAL_OVERHANG_X, y = NUMERAL_OVERHANG_Y),
            )
        }
    }
}

/**
 * The caption under every card: the title in italics, and a spaced line of what it is or what is
 * left. Always there, so a row reads like a contents page; two fixed lines, so every card in a row is
 * the same height and vertical navigation never jumps.
 */
@Composable
private fun CardCaption(card: MediaCard, focused: Boolean, topGap: Dp) {
    Column(Modifier.fillMaxWidth().padding(top = topGap)) {
        Text(
            text = card.item.title,
            style = TorfilxType.CardTitle,
            color = if (focused) TorfilxColors.TextPrimary else TorfilxColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CapsText(
            text = card.subtitle(),
            style = TorfilxType.MetaCaps,
            color = TorfilxColors.TextTertiary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** A thin ivory line along the foot of a card, for how far it has been watched. */
@Composable
fun CardProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(3.dp)
            .background(TorfilxColors.ProgressTrack),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(3.dp)
                .background(TorfilxColors.ProgressFill),
        )
    }
}

@Composable
private fun WatchedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .background(TorfilxColors.ScrimStrong, TorfilxShapes.Control),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "✓",
            style = TorfilxType.Small,
            color = TorfilxColors.TextPrimary,
        )
    }
}

/**
 * The caption's second line: what is left to watch, or the title's year and length.
 *
 * An episode card leads with the episode — `S1 E3 · 12m left` — because the title above it is the
 * show's. A show's poster gives its seasons in place of a runtime, or says it is a series when the
 * catalogue counted no episodes, since a show's poster looks exactly like a film's.
 */
internal fun MediaCard.subtitle(): String {
    val parts = buildList<String> {
        episode?.let { add(it.code) }
        progress?.takeIf { it.fraction > 0f && !it.watched }?.let { p ->
            val remaining = Format.runtime(p.remainingMs)
            if (remaining.isNotEmpty()) add("$remaining left")
        }
        val episode = episode
        when {
            episode != null -> if (size == 1) add(episode.displayName)
            isEmpty() -> {
                item.year?.let { add(it.toString()) }
                val length = if (item.isShow) Format.showLength(item) else Format.runtime(item.runtimeMs)
                when {
                    length.isNotEmpty() -> add(length)
                    item.isShow -> add("Series")
                }
            }
        }
    }
    return parts.joinToString(" · ")
}

/** What VoiceView reads for a card: the title, what kind it is, which episode, and how far along. */
internal fun MediaCard.accessibilityDescription(): String = buildString {
    append(item.title)
    if (item.isShow) append(", series")
    episode?.let { append(", season ${it.season} episode ${it.number}, ${it.displayName}") }
    item.year?.let { append(", $it") }
    val p = progress
    when {
        isWatched || p?.watched == true -> append(", watched")
        p != null && p.fraction > 0f -> append(", ${Format.percentComplete(p.positionMs, p.durationMs)} percent watched")
    }
    if (inMyList) append(", in My List")
}
