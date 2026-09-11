package com.torfilx.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torfilx.core.common.error.DataError
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.data.repository.MediaRepository
import com.torfilx.core.data.repository.MyListRepository
import com.torfilx.core.data.repository.ProgressRepository
import com.torfilx.core.model.LibraryQuery
import com.torfilx.core.model.LibrarySort
import com.torfilx.core.model.MediaCard
import com.torfilx.core.model.WatchedFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "Library"

/** Browse mode: the Movies tab, the Shows tab, or My List (same grid, different source). */
enum class LibraryMode { MOVIES, MY_LIST }

data class LibraryUiState(
    val mode: LibraryMode = LibraryMode.MOVIES,
    val cards: List<MediaCard> = emptyList(),
    val genres: List<String> = emptyList(),
    val query: LibraryQuery = LibraryQuery(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    /** The release number of the catalogue in use; 0 when the catalogue carries none. */
    val catalogueVersion: Long = 0,
) {
    val isEmpty: Boolean get() = !isLoading && cards.isEmpty()
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mediaRepository: MediaRepository,
    private val progressRepository: ProgressRepository,
    private val myListRepository: MyListRepository,
) : ViewModel() {

    private val mode = MutableStateFlow(
        savedStateHandle.get<String>(ARG_MODE)?.let { runCatching { LibraryMode.valueOf(it) }.getOrNull() }
            ?: LibraryMode.MOVIES,
    )
    private val query = MutableStateFlow(LibraryQuery())
    private val genres = MutableStateFlow<List<String>>(emptyList())
    private val loading = MutableStateFlow(true)
    private val errorMessage = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val cards = combine(mode, query) { currentMode, currentQuery ->
        currentMode to currentQuery
    }.flatMapLatest { (currentMode, effectiveQuery) ->
        val source = mediaRepository.observeLibrary(effectiveQuery)
        if (currentMode == LibraryMode.MY_LIST) {
            combine(source, myListRepository.itemIds) { items, myList ->
                items.filter { it.item.id in myList }
            }
        } else {
            source
        }
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        mode,
        cards,
        genres,
        query,
        combine(loading, errorMessage, mediaRepository.observeCatalogue()) { isLoading, error, catalogue ->
            Triple(isLoading, error, catalogue.version)
        },
    ) { currentMode, items, genreList, currentQuery, (isLoading, error, catalogueVersion) ->
        LibraryUiState(
            mode = currentMode,
            cards = items,
            genres = genreList,
            query = currentQuery,
            isLoading = isLoading && items.isEmpty(),
            errorMessage = error,
            catalogueVersion = catalogueVersion,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryUiState())

    init {
        // Genres come from the catalogue in use, so they are read again whenever a newer one is
        // swapped in; a genre chip must never offer a genre the grid no longer has.
        viewModelScope.launch {
            mediaRepository.observeCatalogue()
                .distinctUntilChangedBy { it.generation }
                .collect { genres.value = mediaRepository.genres() }
        }
        refresh()
    }

    fun setMode(newMode: LibraryMode) {
        mode.value = newMode
    }

    fun setSort(sort: LibrarySort) {
        query.value = query.value.copy(sort = sort)
    }

    fun setGenre(genre: String?) {
        query.value = query.value.copy(genre = genre)
    }

    fun setWatchedFilter(filter: WatchedFilter) {
        query.value = query.value.copy(watched = filter)
    }

    fun toggleMyList(card: MediaCard) {
        viewModelScope.launch { myListRepository.toggle(card.item.id) }
    }

    fun markWatched(card: MediaCard, watched: Boolean) {
        viewModelScope.launch {
            progressRepository.markWatched(card.playableId, card.item.runtimeMs, watched)
        }
    }

    /**
     * The catalogue is local (bundled, or already downloaded), so "refresh" only re-reads the genre
     * list. Newer catalogues arrive on their own and every flow above follows them.
     */
    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            genres.value = mediaRepository.genres()
            errorMessage.value = null
            loading.value = false
        }
    }

    companion object {
        const val ARG_MODE = "mode"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
