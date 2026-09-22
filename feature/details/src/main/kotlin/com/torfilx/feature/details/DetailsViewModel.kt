package com.torfilx.feature.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torfilx.core.common.di.Dispatcher
import com.torfilx.core.common.di.TorfilxDispatcher
import com.torfilx.core.data.catalog.Catalog
import com.torfilx.core.data.catalog.Playable
import com.torfilx.core.data.repository.MediaRepository
import com.torfilx.core.data.repository.MyListRepository
import com.torfilx.core.data.repository.ProgressRepository
import com.torfilx.core.data.settings.SettingsRepository
import com.torfilx.core.data.torrent.TorrentCoordinator
import com.torfilx.core.model.Episode
import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.MediaSource
import com.torfilx.core.model.PlayAction
import com.torfilx.core.model.PlayActionResolver
import com.torfilx.core.model.PlaybackProgress
import com.torfilx.core.model.ResumeRules
import com.torfilx.core.model.Season
import com.torfilx.core.model.ShowPlayRules
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DetailsUiState {
    data object Loading : DetailsUiState

    data class Content(
        val item: MediaItem,
        /** A film's own progress; always null for a show, whose progress lives on its episodes. */
        val progress: PlaybackProgress?,
        val inMyList: Boolean,
        val primaryAction: PlayAction,
        /** A film's sources, one button each. Empty for a show. */
        val sources: List<MediaSource> = emptyList(),
        /** Set for a show. */
        val show: ShowDetails? = null,
    ) : DetailsUiState

    data class Error(val message: String) : DetailsUiState
}

/** Everything the show half of the details screen draws. */
data class ShowDetails(
    val seasons: List<Season>,
    /** The season whose episodes are listed. */
    val selectedSeason: Season?,
    /** What the primary button plays. */
    val nextUp: Episode?,
    /** Progress for this show's episodes only. */
    val episodeProgress: Map<String, PlaybackProgress>,
) {
    fun isWatched(episode: Episode): Boolean = episodeProgress[episode.id]?.let(ResumeRules::isWatched) == true
}

/** The menu raised by the remote's Menu key on an episode or a season chip. */
sealed interface DetailsMenu {
    data class ForEpisode(val episode: Episode, val sources: List<MediaSource>, val watched: Boolean) : DetailsMenu
    data class ForSeason(val season: Season, val watched: Boolean) : DetailsMenu
}

/** A play the consent dialog is holding until the viewer answers it. */
data class PendingPlay(val playableId: String, val sourceId: String?)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mediaRepository: MediaRepository,
    private val progressRepository: ProgressRepository,
    private val myListRepository: MyListRepository,
    private val catalog: Catalog,
    private val settingsRepository: SettingsRepository,
    torrentCoordinator: TorrentCoordinator,
    @Dispatcher(TorfilxDispatcher.IO) ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val requestedId: String = requireNotNull(savedStateHandle.get<String>(ARG_ITEM_ID)) {
        "Details opened without an item id"
    }

    val torrentAvailable: Boolean = torrentCoordinator.isAvailable()

    /**
     * The title this screen shows. An episode's id — which nothing produces today, but a route saved by
     * a later build might — opens its show. Followed per catalogue, so a swap that moves an episode to
     * another show (or drops it) is honoured.
     */
    private val itemId: StateFlow<String> = catalog.info
        .map { resolveItemId(requestedId) }
        .distinctUntilChanged()
        .flowOn(ioDispatcher)
        .stateIn(viewModelScope, SharingStarted.Eagerly, requestedId)

    private fun resolveItemId(id: String): String =
        (catalog.playable(id) as? Playable.EpisodeOf)?.show?.id ?: id

    private val selectedSeason = MutableStateFlow<Int?>(null)

    val uiState: StateFlow<DetailsUiState> = itemId
        .flatMapLatest { id -> content(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DetailsUiState.Loading)

    private fun content(id: String): Flow<DetailsUiState> = combine(
        mediaRepository.observeItem(id),
        mediaRepository.observeSeasons(id),
        progressRepository.observeAllProgress(),
        myListRepository.itemIds,
        selectedSeason,
    ) { item, seasons, progress, myList, chosenSeason ->
        when {
            item == null -> DetailsUiState.Error("This title is not in the catalogue.")
            item.isShow -> {
                val episodeIds = seasons.flatMapTo(HashSet()) { season -> season.episodes.map { it.id } }
                val nextUp = ShowPlayRules.nextUp(seasons, progress)
                val selected = seasons.firstOrNull { it.number == chosenSeason }
                    ?: nextUp?.let { next -> seasons.firstOrNull { it.number == next.season } }
                    ?: seasons.firstOrNull()
                DetailsUiState.Content(
                    item = item,
                    progress = null,
                    inMyList = item.id in myList,
                    primaryAction = PlayActionResolver.actionForShow(seasons, progress),
                    show = ShowDetails(
                        seasons = seasons,
                        selectedSeason = selected,
                        nextUp = nextUp,
                        episodeProgress = progress.filterKeys { it in episodeIds },
                    ),
                )
            }
            else -> {
                val sources = catalog.sourcesFor(item.id)
                DetailsUiState.Content(
                    item = item,
                    progress = progress[item.id],
                    inMyList = item.id in myList,
                    primaryAction = PlayActionResolver.actionFor(item, progress[item.id], sources.isNotEmpty()),
                    sources = sources,
                )
            }
        }
    }

    // --- Playing ---------------------------------------------------------------------------------

    private val _pendingPlay = MutableStateFlow<PendingPlay?>(null)

    /** Non-null while the consent dialog is asking about this play. */
    val pendingPlay: StateFlow<PendingPlay?> = _pendingPlay.asStateFlow()

    /** The episode last started from its row, so focus can go back to it on return from the player. */
    var lastPlayedEpisodeId: String? = null
        private set

    /**
     * Plays [playableId] now if the viewer has already consented to sharing, otherwise raises the
     * consent dialog. The consent flag is read from storage at the moment of the tap rather than
     * from a screen-scoped cache, so an already-consented viewer is never asked again — the earlier
     * cache started at `false` and lagged, which is why the dialog kept reappearing.
     *
     * @param sourceId a quality the viewer chose; null lets the player pick.
     */
    fun requestPlayback(playableId: String, sourceId: String?, onReady: (PendingPlay) -> Unit) {
        val play = PendingPlay(playableId, sourceId)
        viewModelScope.launch {
            if (settingsRepository.sharingConsent.first()) onReady(play) else _pendingPlay.value = play
        }
    }

    /** An episode from its row: remembered so focus returns to it after the player. */
    fun requestEpisode(episode: Episode, sourceId: String?, onReady: (PendingPlay) -> Unit) {
        lastPlayedEpisodeId = episode.id
        requestPlayback(episode.id, sourceId, onReady)
    }

    /** The primary button: focus returns to it, not to a row, after the player. */
    fun requestPrimary(onReady: (PendingPlay) -> Unit) {
        lastPlayedEpisodeId = null
        when (val action = (uiState.value as? DetailsUiState.Content)?.primaryAction) {
            is PlayAction.Play -> requestPlayback(action.itemId, null, onReady)
            is PlayAction.Resume -> requestPlayback(action.itemId, null, onReady)
            PlayAction.Unavailable, null -> Unit
        }
    }

    fun onConsentAccepted(onReady: (PendingPlay) -> Unit) {
        val pending = _pendingPlay.value
        viewModelScope.launch {
            settingsRepository.setSharingConsent(true)
            _pendingPlay.value = null
            pending?.let(onReady)
        }
    }

    fun onConsentDeclined() {
        viewModelScope.launch {
            settingsRepository.setSharingConsent(false)
            _pendingPlay.value = null
        }
    }

    // --- Show ------------------------------------------------------------------------------------

    fun selectSeason(season: Season) {
        selectedSeason.value = season.number
    }

    private val _menu = MutableStateFlow<DetailsMenu?>(null)
    val menu: StateFlow<DetailsMenu?> = _menu.asStateFlow()

    fun openEpisodeMenu(episode: Episode) {
        val show = (uiState.value as? DetailsUiState.Content)?.show ?: return
        _menu.value = DetailsMenu.ForEpisode(episode, catalog.sourcesFor(episode.id), show.isWatched(episode))
    }

    fun openSeasonMenu(season: Season) {
        val show = (uiState.value as? DetailsUiState.Content)?.show ?: return
        _menu.value = DetailsMenu.ForSeason(season, season.episodes.isNotEmpty() && season.episodes.all(show::isWatched))
    }

    fun dismissMenu() {
        _menu.value = null
    }

    fun markEpisodeWatched(episode: Episode, watched: Boolean) {
        _menu.value = null
        viewModelScope.launch { progressRepository.markWatched(episode.id, episode.runtimeMs, watched) }
    }

    fun markSeasonWatched(season: Season, watched: Boolean) {
        _menu.value = null
        viewModelScope.launch { progressRepository.markSeasonWatched(itemId.value, season.number, watched) }
    }

    // --- Both ------------------------------------------------------------------------------------

    fun toggleMyList() {
        viewModelScope.launch { myListRepository.toggle(itemId.value) }
    }

    /** A film's "Mark watched" button. */
    fun markWatched(watched: Boolean, runtimeMs: Long?) {
        viewModelScope.launch { progressRepository.markWatched(itemId.value, runtimeMs, watched) }
    }

    companion object {
        const val ARG_ITEM_ID = "itemId"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
