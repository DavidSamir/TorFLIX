package com.torfilx.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.torfilx.core.model.HeroItem
import com.torfilx.core.model.HomeRowKind
import com.torfilx.core.model.MediaCard
import com.torfilx.core.model.PlayAction
import com.torfilx.core.ui.component.EmptyState
import com.torfilx.core.ui.component.HeroSection
import com.torfilx.core.ui.component.MediaRow
import com.torfilx.core.ui.component.SkeletonRow
import com.torfilx.core.ui.focus.keepNeighbourComposed
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import kotlinx.coroutines.launch

/**
 * Home.
 *
 * Focus rules that matter here (plan.md §5.2): initial focus lands on the hero's play button only
 * once content exists; each row restores its own last-focused card; and a refresh failure over
 * cached content is a banner, never a blank screen.
 */
@Composable
fun HomeScreen(
    onOpenDetails: (String) -> Unit,
    onPlay: (PlayAction) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val offline by viewModel.isOffline.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val play: (MediaCard) -> Unit = { card ->
        scope.launch {
            when (val action = viewModel.playActionFor(card)) {
                PlayAction.Unavailable -> onOpenDetails(card.item.id)
                else -> onPlay(action)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(TorfilxColors.Background)) {
        when (val current = state) {
            HomeUiState.Loading -> HomeSkeleton()

            HomeUiState.EmptyCatalog -> EmptyState(
                title = "Nothing to watch yet",
                message = "The catalogue has no playable titles. With sharing on, the app looks for " +
                    "the latest signed catalogue on the peer network.",
            )

            is HomeUiState.Content -> HomeContent(
                state = current,
                onCardClick = { card -> onOpenDetails(card.item.id) },
                onPlay = play,
                onToggleMyList = viewModel::toggleMyList,
                onRemoveFromContinue = viewModel::removeFromContinueWatching,
            )
        }

        // Playback needs a connection; browsing does not. Warn without blocking the catalogue.
        if (offline) {
            StaleBanner(
                message = "You're offline. You can browse, but playing a title needs a connection.",
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState.Content,
    onCardClick: (MediaCard) -> Unit,
    onPlay: (MediaCard) -> Unit,
    onToggleMyList: (MediaCard) -> Unit,
    onRemoveFromContinue: (MediaCard) -> Unit,
) {
    val dimens = LocalTorfilxDimens.current
    val listState = rememberLazyListState()
    val heroPlayFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // Initial focus lands on the hero Play button, and only once content exists — requesting focus
    // while the screen is still Loading silently fails (plan.md §5.2 rule 1).
    LaunchedEffect(state.hero.isNotEmpty()) {
        if (state.hero.isNotEmpty()) runCatching { heroPlayFocus.requestFocus() }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .focusRestorer(),
            contentPadding = PaddingValues(bottom = dimens.overscanVertical * 2),
            verticalArrangement = Arrangement.spacedBy(dimens.rowSpacing),
        ) {
            if (state.hero.isNotEmpty()) {
                item(key = "hero", contentType = "hero") {
                    val heroByCard = state.hero.associateBy { it.card.playableId }
                    HeroSection(
                        items = state.hero.map { it.card },
                        primaryActionLabel = { card -> heroByCard[card.playableId]?.let(::heroLabel) ?: "▶ Play" },
                        onPlay = onPlay,
                        onMoreInfo = onCardClick,
                        onToggleMyList = onToggleMyList,
                        playFocusRequester = heroPlayFocus,
                    )
                }
            }

            items(
                count = state.rows.size,
                key = { index -> state.rows[index].id },
                contentType = { "row" },
            ) { index ->
                val row = state.rows[index]
                MediaRow(
                    // The hero occupies index 0 of the lazy column, so a row's position there is one
                    // further along than its position in `rows`. Getting that off by one would nudge
                    // the scroll to the wrong row and fight the focus instead of helping it.
                    modifier = Modifier.keepNeighbourComposed(
                        index = index + if (state.hero.isNotEmpty()) 1 else 0,
                        itemCount = state.rows.size + 1,
                        state = listState,
                        scope = scope,
                    ),
                    title = row.title,
                    items = row.items,
                    totalItems = row.totalItems,
                    seeAllIn = row.seeAllIn,
                    landscape = row.kind == HomeRowKind.CONTINUE_WATCHING,
                    onCardClick = { card ->
                        if (row.kind == HomeRowKind.CONTINUE_WATCHING) onPlay(card) else onCardClick(card)
                    },
                    onCardLongClick = { card ->
                        if (row.kind == HomeRowKind.CONTINUE_WATCHING) {
                            onRemoveFromContinue(card)
                        } else {
                            onToggleMyList(card)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StaleBanner(message: String, modifier: Modifier = Modifier) {
    val dimens = LocalTorfilxDimens.current
    Box(
        modifier
            .fillMaxWidth()
            .background(TorfilxColors.SurfaceHigh)
            .padding(horizontal = dimens.overscanHorizontal, vertical = 8.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelLarge,
            color = TorfilxColors.Warning,
        )
    }
}

@Composable
private fun HomeSkeleton() {
    val dimens = LocalTorfilxDimens.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = dimens.overscanVertical),
        verticalArrangement = Arrangement.spacedBy(dimens.rowSpacing),
    ) {
        SkeletonRow(landscape = true, count = 4)
        SkeletonRow(count = 6)
        SkeletonRow(count = 6)
    }
}

/**
 * The hero's play button: what it will actually do, taken from the resolved action.
 *
 * "▶ Resume S2 E3" for a show says which episode; a film just says "▶ Resume". Nothing playable reads
 * "Details", because that is where the button then goes.
 */
internal fun heroLabel(hero: HeroItem): String {
    val code = hero.card.episode?.code
    return when (val action = hero.action) {
        PlayAction.Unavailable -> "Details"
        is PlayAction.Resume -> if (code != null) "▶ Resume $code" else "▶ Resume"
        is PlayAction.Play -> when {
            action.restart -> "▶ Play again"
            code != null -> "▶ Play $code"
            else -> "▶ Play"
        }
    }
}
