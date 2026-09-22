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
import com.torfilx.core.model.PlaybackProgress
import com.torfilx.core.model.PlayAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

    val uiState: StateFlow<HomeUiState> = combine(
        mediaRepository.observeHome(),
        progressRepository.observeAllProgress(),
    ) { rows, progress ->
        if (rows.isEmpty()) {
            HomeUiState.EmptyCatalog
        } else {
            HomeUiState.Content(hero = heroItems(rows, progress), rows = rows)
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
            progressRepository.markWatched(card.playableId, card.runtimeMs, watched)
        }
    }

    /**
     * What pressing Play on a card does, resolved from local progress: the film or episode it names,
     * or a show's next-up episode. Unavailable when nothing can be played, which opens the details.
     */
    suspend fun playActionFor(card: MediaCard): PlayAction = mediaRepository.playAction(card)

    private fun heroItems(rows: List<HomeRow>, progress: Map<String, PlaybackProgress>): List<HeroItem> {
        // Continue Watching first — the most likely thing the viewer wants — then the whole catalogue
        // ("Recently added"), never the TV shows row alone: the hero is the front page, not a genre.
        val source = rows.firstOrNull { it.kind == HomeRowKind.CONTINUE_WATCHING }
            ?: rows.firstOrNull { it.id == MediaRepository.ROW_CATALOG }
            ?: rows.firstOrNull()
        return source?.items.orEmpty().take(HERO_COUNT).map { card -> mediaRepository.heroItem(card, progress) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val HERO_COUNT = 5
    }
}
