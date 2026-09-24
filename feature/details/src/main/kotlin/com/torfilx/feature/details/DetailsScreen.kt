package com.torfilx.feature.details

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.torfilx.core.model.Episode
import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.MediaSource
import com.torfilx.core.model.PlayAction
import com.torfilx.core.model.Season
import com.torfilx.core.ui.component.ActionMenu
import com.torfilx.core.ui.component.CapsText
import com.torfilx.core.ui.component.EpisodeRow
import com.torfilx.core.ui.component.ErrorState
import com.torfilx.core.ui.component.Kicker
import com.torfilx.core.ui.component.MenuAction
import com.torfilx.core.ui.component.Plate
import com.torfilx.core.ui.component.RowHeader
import com.torfilx.core.ui.component.SeasonChips
import com.torfilx.core.ui.component.SharingConsentDialog
import com.torfilx.core.ui.component.SkeletonBox
import com.torfilx.core.ui.component.TvTextLink
import com.torfilx.core.ui.component.kindAndYear
import com.torfilx.core.ui.focus.bringIntoViewAsWhole
import com.torfilx.core.ui.focus.keepNeighbourComposed
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.theme.TorfilxType
import com.torfilx.core.ui.theme.ruleAbove
import com.torfilx.core.ui.theme.ruleBelow
import com.torfilx.core.ui.util.Format
import kotlinx.coroutines.delay

/** How hard to try to put focus somewhere after the screen or a menu changes under it. */
private const val FOCUS_ATTEMPTS = 6
private const val FOCUS_RETRY_MS = 40L

/** The spread's top margin: the tab bar is hidden here, so the page starts under the overscan inset. */
private val SPREAD_TOP = 44.dp
private val FACT_LABEL_WIDTH = 132.dp
private const val MAX_FACTS = 3

/**
 * Details for one title, set as a magazine spread: kicker, title, deck and a paragraph beside the
 * poster as a plate, the actions as text links, and a short table of facts. A show's episodes follow
 * as a numbered list.
 *
 * A film lists every quality the catalogue offers as a separate link, so the viewer chooses what to
 * stream rather than the app guessing. A show has one primary link — resume or play its next-up
 * episode — above its seasons and episodes, where any episode can be played and the Menu key offers
 * a quality, or marks an episode or a whole season watched.
 *
 * Back is both the remote's Back key and a visible link, because otherwise nothing on the page says
 * "you can leave". With a menu or the consent dialog up, Back closes that first.
 */
@Composable
fun DetailsScreen(
    onPlay: (playableId: String, sourceId: String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingPlay by viewModel.pendingPlay.collectAsStateWithLifecycle()
    val menu by viewModel.menu.collectAsStateWithLifecycle()
    val play: (PendingPlay) -> Unit = { ready -> onPlay(ready.playableId, ready.sourceId) }

    BackHandler(enabled = pendingPlay != null) { viewModel.onConsentDeclined() }
    BackHandler(enabled = pendingPlay == null && menu != null) { viewModel.dismissMenu() }

    Box(modifier = modifier.fillMaxSize().background(TorfilxColors.Background)) {
        when (val current = state) {
            DetailsUiState.Loading -> DetailsSkeleton()

            is DetailsUiState.Error -> ErrorState(
                title = "Can't open this title",
                message = current.message,
                primaryActionLabel = "Back",
                onPrimaryAction = onBack,
            )

            is DetailsUiState.Content -> {
                val show = current.show
                if (show == null) {
                    FilmDetails(
                        state = current,
                        torrentAvailable = viewModel.torrentAvailable,
                        onPlay = { source -> viewModel.requestPlayback(current.item.id, source.id, play) },
                        onToggleMyList = viewModel::toggleMyList,
                        onMarkWatched = { viewModel.markWatched(it, current.item.runtimeMs) },
                        onBack = onBack,
                    )
                } else {
                    ShowDetailsContent(
                        state = current,
                        show = show,
                        viewModel = viewModel,
                        menuOpen = menu != null,
                        onPlayPrimary = { viewModel.requestPrimary(play) },
                        onPlayEpisode = { episode -> viewModel.requestEpisode(episode, null, play) },
                        onBack = onBack,
                    )
                }
            }
        }

        menu?.let { open ->
            DetailsMenuOverlay(
                menu = open,
                onPlaySource = { episode, source ->
                    viewModel.dismissMenu()
                    viewModel.requestEpisode(episode, source.id, play)
                },
                onMarkEpisode = viewModel::markEpisodeWatched,
                onMarkSeason = viewModel::markSeasonWatched,
                onDismiss = viewModel::dismissMenu,
            )
        }

        if (pendingPlay != null) {
            SharingConsentDialog(
                storageSummary = "While sharing, the app keeps part of what you watch on this device " +
                    "and uploads it to others. It never uses more than the share of free space set in " +
                    "Settings, and clears the oldest titles first.",
                onAccept = { viewModel.onConsentAccepted(play) },
                onDecline = viewModel::onConsentDeclined,
            )
        }
    }
}

// --- Film -----------------------------------------------------------------------------------------

@Composable
private fun FilmDetails(
    state: DetailsUiState.Content,
    torrentAvailable: Boolean,
    onPlay: (MediaSource) -> Unit,
    onToggleMyList: () -> Unit,
    onMarkWatched: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val sources = state.sources
    val watched = state.progress?.watched == true
    // A long overview or a two-line title can run the spread past the bottom of the screen; the
    // column scrolls to whatever link is focused.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spread(
            modifier = Modifier.bringIntoViewAsWhole(),
            item = state.item,
            facts = filmFacts(state.item, sources),
            notice = {
                state.progress?.takeIf { it.fraction > 0f && !it.watched }?.let { progress ->
                    CapsText(
                        text = "${Format.runtime(progress.remainingMs)} left",
                        style = TorfilxType.MetaCaps,
                        color = TorfilxColors.TextSecondary,
                    )
                }
                when {
                    !torrentAvailable -> Notice("BitTorrent is not available on this device, so this title cannot be played.")
                    sources.isEmpty() -> Notice("This catalogue entry has no valid magnet link.")
                }
            },
            primaryActions = {
                if (torrentAvailable) {
                    sources.forEachIndexed { index, source ->
                        TvTextLink(
                            text = state.primaryAction.label(source, sources.size),
                            onClick = { onPlay(source) },
                            arrow = index == 0,
                            autoFocus = index == 0,
                        )
                    }
                }
            },
            secondaryActions = {
                TvTextLink(
                    text = if (state.inMyList) "In My List ✓" else "Add to My List",
                    onClick = onToggleMyList,
                    // Nothing to play means no play link to land on; focus has to go somewhere.
                    autoFocus = sources.isEmpty() || !torrentAvailable,
                )
                TvTextLink(
                    text = if (watched) "Mark unwatched" else "Mark watched",
                    onClick = { onMarkWatched(!watched) },
                )
                TvTextLink(text = "Back", onClick = onBack)
            },
        )
    }
}

// --- Show -----------------------------------------------------------------------------------------

@Composable
private fun ShowDetailsContent(
    state: DetailsUiState.Content,
    show: ShowDetails,
    viewModel: DetailsViewModel,
    menuOpen: Boolean,
    onPlayPrimary: () -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    onBack: () -> Unit,
) {
    val dimens = LocalTorfilxDimens.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val primaryFocus = remember { FocusRequester() }
    val myListFocus = remember { FocusRequester() }
    val episodeFocus = remember(state.item.id) { HashMap<String, FocusRequester>() }
    val chipFocus = remember(state.item.id) { HashMap<Int, FocusRequester>() }
    // Where focus goes back to when a menu closes: the row or chip that opened it.
    var menuOrigin by remember { mutableStateOf<FocusRequester?>(null) }

    val seasons = show.seasons
    val episodes = show.selectedSeason?.episodes.orEmpty()
    val chipsShown = seasons.size > 1
    val firstEpisodeIndex = if (chipsShown) 2 else 1
    val itemCount = firstEpisodeIndex + episodes.size.coerceAtLeast(1)
    val canPlay = viewModel.torrentAvailable && state.primaryAction != PlayAction.Unavailable

    // Initial focus, once content exists: back on the episode that was just played, or on the primary
    // link. Requesting focus before a node is attached throws, so the request is retried briefly.
    LaunchedEffect(state.item.id) {
        val returnTo = viewModel.lastPlayedEpisodeId?.let { id -> episodes.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
        if (returnTo != null) {
            listState.scrollToItem(firstEpisodeIndex + returnTo)
            val requester = episodeFocus.getOrPut(episodes[returnTo].id) { FocusRequester() }
            if (requestWithRetry(requester)) return@LaunchedEffect
        }
        requestWithRetry(if (canPlay) primaryFocus else myListFocus)
    }

    LaunchedEffect(menuOpen) {
        if (!menuOpen) menuOrigin?.let { origin -> requestWithRetry(origin); menuOrigin = null }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = dimens.overscanVertical * 2),
    ) {
        item(key = "header", contentType = "header") {
            // Focus on a link shows the whole header, so the title never scrolls away from it.
            Column(Modifier.bringIntoViewAsWhole()) {
                Spread(
                    item = state.item,
                    facts = showFacts(state.item),
                    notice = {
                        if (!viewModel.torrentAvailable) {
                            Notice("BitTorrent is not available on this device, so this show cannot be played.")
                        } else if (state.primaryAction == PlayAction.Unavailable) {
                            Notice("None of this show's episodes has a valid magnet link.")
                        }
                    },
                    primaryActions = {
                        if (canPlay) {
                            TvTextLink(
                                text = showPrimaryLabel(state.primaryAction, show),
                                onClick = onPlayPrimary,
                                arrow = true,
                                focusRequester = primaryFocus,
                            )
                        }
                        TvTextLink(
                            text = if (state.inMyList) "In My List ✓" else "Add to My List",
                            onClick = viewModel::toggleMyList,
                            focusRequester = myListFocus,
                        )
                        TvTextLink(text = "Back", onClick = onBack)
                    },
                )
                RowHeader(
                    title = if (chipsShown) "Episodes" else show.selectedSeason?.name ?: "Episodes",
                    numeral = Format.sectionNumeral(1),
                    subtitle = episodeCount(episodes.size, if (chipsShown) show.selectedSeason?.name else null),
                    modifier = Modifier.padding(top = 36.dp, bottom = if (chipsShown) 8.dp else 16.dp),
                )
            }
        }

        if (chipsShown) {
            item(key = "seasons", contentType = "seasons") {
                SeasonChips(
                    seasons = seasons,
                    selected = show.selectedSeason?.number,
                    onSelect = viewModel::selectSeason,
                    onMenu = { season ->
                        menuOrigin = chipFocus.getOrPut(season.number) { FocusRequester() }
                        viewModel.openSeasonMenu(season)
                    },
                    focusRequesterFor = { season -> chipFocus.getOrPut(season.number) { FocusRequester() } },
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .keepNeighbourComposed(1, itemCount, listState, scope),
                )
            }
        }

        if (episodes.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Text(
                    text = "This show has no episodes in the catalogue yet.",
                    style = TorfilxType.Caption,
                    color = TorfilxColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = dimens.overscanHorizontal),
                )
            }
        } else {
            // Prefixed so an episode id can never collide with the fixed keys above (a duplicate key is
            // a crash), however a hand-edited catalogue names its episodes.
            items(count = episodes.size, key = { "episode:" + episodes[it].id }, contentType = { "episode" }) { index ->
                val episode = episodes[index]
                val requester = episodeFocus.getOrPut(episode.id) { FocusRequester() }
                EpisodeRow(
                    episode = episode,
                    progress = show.episodeProgress[episode.id],
                    // An episode with no source says so in the row; OK on it does nothing more.
                    onClick = { if (episode.isPlayable && viewModel.torrentAvailable) onPlayEpisode(episode) },
                    onMenu = {
                        menuOrigin = requester
                        viewModel.openEpisodeMenu(episode)
                    },
                    focusRequester = requester,
                    first = index == 0,
                    modifier = Modifier.keepNeighbourComposed(firstEpisodeIndex + index, itemCount, listState, scope),
                )
            }
        }
    }
}

/** "Resume S2 E3 · 12:40", "Play S1 E1", "Play again from S1 E1". The link draws its own arrow. */
internal fun showPrimaryLabel(action: PlayAction, show: ShowDetails): String {
    fun code(id: String) = show.seasons.firstNotNullOfOrNull { s -> s.episodes.firstOrNull { it.id == id } }?.code
    return when (action) {
        PlayAction.Unavailable -> "Unavailable"
        is PlayAction.Resume -> "Resume ${code(action.itemId).orEmpty()} · ${Format.timecode(action.positionMs)}"
        is PlayAction.Play ->
            if (action.restart) "Play again from ${code(action.itemId).orEmpty()}" else "Play ${code(action.itemId).orEmpty()}"
    }.replace("  ", " ").trim()
}

/** "8 episodes", or "Season 2 · 8 episodes" when the season is chosen by chips below. */
private fun episodeCount(count: Int, season: String?): String {
    val episodes = if (count == 1) "1 episode" else "$count episodes"
    return if (season != null) "$season · $episodes" else episodes
}

@Composable
private fun DetailsMenuOverlay(
    menu: DetailsMenu,
    onPlaySource: (Episode, MediaSource) -> Unit,
    onMarkEpisode: (Episode, Boolean) -> Unit,
    onMarkSeason: (Season, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    when (menu) {
        is DetailsMenu.ForEpisode -> {
            val episode = menu.episode
            val actions = buildList {
                // A quality choice only means something when there is more than one.
                if (menu.sources.size > 1) {
                    menu.sources.forEach { source ->
                        add(MenuAction("Play in ${source.qualityLabel()}") { onPlaySource(episode, source) })
                    }
                }
                add(
                    if (menu.watched) {
                        MenuAction("Mark unwatched") { onMarkEpisode(episode, false) }
                    } else {
                        MenuAction("Mark watched") { onMarkEpisode(episode, true) }
                    },
                )
            }
            ActionMenu(title = "${episode.code} · ${episode.displayName}", actions = actions, onDismiss = onDismiss)
        }
        is DetailsMenu.ForSeason -> {
            val season = menu.season
            ActionMenu(
                title = season.name,
                actions = listOf(
                    if (menu.watched) {
                        MenuAction("Mark season unwatched") { onMarkSeason(season, false) }
                    } else {
                        MenuAction("Mark season watched") { onMarkSeason(season, true) }
                    },
                ),
                onDismiss = onDismiss,
            )
        }
    }
}

/** "720p" out of "Torrent · 720p"; the whole label when it carries no quality. */
private fun MediaSource.qualityLabel(): String =
    label?.substringAfter("· ", "")?.takeIf { it.isNotBlank() } ?: label ?: "this quality"

private suspend fun requestWithRetry(requester: FocusRequester): Boolean {
    repeat(FOCUS_ATTEMPTS) { attempt ->
        if (runCatching { requester.requestFocus() }.isSuccess) return true
        delay(FOCUS_RETRY_MS * (attempt + 1))
    }
    return false
}

// --- The spread -----------------------------------------------------------------------------------

/**
 * The opening of a title: kicker, title, deck and a paragraph, then [notice] (what is left to watch,
 * or why nothing can play), the actions as links — [primaryActions] on the first line and
 * [secondaryActions], if any, on the next — and the facts; beside it all, the poster as a plate.
 */
@Composable
private fun Spread(
    item: MediaItem,
    facts: List<Pair<String, String>>,
    notice: @Composable () -> Unit,
    primaryActions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    secondaryActions: (@Composable RowScope.() -> Unit)? = null,
) {
    val dimens = LocalTorfilxDimens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.overscanHorizontal)
            .padding(top = SPREAD_TOP),
        horizontalArrangement = Arrangement.spacedBy(56.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Kicker(text = kindAndYear(item))
            Text(
                text = item.title,
                style = TorfilxType.displayFor(item.title),
                color = TorfilxColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.genres.take(3).takeIf { it.isNotEmpty() }?.let { genres ->
                Text(
                    text = genres.joinToString(", ") + ".",
                    style = TorfilxType.Deck,
                    color = TorfilxColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    text = overview,
                    style = TorfilxType.Reading,
                    color = TorfilxColors.TextSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            notice()
            Row(
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                modifier = Modifier.padding(top = 4.dp),
                content = primaryActions,
            )
            secondaryActions?.let { actions ->
                Row(horizontalArrangement = Arrangement.spacedBy(36.dp), content = actions)
            }
            if (facts.isNotEmpty()) Facts(facts, Modifier.padding(top = 12.dp))
        }
        Plate(item = item, label = "Plate I")
    }
}

/** A short table of facts between hairline rules: "RATING   8.4 / 10". */
@Composable
private fun Facts(facts: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val hairline = LocalTorfilxDimens.current.hairline
    Column(modifier.fillMaxWidth().ruleAbove(hairline)) {
        facts.forEach { (label, value) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .ruleBelow(hairline)
                    .padding(vertical = 9.dp),
            ) {
                CapsText(
                    text = label,
                    style = TorfilxType.MetaCaps,
                    color = TorfilxColors.TextTertiary,
                    modifier = Modifier.width(FACT_LABEL_WIDTH),
                )
                CapsText(text = value, style = TorfilxType.MetaCaps, color = TorfilxColors.TextPrimary)
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(text = text, style = TorfilxType.Caption, color = TorfilxColors.Error)
}

private fun filmFacts(item: MediaItem, sources: List<MediaSource>): List<Pair<String, String>> = listOfNotNull(
    Format.runtime(item.runtimeMs).takeIf { it.isNotEmpty() }?.let { "Running time" to it },
    Format.rating(item.communityRating)?.let { "Rating" to "$it / 10" },
    item.ageRating?.takeIf { it.isNotBlank() }?.let { "Rated" to it },
    sources.mapNotNull { source -> source.label?.substringAfter("· ", "")?.takeIf { it.isNotBlank() } }
        .takeIf { it.size > 1 }
        ?.let { "Quality" to it.joinToString(", ") },
).take(MAX_FACTS)

private fun showFacts(item: MediaItem): List<Pair<String, String>> = listOfNotNull(
    item.seasonCount.takeIf { it > 0 }?.let { "Seasons" to it.toString() },
    item.episodeCount.takeIf { it > 0 }?.let { "Episodes" to it.toString() },
    Format.rating(item.communityRating)?.let { "Rating" to "$it / 10" },
    item.ageRating?.takeIf { it.isNotBlank() }?.let { "Rated" to it },
).take(MAX_FACTS)

@Composable
private fun DetailsSkeleton() {
    val dimens = LocalTorfilxDimens.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.overscanHorizontal)
            .padding(top = SPREAD_TOP),
        horizontalArrangement = Arrangement.spacedBy(56.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SkeletonBox(width = 180.dp, height = 14.dp)
            SkeletonBox(width = 420.dp, height = 54.dp)
            SkeletonBox(width = 260.dp, height = 22.dp)
            repeat(3) { SkeletonBox(width = 560.dp, height = 16.dp) }
        }
        SkeletonBox(width = 192.dp, height = 280.dp)
    }
}

/**
 * Link label: the action for the film, plus the quality when there is more than one to choose from
 * ("Resume 12:40 · 1080p").
 */
private fun PlayAction.label(source: MediaSource, sourceCount: Int): String {
    val action = when (this) {
        PlayAction.Unavailable -> "Unavailable"
        is PlayAction.Play -> if (restart) "Play again" else "Play"
        is PlayAction.Resume -> "Resume ${Format.timecode(positionMs)}"
    }
    if (sourceCount <= 1) return action
    val quality = source.label?.substringAfter("· ", "") ?: ""
    return if (quality.isBlank()) action else "$action · $quality"
}
