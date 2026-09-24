package com.torfilx.feature.home

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.torfilx.core.model.HeroItem
import com.torfilx.core.model.HomeRowKind
import com.torfilx.core.model.MediaCard
import com.torfilx.core.model.PlayAction
import com.torfilx.core.ui.component.EmptyState
import com.torfilx.core.ui.component.HeroSection
import com.torfilx.core.ui.component.MediaRow
import com.torfilx.core.ui.component.SkeletonRow
import com.torfilx.core.ui.focus.SectionBringIntoViewSpec
import com.torfilx.core.ui.focus.bringIntoViewAsWhole
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.theme.TorfilxType
import com.torfilx.core.ui.theme.ruleBelow
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

// LocalBringIntoViewSpec is how a list chooses where a focused item settles; still marked experimental.
@OptIn(ExperimentalFoundationApi::class)
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
    // The page moves a section at a time; the rows inside it keep the platform's sideways scrolling.
    val density = LocalDensity.current
    val rowScrolling = LocalBringIntoViewSpec.current
    val pageScrolling = remember(density) { SectionBringIntoViewSpec(with(density) { SECTION_TOP_MARGIN.toPx() }) }

    // Initial focus lands on the hero Play button, and only once content exists — requesting focus
    // while the screen is still Loading silently fails (plan.md §5.2 rule 1).
    LaunchedEffect(state.hero.isNotEmpty()) {
        if (state.hero.isNotEmpty()) runCatching { heroPlayFocus.requestFocus() }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides pageScrolling) {
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
                        primaryActionLabel = { card -> heroByCard[card.playableId]?.let(::heroLabel) ?: "Play" },
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
                CompositionLocalProvider(LocalBringIntoViewSpec provides rowScrolling) {
                    MediaRow(
                        // A focused row is brought in whole and set at the top of the page, which leaves
                        // the next row's header showing beneath it. That keeps the next row composed, so
                        // Down always has somewhere to go; a scroll to keep it composed would only fight
                        // this one.
                        modifier = Modifier.bringIntoViewAsWhole(),
                        title = row.title,
                        // Sections are numbered down the page like a magazine's; the shows are ranked.
                        section = index + 1,
                        ranked = row.kind == HomeRowKind.SHOWS,
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
}

/** Where a focused section settles: this far below the masthead, so the two rules never touch. */
private val SECTION_TOP_MARGIN = 16.dp

@Composable
private fun StaleBanner(message: String, modifier: Modifier = Modifier) {
    val dimens = LocalTorfilxDimens.current
    Box(
        modifier
            .fillMaxWidth()
            .background(TorfilxColors.SurfaceHigh)
            .ruleBelow(dimens.hairline)
            .padding(horizontal = dimens.overscanHorizontal, vertical = 8.dp),
    ) {
        Text(
            text = message,
            style = TorfilxType.Caption,
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
 * "Resume S2 E3" for a show says which episode; a film just says "Resume". Nothing playable reads
 * "Details", because that is where the link then goes. The link draws its own arrow.
 */
internal fun heroLabel(hero: HeroItem): String {
    val code = hero.card.episode?.code
    return when (val action = hero.action) {
        PlayAction.Unavailable -> "Details"
        is PlayAction.Resume -> if (code != null) "Resume $code" else "Resume"
        is PlayAction.Play -> when {
            action.restart -> "Play again"
            code != null -> "Play $code"
            else -> "Play"
        }
    }
}
