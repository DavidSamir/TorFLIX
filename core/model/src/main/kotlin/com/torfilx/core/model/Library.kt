package com.torfilx.core.model

/**
 * A card as displayed in a row or grid: a title plus whatever local state decorates it.
 *
 * For a show, [episode] names the episode the card plays — set on Continue Watching and hero cards,
 * null on a plain show poster, which opens the show. [progress] is always the progress of
 * [playableId], never a show-wide figure.
 */
data class MediaCard(
    val item: MediaItem,
    val progress: PlaybackProgress? = null,
    val inMyList: Boolean = false,
    val episode: Episode? = null,
    /** A film: watched. A show: every regular episode watched (see [ShowWatchedRules]). */
    val isWatched: Boolean = progress?.watched == true,
) {
    /** What pressing Play on this card plays. Progress and watched state are stored under it. */
    val playableId: String get() = episode?.id ?: item.id

    /** The running time of what [playableId] names, for "mark watched" without playing it. */
    val runtimeMs: Long? get() = episode?.runtimeMs ?: item.runtimeMs
}

/** Home's rows. Continue Watching and My List are the two the app derives from local state. */
enum class HomeRowKind { CONTINUE_WATCHING, MY_LIST, RECENTLY_ADDED, GENRE, RECOMMENDED, GENERIC, SHOWS }

data class HomeRow(
    val id: String,
    val title: String,
    val kind: HomeRowKind = HomeRowKind.GENERIC,
    val items: List<MediaCard> = emptyList(),
    /**
     * How many titles the row *could* show, before it was capped for the D-pad.
     *
     * A home row is a preview, not the whole set — but a capped row is visually identical to a
     * complete one, so "Animation" showing 60 of 217 looked exactly like a catalogue with 60
     * animated films in it. Carrying the real total lets the header say so.
     */
    val totalItems: Int = items.size,
    /** Where the rest of a capped row can be browsed: "Movies", "Shows", or "Movies and Shows". */
    val seeAllIn: String = "Movies",
)

/** The featured items at the top of Home. */
data class HeroItem(
    val card: MediaCard,
    val action: PlayAction,
)

enum class LibrarySort {
    RECENTLY_ADDED,
    ALPHABETICAL,
    YEAR,
    RATING,
    ;

    companion object {
        val DEFAULT = RECENTLY_ADDED
    }
}

enum class WatchedFilter { ALL, WATCHED, UNWATCHED }

data class LibraryQuery(
    val genre: String? = null,
    val sort: LibrarySort = LibrarySort.DEFAULT,
    val watched: WatchedFilter = WatchedFilter.ALL,
    /** Films only, shows only, or null for both (My List). */
    val kind: MediaKind? = null,
)

data class SearchResult(
    val card: MediaCard,
    val matchedOn: String? = null,
)

