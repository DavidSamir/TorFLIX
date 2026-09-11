package com.torfilx.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torfilx.core.data.repository.MediaRepository
import com.torfilx.core.data.repository.MyListRepository
import com.torfilx.core.data.repository.ProgressRepository
import com.torfilx.core.model.HeroItem
import com.torfilx.core.model.HomeRow
import com.torfilx.core.model.HomeRowKind
import com.torfilx.core.model.MediaCard
import com.torfilx.core.model.PlayActionResolver
import com.torfilx.core.model.PlayAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What Home renders.
 *
 * The catalogue is always local (bundled with the app, or a newer signed release already downloaded),
 * so there is no network error and no "configure your server" state to model: either there are titles
 * or the catalogue is empty or broken. A newer catalogue arriving just makes Content emit again.
 */
sealed interface HomeUiState {
    data object Loading : HomeUiState

    data class Content(
        val hero: List<HeroItem>,
        val rows: List<HomeRow>,
    ) : HomeUiState

    /** The catalogue in use produced no playable entries (missing file or every magnet invalid). */
    data object EmptyCatalog : HomeUiState
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val progressRepository: ProgressRepository,
    private val myListRepository: MyListRepository,
    networkMonitor: com.torfilx.core.common.network.NetworkMonitor,
) : ViewModel() {

    /** True when there is no usable network, so Home can warn that playback will not work. */
    val isOffline: StateFlow<Boolean> = networkMonitor.isOnline
        .map { online -> !online }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    val uiState: StateFlow<HomeUiState> = mediaRepository.observeHome()
        .map { rows ->
            if (rows.isEmpty()) {
                HomeUiState.EmptyCatalog
            } else {
                HomeUiState.Content(hero = heroItems(rows), rows = rows)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = HomeUiState.Loading,
        )

    fun toggleMyList(card: MediaCard) {
        viewModelScope.launch { myListRepository.toggle(card.item.id) }
    }

    /** Menu → Remove on a Continue Watching card. */
    fun removeFromContinueWatching(card: MediaCard) {
        viewModelScope.launch { progressRepository.remove(card.playableId) }
    }

    fun markWatched(card: MediaCard, watched: Boolean) {
        viewModelScope.launch {
            progressRepository.markWatched(card.playableId, card.item.runtimeMs, watched)
        }
    }

    /** What pressing Play on a card should do, resolved from local progress. */
    suspend fun playActionFor(card: MediaCard): PlayAction =
        PlayActionResolver.actionFor(card.item, progressRepository.get(card.item.id))

    private fun heroItems(rows: List<HomeRow>): List<HeroItem> {
        // Continue Watching first — the most likely thing the viewer wants — then the catalogue.
        val source = rows.firstOrNull { it.kind == HomeRowKind.CONTINUE_WATCHING }
            ?: rows.firstOrNull()
        return source?.items.orEmpty().take(HERO_COUNT).map { card ->
            HeroItem(
                card = card,
                action = PlayActionResolver.actionFor(card.item, card.progress),
            )
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val HERO_COUNT = 5
    }
}
