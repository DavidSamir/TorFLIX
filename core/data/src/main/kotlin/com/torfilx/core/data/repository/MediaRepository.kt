package com.torfilx.core.data.repository

import com.torfilx.core.common.di.Dispatcher
import com.torfilx.core.common.di.TorfilxDispatcher
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.common.time.TimeProvider
import com.torfilx.core.data.catalog.Catalog
import com.torfilx.core.data.catalog.CatalogSnapshot
import com.torfilx.core.data.catalog.CatalogueInfo
import com.torfilx.core.data.catalog.Playable
import com.torfilx.core.data.database.SearchHistoryDao
import com.torfilx.core.model.HeroItem
import com.torfilx.core.model.HomeRow
import com.torfilx.core.model.HomeRowKind
import com.torfilx.core.model.LibraryQuery
import com.torfilx.core.model.LibrarySort
import com.torfilx.core.model.MediaCard
import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.MediaKind
import com.torfilx.core.model.PlayAction
import com.torfilx.core.model.PlayActionResolver
import com.torfilx.core.model.PlaybackProgress
import com.torfilx.core.model.SearchResult
import com.torfilx.core.model.Season
import com.torfilx.core.model.ShowPlayRules
import com.torfilx.core.model.ShowWatchedRules
import com.torfilx.core.model.WatchedFilter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The library.
 *
 * One source of titles, the catalogue in use (bundled, or a newer signed release from the peer
 * network), merged with what is dynamic and lives in Room: playback progress and My List.
 *
 * Everything derived purely from the catalogue (sort orders, genre groupings) is computed **once per
 * catalogue** and cached. Only the cheap decoration step re-runs when progress changes, because progress
 * changes every ten seconds during playback and a Fire TV Stick cannot afford to rebuild thousands of
 * cards each time. When a new catalogue is swapped in, its generation changes, every flow below
 * re-emits, and the views are rebuilt once for it.
 *
 * Films and shows share every list. A show's card never carries progress of its own — a show is not
 * played, its episodes are — but it does carry whether the whole show is watched, which is what its
 * badge and the library's watched filter need.
 */
@Singleton
class MediaRepository @Inject constructor(
    private val catalog: Catalog,
    private val searchHistoryDao: SearchHistoryDao,
    private val progressRepository: ProgressRepository,
    private val myListRepository: MyListRepository,
    private val timeProvider: TimeProvider,
    @Dispatcher(TorfilxDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /** Catalogue-derived structure, independent of local state, for one catalogue generation. */
    private class CatalogViews(
        val generation: Int,
        val byRecent: List<MediaItem>,
        val byTitle: List<MediaItem>,
        val byYear: List<MediaItem>,
        val byRating: List<MediaItem>,
        /** Shows only, newest first, for the Shows row. */
        val shows: List<MediaItem>,
        val genreRows: List<Pair<String, List<MediaItem>>>,
        /** Real size of each genre before the row cap, so a row can say it is a preview. */
        val genreTotals: Map<String, Int>,
        /** Which kinds each genre holds, so a capped row says where the rest can be browsed. */
        val genreKinds: Map<String, Set<MediaKind>>,
        val recentKinds: Set<MediaKind>,
    )

    @Volatile
    private var views: CatalogViews? = null

    /**
     * Catalogue-derived views for the catalogue in use, built once per generation.
     *
     * Never cached from a catalogue the catalogue itself would not keep (empty or incomplete): views
     * cached from a short read would preserve a broken library even after the catalogue recovered.
     */
    private fun views(): CatalogViews {
        val snapshot = catalog.snapshot()
        views?.takeIf { it.generation == snapshot.info.generation && snapshot.isKept() }?.let { return it }
        return synchronized(this) {
            views?.takeIf { it.generation == snapshot.info.generation && snapshot.isKept() }
                ?: buildViews(snapshot).also { built -> if (snapshot.isKept()) views = built }
        }
    }

    private fun CatalogSnapshot.isKept(): Boolean = items.isNotEmpty() && !isIncomplete

    private fun buildViews(snapshot: CatalogSnapshot): CatalogViews {
        val items = snapshot.mediaItems
        val byGenre = LinkedHashMap<String, MutableList<MediaItem>>()
        items.forEach { item ->
            item.genres.forEach { genre -> byGenre.getOrPut(genre) { ArrayList() }.add(item) }
        }
        val byRecent = items.sortedByDescending { it.addedAtMs ?: it.year?.toLong() ?: 0L }
        return CatalogViews(
            generation = snapshot.info.generation,
            byRecent = byRecent,
            byTitle = items.sortedBy { it.sortTitle.lowercase() },
            byYear = items.sortedByDescending { it.year ?: 0 },
            byRating = items.sortedByDescending { it.communityRating ?: 0.0 },
            shows = byRecent.filter { it.isShow },
            genreRows = byGenre
                .filterValues { it.size >= MIN_GENRE_ROW_SIZE }
                .toList()
                .sortedBy { it.first }
                // distinctBy is belt-and-braces: a row must never contain the same title twice.
                .map { (genre, list) -> genre to list.distinctBy { item -> item.id }.take(MAX_ROW_ITEMS) },
            genreTotals = byGenre.mapValues { (_, list) -> list.distinctBy { item -> item.id }.size },
            genreKinds = byGenre.mapValues { (_, list) -> list.mapTo(HashSet()) { it.kind } },
            recentKinds = items.mapTo(HashSet()) { it.kind },
        )
    }

    /** Cheap decoration: wraps one pre-sorted item with the local state that actually changed. */
    private fun MediaItem.toCard(progress: Map<String, PlaybackProgress>, myList: Set<String>): MediaCard =
        if (isShow) {
            MediaCard(
                item = this,
                inMyList = id in myList,
                isWatched = ShowWatchedRules.isWatched(catalog.seasons(id), progress),
            )
        } else {
            MediaCard(item = this, progress = progress[id], inMyList = id in myList)
        }

    private fun List<MediaItem>.toCards(
        progress: Map<String, PlaybackProgress>,
        myList: Set<String>,
    ): List<MediaCard> = map { it.toCard(progress, myList) }

    // --- Catalogue -------------------------------------------------------------------------------

    /** Which catalogue is in use; changes when a newer release is swapped in. */
    fun observeCatalogue(): StateFlow<CatalogueInfo> = catalog.info

    // --- Library ---------------------------------------------------------------------------------

    fun observeLibrary(query: LibraryQuery): Flow<List<MediaCard>> = combine(
        catalog.info,
        progressRepository.observeAllProgress(),
        myListRepository.itemIds,
    ) { _, progress, myList ->
        val data = views()
        val sorted = when (query.sort) {
            LibrarySort.RECENTLY_ADDED -> data.byRecent
            LibrarySort.ALPHABETICAL -> data.byTitle
            LibrarySort.YEAR -> data.byYear
            LibrarySort.RATING -> data.byRating
        }
        sorted.asSequence()
            .filter { item -> query.kind == null || item.kind == query.kind }
            .filter { item -> query.genre == null || query.genre in item.genres }
            .map { item -> item.toCard(progress, myList) }
            .filter { card -> query.watched.matches(card) }
            .toList()
    }.flowOn(ioDispatcher)

    fun observeItemCount(): Flow<Int> = catalog.info
        .map { catalog.mediaItems().size }
        .distinctUntilChanged()
        .flowOn(ioDispatcher)

    /** Titles the catalogue declares. Differs from the loaded count only when a read failed. */
    fun declaredItemCount(): Int = catalog.declaredTitleCount()

    /** Every genre, or only those that [kind] has, so a Shows grid never offers a films-only genre. */
    suspend fun genres(kind: MediaKind? = null): List<String> = withContext(ioDispatcher) {
        if (kind == null) {
            catalog.genres()
        } else {
            catalog.mediaItems().asSequence().filter { it.kind == kind }.flatMap { it.genres }.distinct().sorted().toList()
        }
    }

    // --- Home ------------------------------------------------------------------------------------

    /**
     * Home rows: Continue Watching, TV shows, the catalogue, My List, then a row per genre.
     *
     * Rows are capped at [MAX_ROW_ITEMS]: a row nobody can reach the end of with a D-pad costs memory
     * and scroll performance for nothing. The Movies and Shows grids are where the whole catalogue is
     * browsed, and a capped row's header says which of them holds the rest.
     */
    fun observeHome(): Flow<List<HomeRow>> = combine(
        catalog.info,
        progressRepository.observeContinueWatching(),
        progressRepository.observeAllProgress(),
        myListRepository.itemIds,
    ) { _, continueWatching, progress, myListIds ->
        val data = views()

        buildList {
            if (continueWatching.isNotEmpty()) {
                add(
                    HomeRow(
                        id = ROW_CONTINUE_WATCHING,
                        title = "Continue Watching",
                        kind = HomeRowKind.CONTINUE_WATCHING,
                        items = continueWatching,
                    ),
                )
            }

            if (data.shows.isNotEmpty()) {
                add(
                    HomeRow(
                        id = ROW_SHOWS,
                        title = "TV shows",
                        kind = HomeRowKind.SHOWS,
                        items = data.shows.take(MAX_ROW_ITEMS).toCards(progress, myListIds),
                        totalItems = data.shows.size,
                        seeAllIn = seeAllIn(setOf(MediaKind.SHOW)),
                    ),
                )
            }

            val recent = data.byRecent.take(MAX_ROW_ITEMS).toCards(progress, myListIds)
            if (recent.isNotEmpty()) {
                add(
                    HomeRow(
                        id = ROW_CATALOG,
                        title = "Recently added",
                        items = recent,
                        totalItems = data.byRecent.size,
                        seeAllIn = seeAllIn(data.recentKinds),
                    ),
                )
            }

            if (myListIds.isNotEmpty()) {
                val myListCards = data.byRecent
                    .asSequence()
                    .filter { it.id in myListIds }
                    .take(MAX_ROW_ITEMS)
                    .map { it.toCard(progress, myListIds) }
                    .toList()
                if (myListCards.isNotEmpty()) {
                    add(
                        HomeRow(
                            id = ROW_MY_LIST,
                            title = "My List",
                            kind = HomeRowKind.MY_LIST,
                            items = myListCards,
                        ),
                    )
                }
            }

            data.genreRows.take(MAX_GENRE_ROWS).forEach { (genre, items) ->
                add(
                    HomeRow(
                        id = "genre-$genre",
                        title = genre,
                        kind = HomeRowKind.GENRE,
                        totalItems = data.genreTotals[genre] ?: items.size,
                        items = items.toCards(progress, myListIds),
                        seeAllIn = seeAllIn(data.genreKinds[genre].orEmpty()),
                    ),
                )
            }
        }
    }.flowOn(ioDispatcher)

    private fun seeAllIn(kinds: Set<MediaKind>): String = when {
        MediaKind.SHOW in kinds && MediaKind.MOVIE in kinds -> "Movies and Shows"
        MediaKind.SHOW in kinds -> "Shows"
        else -> "Movies"
    }

    // --- Playing from a card ---------------------------------------------------------------------

    /**
     * What pressing Play on [card] does, given every progress row.
     *
     * A film or an episode card plays what it names. A plain show card plays the show's next-up
     * episode ([ShowPlayRules.nextUp]). Anything with no usable source is [PlayAction.Unavailable],
     * which the screens turn into "open the details" rather than a player that can only fail.
     */
    fun actionFor(card: MediaCard, progress: Map<String, PlaybackProgress>): PlayAction {
        val episode = card.episode
        return when {
            episode != null -> PlayActionResolver.actionForPlayable(
                episode.id,
                progress[episode.id],
                hasPlayableSource = catalog.sourcesFor(episode.id).isNotEmpty(),
            )
            card.item.isShow -> PlayActionResolver.actionForShow(catalog.seasons(card.item.id), progress)
            else -> PlayActionResolver.actionForPlayable(
                card.item.id,
                progress[card.item.id],
                hasPlayableSource = catalog.sourcesFor(card.item.id).isNotEmpty(),
            )
        }
    }

    /** [actionFor], reading progress now. */
    suspend fun playAction(card: MediaCard): PlayAction = withContext(ioDispatcher) {
        actionFor(card, progressRepository.currentProgressMap())
    }

    /**
     * A card ready for the hero: a plain show card gains its next-up episode, so the hero's button
     * can say "Resume S2 E3" and play exactly that. Films and episode cards pass through.
     */
    fun heroItem(card: MediaCard, progress: Map<String, PlaybackProgress>): HeroItem {
        val resolved = if (card.item.isShow && card.episode == null) {
            ShowPlayRules.nextUp(catalog.seasons(card.item.id), progress)
                ?.let { next -> card.copy(episode = next, progress = progress[next.id]) }
                ?: card
        } else {
            card
        }
        return HeroItem(card = resolved, action = actionFor(resolved, progress))
    }

    // --- Details ---------------------------------------------------------------------------------

    /** The item, re-emitted if a newer catalogue changes it (or drops it). */
    fun observeItem(id: String): Flow<MediaItem?> = catalog.info
        .map { catalog.item(id)?.item }
        .distinctUntilChanged()
        .flowOn(ioDispatcher)

    /** A show's seasons, re-emitted with the item whenever a newer catalogue is swapped in. */
    fun observeSeasons(showId: String): Flow<List<Season>> = catalog.info
        .map { catalog.seasons(showId) }
        .distinctUntilChanged()
        .flowOn(ioDispatcher)

    suspend fun item(id: String): MediaItem? = withContext(ioDispatcher) { catalog.item(id)?.item }

    /** A film or an episode by id; null for a show's own id or anything not in the catalogue. */
    suspend fun playable(id: String): Playable? = withContext(ioDispatcher) { catalog.playable(id) }

    // --- Search ----------------------------------------------------------------------------------

    /**
     * Titles first — prefix matches, then titles containing the query — then shows found by an
     * episode's name, each card carrying that episode so the result says which one matched. Every
     * result opens the title's details.
     */
    suspend fun search(query: String): List<SearchResult> = withContext(ioDispatcher) {
        val startedAt = System.nanoTime()
        val matches = catalog.search(query, SEARCH_LIMIT)
        val byEpisode = catalog.searchEpisodes(query, SEARCH_LIMIT - matches.size, excluding = matches.mapTo(HashSet()) { it.id })
        if (matches.isEmpty() && byEpisode.isEmpty()) return@withContext emptyList()

        val myListIds = myListRepository.itemIds.first()
        val progress = progressRepository.currentProgressMap()
        val titles = matches.map { item ->
            SearchResult(card = item.toCard(progress, myListIds), matchedOn = "title")
        }
        val episodes = byEpisode.map { match ->
            SearchResult(card = match.show.toCard(progress, myListIds).copy(episode = match.episode), matchedOn = "episode")
        }
        (titles + episodes).also {
            // A slow search on a TV is felt immediately: the results grid lags behind the keyboard.
            // Logged when it happens so it is visible on the device, not only in a benchmark.
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            if (elapsedMs >= SLOW_SEARCH_MS) {
                TorfilxLog.w(TAG, "Search \"$query\" took ${elapsedMs}ms (${it.size} results)")
            } else {
                TorfilxLog.i(TAG, "Search \"$query\": ${it.size} results in ${elapsedMs}ms")
            }
        }
    }

    fun recentSearches(limit: Int = RECENT_SEARCH_LIMIT): Flow<List<String>> =
        searchHistoryDao.observeRecent(limit).map { entries -> entries.map { it.query } }

    suspend fun recordSearch(query: String) = withContext(ioDispatcher) {
        if (query.isNotBlank()) {
            searchHistoryDao.record(query.trim(), timeProvider.writeTimestampMs(), RECENT_SEARCH_LIMIT)
        }
    }

    suspend fun clearSearchHistory() = withContext(ioDispatcher) { searchHistoryDao.clear() }

    /** The same rule for films and shows: a card's own watched state, which each kind sets. */
    private fun WatchedFilter.matches(card: MediaCard): Boolean = when (this) {
        WatchedFilter.ALL -> true
        WatchedFilter.WATCHED -> card.isWatched
        WatchedFilter.UNWATCHED -> !card.isWatched
    }

    companion object {
        const val ROW_CONTINUE_WATCHING = "continue-watching"
        const val ROW_MY_LIST = "my-list"
        const val ROW_CATALOG = "catalog"
        const val ROW_SHOWS = "shows"

        /**
         * How many search results are returned.
         *
         * This was 60, which quietly truncated any broad query: searching a common word in a
         * 2000-title catalogue returned the first 60 matches and nothing said so. The cap exists only
         * to bound the work done per keystroke on a very slow CPU, and 500 titles of card wrappers is
         * well within that; the results grid is lazy, so nothing beyond the visible rows is laid out.
         */
        const val SEARCH_LIMIT = 500
        const val RECENT_SEARCH_LIMIT = 10
        private const val SLOW_SEARCH_MS = 50L
        private const val TAG = "MediaRepo"

        /** A TV row longer than this is unreachable by D-pad and only costs memory. */
        private const val MAX_ROW_ITEMS = 60

        /**
         * Every genre gets a row.
         *
         * This was 12, which silently hid nine of the catalogue's genres and a large slice of the
         * library behind them. The rows are lazy, so the cost of the extra ones is a list of card
         * wrappers, not layout.
         */
        private const val MAX_GENRE_ROWS = 32
        private const val MIN_GENRE_ROW_SIZE = 2
    }
}
