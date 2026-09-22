package com.torfilx.core.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.torfilx.core.model.MediaCard
import com.torfilx.core.ui.focus.onMenuKey
import com.torfilx.core.ui.image.Artwork
import com.torfilx.core.ui.theme.FOCUS_ANIMATION_MS
import com.torfilx.core.ui.theme.FOCUS_SCALE
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.util.Format

/**
 * The poster card used in every row and grid.
 *
 * Focus behaviour is the whole point (plan.md §4, §5): a scale-up plus a high-contrast border that
 * appears immediately, never colour alone, and an accessible description that says what the card is
 * and how far it has been watched (VoiceView reads this aloud on Fire TV).
 */
@Composable
fun PosterCard(
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
        widthDp = dimens.posterWidth,
        heightDp = dimens.posterHeight,
        artworkUrl = card.item.images.poster,
        modifier = modifier,
        interactionSource = interactionSource,
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
    widthDp: androidx.compose.ui.unit.Dp,
    heightDp: androidx.compose.ui.unit.Dp,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
) {
    val dimens = LocalTorfilxDimens.current
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val isFocused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isFocused) FOCUS_SCALE else 1f,
        animationSpec = tween(FOCUS_ANIMATION_MS),
        label = "cardScale",
    )

    val shape = RoundedCornerShape(dimens.cornerRadius)
    val description = remember(card, isFocused) { card.accessibilityDescription() }

    Column(modifier = modifier.width(widthDp)) {
        Box(
            modifier = Modifier
                .width(widthDp)
                .height(heightDp)
                .scale(scale)
                .clip(shape)
                .background(TorfilxColors.SurfaceLow)
                .border(
                    width = if (isFocused) dimens.focusBorderWidth else 0.dp,
                    color = if (isFocused) TorfilxColors.Focus else TorfilxColors.Transparent,
                    shape = shape,
                )
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

            // A show's poster looks exactly like a film's; the label says which it is.
            if (card.item.isShow && card.episode == null) {
                SeriesBadge(modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
            }
        }

        // The label sits under the card and only renders for the focused item, which keeps the row
        // visually calm and avoids laying out text for off-screen cards.
        if (isFocused) {
            CardLabel(card = card, widthDp = widthDp)
        }
    }
}

@Composable
private fun CardLabel(card: MediaCard, widthDp: androidx.compose.ui.unit.Dp) {
    Column(
        modifier = Modifier
            .width(widthDp)
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = card.item.title,
            style = MaterialTheme.typography.labelLarge,
            color = TorfilxColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val subtitle = card.subtitle()
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = TorfilxColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun CardProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(4.dp)
            .background(
                Brush.verticalGradient(
                    listOf(TorfilxColors.Transparent, TorfilxColors.ScrimSoft),
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .background(TorfilxColors.ProgressFill),
        )
    }
}

@Composable
private fun WatchedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(TorfilxColors.ScrimStrong),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "✓",
            style = MaterialTheme.typography.labelMedium,
            color = TorfilxColors.TextPrimary,
        )
    }
}

/** "SERIES", in the corner of a show's poster. Text rather than an icon, so VoiceView needs nothing extra. */
@Composable
fun SeriesBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(TorfilxColors.ScrimStrong)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "SERIES",
            style = MaterialTheme.typography.labelMedium,
            color = TorfilxColors.TextPrimary,
        )
    }
}

/**
 * Secondary line under a focused card: what is left to watch, or the title's year and length.
 *
 * An episode card leads with the episode — `S1 E3 · 12m left` — because the title above it is the
 * show's. A show's poster gives its seasons in place of a runtime.
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
                length.takeIf { it.isNotEmpty() }?.let { add(it) }
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
