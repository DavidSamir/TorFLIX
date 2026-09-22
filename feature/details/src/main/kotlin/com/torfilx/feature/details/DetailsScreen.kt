package com.torfilx.feature.details

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.torfilx.core.model.Episode
import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.MediaSource
import com.torfilx.core.model.PlayAction
import com.torfilx.core.model.Season
import com.torfilx.core.ui.component.ActionMenu
import com.torfilx.core.ui.component.EpisodeRow
import com.torfilx.core.ui.component.ErrorState
import com.torfilx.core.ui.component.MenuAction
import com.torfilx.core.ui.component.SeasonChips
import com.torfilx.core.ui.component.SeriesBadge
import com.torfilx.core.ui.component.SharingConsentDialog
import com.torfilx.core.ui.component.SkeletonRow
import com.torfilx.core.ui.component.TvButton
import com.torfilx.core.ui.focus.keepNeighbourComposed
import com.torfilx.core.ui.image.Artwork
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.util.Format
import kotlinx.coroutines.delay

/** How hard to try to put focus somewhere after the screen or a menu changes under it. */
private const val FOCUS_ATTEMPTS = 6
private const val FOCUS_RETRY_MS = 40L

/**
 * Details for one title.
 *
 * A film lists every quality the catalogue offers as a separate button, so the viewer chooses what to
 * stream rather than the app guessing. A show has one primary button — resume or play its next-up
 * episode — above its seasons and episodes, where any episode can be played and the Menu key offers
 * a quality, or marks an episode or a whole season watched.
 *
 * Back is both the remote's Back key and a visible button, because on a full-screen backdrop there is
 * otherwise nothing that says "you can leave". With a menu or the consent dialog up, Back closes that
 * first.
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
    val dimens = LocalTorfilxDimens.current
    val sources = state.sources
    Box(Modifier.fillMaxSize()) {
        Backdrop(item = state.item, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = dimens.overscanHorizontal)
                .width(660.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = state.item.title,
                style = MaterialTheme.typography.displayMedium,
                color = TorfilxColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MetaRow(item = state.item)
            state.item.overview?.let { overview ->
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TorfilxColors.TextSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            state.progress?.takeIf { it.fraction > 0f && !it.watched }?.let { progress ->
                Text(
                    text = "${Format.runtime(progress.remainingMs)} left",
                    style = MaterialTheme.typography.labelLarge,
                    color = TorfilxColors.TextSecondary,
                )
            }

            when {
                !torrentAvailable -> Text(
                    text = "BitTorrent is not available on this device, so this title cannot be played.",
                    style = MaterialTheme.typography.labelLarge,
                    color = TorfilxColors.Error,
                )

                sources.isEmpty() -> Text(
                    text = "This catalogue entry has no valid magnet link.",
                    style = MaterialTheme.typography.labelLarge,
                    color = TorfilxColors.Error,
                )

                else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    sources.forEachIndexed { index, source ->
                        TvButton(
                            text = state.primaryAction.label(source, sources.size),
                            onClick = { onPlay(source) },
                            autoFocus = index == 0,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(
                    text = if (state.inMyList) "✓ My List" else "+ My List",
                    onClick = onToggleMyList,
                    primary = false,
                    // Nothing to play means no play button to land on; focus has to go somewhere.
                    autoFocus = sources.isEmpty() || !torrentAvailable,
                )
                TvButton(
                    text = if (state.progress?.watched == true) "Mark unwatched" else "Mark watched",
                    onClick = { onMarkWatched(state.progress?.watched != true) },
                    primary = false,
                )
                TvButton(text = "← Back", onClick = onBack, primary = false)
            }
        }
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
    // button. Requesting focus before a node is attached throws, so the request is retried briefly.
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
        contentPadding = PaddingValues(bottom = LocalTorfilxDimens.current.overscanVertical * 2),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header", contentType = "header") {
            ShowHeader(
                state = state,
                show = show,
                torrentAvailable = viewModel.torrentAvailable,
                canPlay = canPlay,
                primaryFocus = primaryFocus,
                myListFocus = myListFocus,
                onPlayPrimary = onPlayPrimary,
                onToggleMyList = viewModel::toggleMyList,
                onBack = onBack,
            )
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
                    modifier = Modifier.keepNeighbourComposed(1, itemCount, listState, scope),
                )
            }
        }

        if (episodes.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Text(
                    text = "This show has no episodes in the catalogue yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TorfilxColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = LocalTorfilxDimens.current.overscanHorizontal),
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
                    fallbackImage = state.item.images.backdrop ?: state.item.images.poster,
                    // An episode with no source says so in the row; OK on it does nothing more.
                    onClick = { if (episode.isPlayable && viewModel.torrentAvailable) onPlayEpisode(episode) },
                    onMenu = {
                        menuOrigin = requester
                        viewModel.openEpisodeMenu(episode)
                    },
                    focusRequester = requester,
                    modifier = Modifier.keepNeighbourComposed(firstEpisodeIndex + index, itemCount, listState, scope),
                )
            }
        }
    }
}

@Composable
private fun ShowHeader(
    state: DetailsUiState.Content,
    show: ShowDetails,
    torrentAvailable: Boolean,
    canPlay: Boolean,
    primaryFocus: FocusRequester,
    myListFocus: FocusRequester,
    onPlayPrimary: () -> Unit,
    onToggleMyList: () -> Unit,
    onBack: () -> Unit,
) {
    val dimens = LocalTorfilxDimens.current
    Box(Modifier.fillMaxWidth().height(HEADER_HEIGHT)) {
        Backdrop(item = state.item, modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = dimens.overscanHorizontal)
                .padding(bottom = 12.dp)
                .width(700.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SeriesBadge()
            Text(
                text = state.item.title,
                style = MaterialTheme.typography.displayMedium,
                color = TorfilxColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MetaRow(item = state.item)
            state.item.overview?.let { overview ->
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TorfilxColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!torrentAvailable) {
                Text(
                    text = "BitTorrent is not available on this device, so this show cannot be played.",
                    style = MaterialTheme.typography.labelLarge,
                    color = TorfilxColors.Error,
                )
            } else if (state.primaryAction == PlayAction.Unavailable) {
                Text(
                    text = "None of this show's episodes has a valid magnet link.",
                    style = MaterialTheme.typography.labelLarge,
                    color = TorfilxColors.Error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (canPlay) {
                    TvButton(
                        text = showPrimaryLabel(state.primaryAction, show),
                        onClick = onPlayPrimary,
                        focusRequester = primaryFocus,
                    )
                }
                TvButton(
                    text = if (state.inMyList) "✓ My List" else "+ My List",
                    onClick = onToggleMyList,
                    primary = false,
                    focusRequester = myListFocus,
                )
                TvButton(text = "← Back", onClick = onBack, primary = false)
            }
        }
    }
}

/** "▶ Resume S2 E3 · 12:40", "▶ Play S1 E1", "▶ Play again from S1 E1". */
internal fun showPrimaryLabel(action: PlayAction, show: ShowDetails): String {
    fun code(id: String) = show.seasons.firstNotNullOfOrNull { s -> s.episodes.firstOrNull { it.id == id } }?.code
    return when (action) {
        PlayAction.Unavailable -> "Unavailable"
        is PlayAction.Resume -> "▶ Resume ${code(action.itemId).orEmpty()} · ${Format.timecode(action.positionMs)}"
        is PlayAction.Play ->
            if (action.restart) "▶ Play again from ${code(action.itemId).orEmpty()}" else "▶ Play ${code(action.itemId).orEmpty()}"
    }.replace("  ", " ").trim()
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
                        add(MenuAction("▶ Play in ${source.qualityLabel()}") { onPlaySource(episode, source) })
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

private val HEADER_HEIGHT = 380.dp

// --- Shared ---------------------------------------------------------------------------------------

@Composable
private fun Backdrop(item: MediaItem, modifier: Modifier = Modifier) {
    Box(modifier) {
        Artwork(
            url = item.images.backdrop ?: item.images.poster,
            title = item.title,
            seed = item.id,
            showGeneratedLabel = false,
            widthDp = 960.dp,
            heightDp = 540.dp,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to TorfilxColors.ScrimStrong,
                    0.6f to TorfilxColors.ScrimSoft,
                    1f to TorfilxColors.Transparent,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.55f to TorfilxColors.Transparent,
                    1f to TorfilxColors.Background,
                ),
            ),
        )
    }
}

@Composable
private fun MetaRow(item: MediaItem) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Format.rating(item.communityRating)?.let { rating ->
            Text(
                text = "★ $rating",
                style = MaterialTheme.typography.labelLarge,
                color = TorfilxColors.Warning,
            )
        }
        Text(
            text = Format.metaLine(item),
            style = MaterialTheme.typography.labelLarge,
            color = TorfilxColors.TextSecondary,
        )
    }
}

@Composable
private fun DetailsSkeleton() {
    Column(
        Modifier.fillMaxSize().padding(top = 60.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        SkeletonRow(landscape = true, count = 3)
        SkeletonRow(count = 5)
    }
}

/**
 * Button label: the action for the film, plus the quality when there is more than one to choose
 * from ("▶ Resume · 1080p").
 */
private fun PlayAction.label(source: MediaSource, sourceCount: Int): String {
    val action = when (this) {
        PlayAction.Unavailable -> "Unavailable"
        is PlayAction.Play -> if (restart) "▶ Play again" else "▶ Play"
        is PlayAction.Resume -> "▶ Resume ${Format.timecode(positionMs)}"
    }
    if (sourceCount <= 1) return action
    val quality = source.label?.substringAfter("· ", "") ?: ""
    return if (quality.isBlank()) action else "$action · $quality"
}
