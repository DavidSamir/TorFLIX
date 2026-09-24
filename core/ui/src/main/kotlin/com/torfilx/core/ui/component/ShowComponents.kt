package com.torfilx.core.ui.component

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.torfilx.core.model.Episode
import com.torfilx.core.model.PlaybackProgress
import com.torfilx.core.model.ResumeRules
import com.torfilx.core.model.Season
import com.torfilx.core.ui.focus.onMenuKey
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.theme.TorfilxShapes
import com.torfilx.core.ui.theme.TorfilxType
import com.torfilx.core.ui.theme.animateFocus
import com.torfilx.core.ui.theme.focusNudge
import com.torfilx.core.ui.theme.focusRowMark
import com.torfilx.core.ui.theme.ruleAbove
import com.torfilx.core.ui.theme.ruleBelow
import com.torfilx.core.ui.util.Format

private val EPISODE_NUMBER_WIDTH = 44.dp
private val EPISODE_STATUS_WIDTH = 150.dp
private val EPISODE_PROGRESS_WIDTH = 96.dp
private val EPISODE_NAME_NUDGE = 6.dp

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
        // The chips carry their own side padding; this lines their text up with the page edge.
        contentPadding = PaddingValues(horizontal = dimens.overscanHorizontal - 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
 * One episode in a show's list, set as a line in a table of contents: its number in italics, its name,
 * a line of summary, and how long it runs — then how far the viewer got.
 *
 * Focus lays a faint wash behind the row, grows a rule down its left edge, and steps the name forward,
 * all drawn rather than laid out. No still per episode: a list of names reads faster than a wall of
 * thumbnails, and it spares an image decode per row.
 *
 * An episode the catalogue offers no source for is still listed — the numbering must stay honest —
 * but dimmed and labelled, and the screen decides what OK does with it.
 */
@Composable
fun EpisodeRow(
    episode: Episode,
    progress: PlaybackProgress?,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    /** The first row of its list, which also draws the rule above itself. */
    first: Boolean = false,
) {
    val dimens = LocalTorfilxDimens.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus = animateFocus(focused, label = "episodeFocus")
    val watched = progress?.let(ResumeRules::isWatched) == true
    val inProgress = progress?.takeIf { it.fraction > 0f && !watched }
    val description = remember(episode, progress) { episode.accessibilityDescription(progress) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.overscanHorizontal)
            .then(if (first) Modifier.ruleAbove(dimens.hairline) else Modifier)
            .ruleBelow(dimens.hairline)
            .focusRowMark(focus, dimens.underline)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .onMenuKey(onMenu)
            .semantics { contentDescription = description }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = Format.rank(episode.number),
            style = TorfilxType.SectionNumeral,
            color = if (focused) TorfilxColors.TextPrimary else TorfilxColors.TextTertiary,
            modifier = Modifier.width(EPISODE_NUMBER_WIDTH),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .focusNudge(focus, EPISODE_NAME_NUDGE),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = episode.displayName,
                style = TorfilxType.ListTitle,
                color = when {
                    !episode.isPlayable -> TorfilxColors.TextDisabled
                    focused -> TorfilxColors.TextPrimary
                    else -> TorfilxColors.TextSecondary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            episode.overview?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = TorfilxType.Small,
                    color = TorfilxColors.TextTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(
            modifier = Modifier.width(EPISODE_STATUS_WIDTH),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Format.runtime(episode.runtimeMs).takeIf { it.isNotEmpty() }?.let {
                CapsText(text = it, style = TorfilxType.MetaCaps, color = TorfilxColors.TextTertiary)
            }
            when {
                !episode.isPlayable -> CapsText(
                    text = "No source",
                    style = TorfilxType.MetaCaps,
                    color = TorfilxColors.Warning,
                )
                watched -> CapsText(text = "Watched ✓", style = TorfilxType.MetaCaps, color = TorfilxColors.TextSecondary)
                inProgress != null -> {
                    CapsText(
                        text = "${Format.runtime(inProgress.remainingMs)} left",
                        style = TorfilxType.MetaCaps,
                        color = TorfilxColors.TextSecondary,
                    )
                    CardProgressBar(fraction = inProgress.fraction, modifier = Modifier.width(EPISODE_PROGRESS_WIDTH))
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
    val dimens = LocalTorfilxDimens.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TorfilxColors.ScrimStrong),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 340.dp, max = 520.dp)
                .panel(dimens.hairline)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = TorfilxType.SectionTitle,
                color = TorfilxColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 4.dp),
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
            .background(TorfilxColors.ScrimStrong, TorfilxShapes.Panel)
            .border(LocalTorfilxDimens.current.hairline, TorfilxColors.Rule, TorfilxShapes.Panel)
            .padding(24.dp)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CapsText(text = headline, style = TorfilxType.KickerCaps, color = TorfilxColors.TextTertiary)
        title?.let {
            Text(
                text = it,
                style = TorfilxType.SectionTitle,
                color = TorfilxColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        detail?.let {
            Text(
                text = it,
                style = TorfilxType.Small,
                color = TorfilxColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            TvButton(text = primaryLabel, onClick = onPrimary, autoFocus = true)
            if (secondaryLabel != null && onSecondary != null) {
                TvButton(text = secondaryLabel, onClick = onSecondary, primary = false)
            }
        }
    }
}

/** A dialog or menu: a raised page with a hairline edge. */
internal fun Modifier.panel(hairline: Dp): Modifier = this
    .background(TorfilxColors.Surface, TorfilxShapes.Panel)
    .border(hairline, TorfilxColors.Rule, TorfilxShapes.Panel)
    .padding(32.dp)
