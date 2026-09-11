package com.torfilx.feature.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torfilx.core.data.catalog.Catalog
import com.torfilx.core.data.repository.MediaRepository
import com.torfilx.core.data.repository.MyListRepository
import com.torfilx.core.data.repository.ProgressRepository
import com.torfilx.core.data.settings.SettingsRepository
import com.torfilx.core.data.torrent.TorrentCoordinator
import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.MediaSource
import com.torfilx.core.model.PlayAction
import com.torfilx.core.model.PlayActionResolver
import com.torfilx.core.model.PlaybackProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DetailsUiState {
    data object Loading : DetailsUiState

    data class Content(
        val item: MediaItem,
        val progress: PlaybackProgress?,
        val inMyList: Boolean,
        val primaryAction: PlayAction,
    ) : DetailsUiState

    data class Error(val message: String) : DetailsUiState
}

@HiltViewModel
class DetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    mediaRepository: MediaRepository,
    private val progressRepository: ProgressRepository,
    private val myListRepository: MyListRepository,
    catalog: Catalog,
    private val settingsRepository: SettingsRepository,
    torrentCoordinator: TorrentCoordinator,
) : ViewModel() {

    private val itemId: String = requireNotNull(savedStateHandle.get<String>(ARG_ITEM_ID)) {
        "Details opened without an item id"
    }

    /** The item this screen is showing; used when starting playback of a chosen source. */
    val itemIdForPlayback: String = itemId

    /** Every quality this film is available in. Local, so showing them needs no network call. */
    val torrentSources: List<MediaSource> = catalog.sourcesFor(itemId)

    val torrentAvailable: Boolean = torrentCoordinator.isAvailable()

    val uiState: StateFlow<DetailsUiState> = combine(
        mediaRepository.observeItem(itemId),
        progressRepository.observeAllProgress(),
        myListRepository.itemIds,
    ) { item, progress, myList ->
        when (item) {
            null -> DetailsUiState.Error("This title is not in the catalogue.")
            else -> DetailsUiState.Content(
                item = item,
                progress = progress[item.id],
                inMyList = item.id in myList,
                primaryAction = PlayActionResolver.actionFor(
                    item = item,
                    progress = progress[item.id],
                    hasPlayableSource = torrentSources.isNotEmpty(),
                ),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DetailsUiState.Loading)

    private val _pendingTorrentSource = MutableStateFlow<MediaSource?>(null)

    /** Non-null while the consent dialog is asking about this source. */
    val pendingTorrentSource: StateFlow<MediaSource?> = _pendingTorrentSource

    /**
     * Plays the source now if the viewer has already consented to sharing, otherwise raises the
     * consent dialog. The consent flag is read from storage at the moment of the tap rather than
     * from a screen-scoped cache, so an already-consented viewer is never asked again — the earlier
     * cache started at `false` and lagged, which is why the dialog kept reappearing.
     */
    fun requestTorrentPlayback(source: MediaSource, onReady: (MediaSource) -> Unit) {
        viewModelScope.launch {
            if (settingsRepository.sharingConsent.first()) {
                onReady(source)
            } else {
                _pendingTorrentSource.value = source
            }
        }
    }

    fun onConsentAccepted(onReady: (MediaSource) -> Unit) {
        val pending = _pendingTorrentSource.value
        viewModelScope.launch {
            settingsRepository.setSharingConsent(true)
            _pendingTorrentSource.value = null
            pending?.let(onReady)
        }
    }

    fun onConsentDeclined() {
        viewModelScope.launch {
            settingsRepository.setSharingConsent(false)
            _pendingTorrentSource.value = null
        }
    }

    fun toggleMyList() {
        viewModelScope.launch { myListRepository.toggle(itemId) }
    }

    fun markWatched(watched: Boolean, runtimeMs: Long?) {
        viewModelScope.launch { progressRepository.markWatched(itemId, runtimeMs, watched) }
    }

    companion object {
        const val ARG_ITEM_ID = "itemId"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
