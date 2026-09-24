package com.torfilx.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.torfilx.core.ui.component.KeyboardLayout
import com.torfilx.core.ui.component.OnScreenKeyboard
import com.torfilx.core.ui.component.PosterCard
import com.torfilx.core.ui.component.RowHeader
import com.torfilx.core.ui.component.SearchField
import com.torfilx.core.ui.component.TvTextLink
import com.torfilx.core.ui.focus.keepNeighbourRowComposed
import com.torfilx.core.ui.theme.LocalTorfilxDimens
import com.torfilx.core.ui.theme.TorfilxColors
import com.torfilx.core.ui.theme.TorfilxType
import com.torfilx.core.ui.util.Format

/**
 * Search: on-screen keyboard on the left, live results on the right.
 *
 * The system IME is deliberately not used — on Fire TV it is an overlay that grabs focus and fights
 * with Compose focus traversal (plan.md §6.5).
 */
@Composable
fun SearchScreen(
    onOpenDetails: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dimens = LocalTorfilxDimens.current

    androidx.activity.compose.BackHandler(enabled = true) { onBack() }
    var layout by remember { mutableStateOf(KeyboardLayout.LATIN) }
    val firstKeyFocus = remember { FocusRequester() }
    val resultsState = rememberLazyGridState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching { firstKeyFocus.requestFocus() }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(TorfilxColors.Background)
            .padding(horizontal = dimens.overscanHorizontal, vertical = dimens.overscanVertical),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(
            modifier = Modifier.width(KEYBOARD_COLUMN_WIDTH).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SearchField(
                value = state.query,
                placeholder = "Search films and shows",
            )
            OnScreenKeyboard(
                onCharacter = viewModel::appendCharacter,
                onBackspace = viewModel::backspace,
                onClear = viewModel::clear,
                layout = layout,
                onLayoutChange = { layout = it },
                firstKeyFocusRequester = firstKeyFocus,
            )
        }

        Box(Modifier.weight(1f).fillMaxHeight()) {
            when {
                state.errorMessage != null -> CenteredMessage(
                    title = "Search unavailable",
                    message = state.errorMessage!!,
                )

                state.showRecent && state.recentSearches.isNotEmpty() -> RecentSearches(
                    recent = state.recentSearches,
                    onPick = viewModel::setQuery,
                    onClear = viewModel::clearHistory,
                )

                state.showRecent -> CenteredMessage(
                    title = "Search your library",
                    message = "Type at least two letters using the keyboard on the left.",
                )

                state.showNoResults -> CenteredMessage(
                    title = "No results for “${state.query}”",
                    message = "Check the spelling, or try part of the title.",
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(RESULT_COLUMNS),
                    state = resultsState,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusGroup()
                        .focusRestorer(),
                    // Room for a focused poster in the first row to rise, with its frame.
                    contentPadding = PaddingValues(
                        top = dimens.focusLift + dimens.focusFrameGap + dimens.focusFrame + 6.dp,
                        bottom = 24.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing),
                    verticalArrangement = Arrangement.spacedBy(dimens.rowSpacing - 12.dp),
                ) {
                    items(
                        count = state.results.size,
                        key = { index -> state.results[index].card.playableId },
                        contentType = { "poster" },
                    ) { index ->
                        val result = state.results[index]
                        PosterCard(
                            card = result.card,
                            onClick = { onOpenDetails(result.card.item.id) },
                            // Same dead-end as every other lazy layout on this screen family: without
                            // it the results stop at the last composed row.
                            modifier = Modifier.fillMaxWidth().wrapContentWidth().keepNeighbourRowComposed(
                                index = index,
                                itemCount = state.results.size,
                                state = resultsState,
                                scope = scope,
                                columns = RESULT_COLUMNS,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** Earlier searches, as a short numbered list of links. */
@Composable
private fun RecentSearches(
    recent: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RowHeader(
            title = "Recent searches",
            numeral = Format.sectionNumeral(1),
            inset = 0.dp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        recent.forEach { entry ->
            TvTextLink(text = entry, onClick = { onPick(entry) }, arrow = true)
        }
        TvTextLink(text = "Clear history", onClick = onClear, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun CenteredMessage(title: String, message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = TorfilxType.SectionTitle,
                color = TorfilxColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = TorfilxType.Caption,
                color = TorfilxColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Six keys of 48 dp and their gaps, and nothing more, so the results get the rest of the page. */
private val KEYBOARD_COLUMN_WIDTH = 330.dp

/** Three 140 dp posters, with their gaps, fill what the keyboard leaves. */
private const val RESULT_COLUMNS = 3
