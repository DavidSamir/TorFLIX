package com.torfilx.core.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.torfilx.core.model.Episode
import com.torfilx.core.model.PlaybackProgress
import com.torfilx.core.model.ResumeRules
import com.torfilx.core.model.Season
import com.torfilx.core.ui.focus.onMenuKey
import com.torfilx.core.ui.image.Artwork
import com.torfilx.core.ui.theme.FOCUS_ANIMATION_MS
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.util.Format

private const val EPISODE_FOCUS_SCALE = 1.02f
private val EPISODE_THUMB_WIDTH = 176.dp
private val EPISODE_THUMB_HEIGHT = 99.dp

/**
 * The season selector on a show's details screen: one chip per season, Specials last.
 *
 * Keyed by season number, which the parser guarantees unique. `focusRestorer` brings focus back to
 * the chip that was selected when the viewer returns to the row. The Menu key on a chip offers the
 * season's own actions (mark the whole season watched).
 */
@Composable
fun SeasonChips(
    seasons: List<Season>,
    selected: Int?,
    onSelect: (Season) -> Unit,
    onMenu: (Season) -> Unit,
    modifier: Modifier = Modifier,
    /** Lets the screen put focus back on a chip, after the season's menu closes. */
    focusRequesterFor: ((Season) -> FocusRequester)? = null,
) {
    val dimens = LocalTorfilxDimens.current
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .focusGroup()
            .focusRestorer(),
        contentPadding = PaddingValues(horizontal = dimens.overscanHorizontal),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(count = seasons.size, key = { seasons[it].number }) { index ->
            val season = seasons[index]
            TvChip(
                text = season.name,
                selected = season.number == selected,
                onClick = { onSelect(season) },
                modifier = Modifier
                    .then(focusRequesterFor?.let { Modifier.focusRequester(it(season)) } ?: Modifier)
                    .onMenuKey { onMenu(season) },
            )
        }
    }
}

/**
 * One episode in a show's list: a still, its number and name, how long it runs, two lines of
 * overview, and how far the viewer got.
 *
 * An episode the catalogue offers no source for is still listed — the numbering must stay honest —
 * but greyed and labelled, and the screen decides what OK does with it.
 */
@Composable
fun EpisodeRow(
    episode: Episode,
    progress: PlaybackProgress?,
    fallbackImage: String?,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val dimens = LocalTorfilxDimens.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) EPISODE_FOCUS_SCALE else 1f,
        animationSpec = tween(FOCUS_ANIMATION_MS),
        label = "episodeScale",
    )
    val watched = progress?.let(ResumeRules::isWatched) == true
    val shape = RoundedCornerShape(dimens.cornerRadius)
    val description = remember(episode, progress) { episode.accessibilityDescription(progress) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.overscanHorizontal)
            .scale(scale)
            .clip(shape)
            .background(if (focused) TorfilxColors.SurfaceHigh else TorfilxColors.Transparent)
            .border(
                width = if (focused) dimens.focusBorderWidth else 0.dp,
                color = if (focused) TorfilxColors.Focus else TorfilxColors.Transparent,
                shape = shape,
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .onMenuKey(onMenu)
            .semantics { contentDescription = description }
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(EPISODE_THUMB_WIDTH)
                .height(EPISODE_THUMB_HEIGHT)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            Artwork(
                url = episode.image ?: fallbackImage,
                title = episode.displayName,
                seed = episode.id,
                widthDp = EPISODE_THUMB_WIDTH,
                heightDp = EPISODE_THUMB_HEIGHT,
                modifier = Modifier.fillMaxSize(),
            )
            progress?.takeIf { it.fraction > 0f && !watched }?.let {
                CardProgressBar(fraction = it.fraction, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
            if (watched) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(TorfilxColors.ScrimStrong)
                        .padding(horizontal = 6.dp),
                ) {
                    Text(text = "✓", style = MaterialTheme.typography.labelMedium, color = TorfilxColors.TextPrimary)
                }
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${episode.number}  ${episode.displayName}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (episode.isPlayable) TorfilxColors.TextPrimary else TorfilxColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Format.runtime(episode.runtimeMs).takeIf { it.isNotEmpty() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelLarge,
                        color = TorfilxColors.TextSecondary,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            episode.overview?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    color = TorfilxColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!episode.isPlayable) {
                Text(
                    text = "No source for this episode",
                    style = MaterialTheme.typography.labelMedium,
                    color = TorfilxColors.Warning,
                )
            } else {
                progress?.takeIf { it.fraction > 0f && !watched }?.let {
                    Text(
                        text = "${Format.runtime(it.remainingMs)} left",
                        style = MaterialTheme.typography.labelMedium,
                        color = TorfilxColors.TextSecondary,
                    )
                }
            }
        }
    }
}

/** What VoiceView reads for an episode row. */
internal fun Episode.accessibilityDescription(progress: PlaybackProgress?): String = buildString {
    append("Episode $number")
    name?.takeIf { it.isNotBlank() }?.let { append(", $it") }
    Format.runtime(runtimeMs).takeIf { it.isNotEmpty() }?.let { append(", $it") }
    when {
        !isPlayable -> append(", not available")
        progress != null && ResumeRules.isWatched(progress) -> append(", watched")
        progress != null && progress.fraction > 0f ->
            append(", ${Format.percentComplete(progress.positionMs, progress.durationMs)} percent watched")
    }
}

/** One entry in an [ActionMenu]. */
data class MenuAction(val label: String, val onClick: () -> Unit)

/**
 * A small modal list of actions, raised by the remote's Menu key on an episode or a season.
 *
 * The first action takes focus, and focus cannot leave the menu while it is open: a D-pad press past
 * its edge is cancelled rather than landing on a row behind the scrim, which would leave the viewer
 * steering something they cannot see. The screen showing it handles Back (to close it), because only
 * the screen owns a back handler.
 */
@Composable
fun ActionMenu(
    title: String,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TorfilxColors.ScrimStrong),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 520.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(TorfilxColors.Surface)
                .padding(24.dp)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = TorfilxColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            actions.forEachIndexed { index, action ->
                TvButton(
                    text = action.label,
                    onClick = action.onClick,
                    primary = index == 0,
                    autoFocus = index == 0,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TvButton(text = "Cancel", onClick = onDismiss, primary = false, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * The card the player shows when an episode ends: what comes next, and the two choices.
 *
 * Sits in the bottom-right corner over the final frame, like every TV player, so the credits stay
 * visible behind it. The primary button takes focus so OK does the expected thing.
 */
@Composable
fun NextEpisodeCard(
    headline: String,
    title: String?,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .widthIn(max = 480.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(TorfilxColors.ScrimStrong)
            .padding(20.dp)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = headline, style = MaterialTheme.typography.labelLarge, color = TorfilxColors.TextSecondary)
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleLarge,
                color = TorfilxColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = TorfilxColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
            TvButton(text = primaryLabel, onClick = onPrimary, autoFocus = true)
            if (secondaryLabel != null && onSecondary != null) {
                TvButton(text = secondaryLabel, onClick = onSecondary, primary = false)
            }
        }
    }
}
