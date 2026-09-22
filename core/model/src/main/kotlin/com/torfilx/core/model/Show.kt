package com.torfilx.core.model

/** Whether a catalogue title is a film, played as one thing, or a show made of episodes. */
enum class MediaKind { MOVIE, SHOW }

/**
 * One season of a show, its episodes in order.
 *
 * Season 0 is "Specials": listed last, playable by hand, and never chained into by next-up or
 * autoplay, because a special dropped between two episodes interrupts the story.
 */
data class Season(
    val number: Int,
    val name: String,
    val image: String? = null,
    val episodes: List<Episode> = emptyList(),
) {
    val isSpecials: Boolean get() = number == SPECIALS

    companion object {
        const val SPECIALS = 0

        fun defaultName(number: Int): String = if (number == SPECIALS) "Specials" else "Season $number"
    }
}

/**
 * One episode. [id] is what watch progress is stored under, exactly as a film's id is.
 *
 * Numbers are for display and ordering only. Once the publisher pins an id it never changes, so an
 * id may no longer describe the episode's position after a renumbering, and nothing reads one out
 * of it.
 */
data class Episode(
    val id: String,
    val showId: String,
    val season: Int,
    val number: Int,
    val name: String? = null,
    val overview: String? = null,
    val runtimeMs: Long? = null,
    val airDateMs: Long? = null,
    val image: String? = null,
    /** False when the catalogue offers no source this app can use for it. */
    val isPlayable: Boolean = true,
) {
    /** `S1 E3`: the form every label uses. */
    val code: String get() = "S$season E$number"

    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "Episode $number"

    val isSpecial: Boolean get() = season == Season.SPECIALS
}

/**
 * Which episode of a show to play next, and which follows which.
 *
 * Pure, so the rules are unit-tested without Android. Every function takes the show's seasons and a
 * map of progress keyed by id; rows for ids that are not episodes of this show are simply never
 * looked up, so a stray row cannot confuse them.
 */
object ShowPlayRules {

    /** Regular episodes in viewing order: seasons ascending, episodes ascending, specials left out. */
    fun viewingOrder(seasons: List<Season>): List<Episode> = seasons
        .filterNot { it.isSpecials }
        .sortedBy { it.number }
        .flatMap { season -> season.episodes.sortedBy { it.number } }

    /** Every episode, specials last: the order the details screen lists them in. */
    fun allEpisodes(seasons: List<Season>): List<Episode> =
        viewingOrder(seasons) + seasons.filter { it.isSpecials }.flatMap { s -> s.episodes.sortedBy { it.number } }

    /**
     * The episode the show's primary button plays.
     *
     * 1. The most recent thing the viewer did decides. If that was leaving an episode part-way, it is
     *    resumed (a special included — they chose it).
     * 2. If it was finishing one, the first unwatched, playable episode after it; failing that, the
     *    first unwatched, playable episode from the start (they skipped ahead earlier).
     * 3. When every regular episode is watched, the first playable one, to watch again.
     * 4. When nothing has been watched, the first playable regular episode.
     * 5. A show made only of specials starts with its first playable special.
     *
     * Unplayable episodes are never chosen: the button must always play something. Null only when
     * nothing in the show can be played at all.
     */
    fun nextUp(seasons: List<Season>, progress: Map<String, PlaybackProgress>): Episode? {
        val ordered = viewingOrder(seasons)
        val everything = allEpisodes(seasons)
        if (everything.none { it.isPlayable }) return null

        val watched = { episode: Episode -> progress[episode.id]?.let(ResumeRules::isWatched) == true }

        // Ties (a whole season marked watched in one go shares one timestamp) go to the episode
        // latest in viewing order, so "the most recent" is never an arbitrary pick.
        val inProgress = everything.withIndex()
            .filter { (_, episode) -> episode.isPlayable && ResumeRules.isInProgress(progress[episode.id]) }
            .maxWithOrNull(compareBy({ progress.getValue(it.value.id).updatedAtMs }, { it.index }))
            ?.value
        val lastFinished = lastFinished(seasons, progress)

        if (inProgress != null &&
            (lastFinished == null || progress.getValue(inProgress.id).updatedAtMs >= progress.getValue(lastFinished.id).updatedAtMs)
        ) {
            return inProgress
        }

        if (lastFinished != null) {
            val after = ordered.drop(ordered.indexOf(lastFinished) + 1)
            after.firstOrNull { it.isPlayable && !watched(it) }?.let { return it }
            ordered.firstOrNull { it.isPlayable && !watched(it) }?.let { return it }
            // Everything that can be played has been watched: start again.
            return ordered.firstOrNull { it.isPlayable } ?: everything.first { it.isPlayable }
        }

        return ordered.firstOrNull { it.isPlayable && !watched(it) }
            ?: ordered.firstOrNull { it.isPlayable }
            ?: everything.first { it.isPlayable }
    }

    /**
     * The episode after [episodeId] in viewing order, crossing season boundaries.
     *
     * Null at the end of the show, for a special (specials are never chained), and for an id that
     * is not in the show. It does **not** skip an episode that cannot be played: the player says so
     * rather than silently jumping past part of the story.
     */
    fun nextAfter(seasons: List<Season>, episodeId: String): Episode? {
        val ordered = viewingOrder(seasons)
        val index = ordered.indexOfFirst { it.id == episodeId }
        if (index < 0) return null
        return ordered.getOrNull(index + 1)
    }

    /** The episode with [episodeId], wherever it sits in the show. */
    fun find(seasons: List<Season>, episodeId: String): Episode? =
        seasons.firstNotNullOfOrNull { season -> season.episodes.firstOrNull { it.id == episodeId } }

    /**
     * The regular episode the viewer finished most recently, or null if none is watched. Specials are
     * left out: finishing one does not move the story on.
     *
     * Ties (a whole season marked watched in one go shares one timestamp) go to the episode latest in
     * viewing order, so "the most recent" is never an arbitrary pick.
     */
    fun lastFinished(seasons: List<Season>, progress: Map<String, PlaybackProgress>): Episode? =
        viewingOrder(seasons).withIndex()
            .filter { (_, episode) -> progress[episode.id]?.let(ResumeRules::isWatched) == true }
            .maxWithOrNull(compareBy({ progress.getValue(it.value.id).updatedAtMs }, { it.index }))
            ?.value

    /**
     * What a show puts in Continue Watching, if anything — one card at most, decided by the most
     * recent thing the viewer did, exactly as [nextUp] decides the show's button:
     *
     * - left an episode part-way: that episode, with its progress ([ContinueWatching.InProgress]);
     * - finished one: the next unwatched, playable episode after it, with no progress
     *   ([ContinueWatching.UpNext]) — unless nothing follows (the show is done, or only earlier
     *   episodes were skipped), in which case there is no card;
     * - never touched: no card.
     *
     * [ContinueWatching.sortKey] is when that activity happened, so the row interleaves shows and
     * films by what was watched last.
     */
    fun continueWatching(seasons: List<Season>, progress: Map<String, PlaybackProgress>): ContinueWatching? {
        val ordered = viewingOrder(seasons)
        val everything = allEpisodes(seasons)
        val watched = { episode: Episode -> progress[episode.id]?.let(ResumeRules::isWatched) == true }
        val updatedAt = { episode: Episode -> progress.getValue(episode.id).updatedAtMs }

        val inProgress = everything.withIndex()
            .filter { (_, episode) -> episode.isPlayable && ResumeRules.belongsInContinueWatching(progress[episode.id]) }
            .maxWithOrNull(compareBy({ updatedAt(it.value) }, { it.index }))
            ?.value
        val lastFinished = lastFinished(seasons, progress)

        if (inProgress != null && (lastFinished == null || updatedAt(inProgress) >= updatedAt(lastFinished))) {
            return ContinueWatching.InProgress(inProgress, updatedAt(inProgress))
        }
        lastFinished ?: return null
        val next = ordered.drop(ordered.indexOf(lastFinished) + 1).firstOrNull { it.isPlayable && !watched(it) }
            ?: return null
        return ContinueWatching.UpNext(finished = lastFinished, next = next, sortKey = updatedAt(lastFinished))
    }
}

/** A show's one card in Continue Watching. See [ShowPlayRules.continueWatching]. */
sealed interface ContinueWatching {
    val episode: Episode
    val sortKey: Long

    /** An episode left part-way: resume it. */
    data class InProgress(override val episode: Episode, override val sortKey: Long) : ContinueWatching

    /**
     * [finished] was watched to the end; [next] follows it. Dismissing this card is remembered against
     * [finished], so it comes back — for the episode after — once the viewer finishes another.
     */
    data class UpNext(val finished: Episode, val next: Episode, override val sortKey: Long) : ContinueWatching {
        override val episode: Episode get() = next
    }
}

/** When a show counts as watched, for the badge on its card and the library's watched filter. */
object ShowWatchedRules {

    /**
     * True when the show has at least one regular episode and every one of them is watched.
     * Specials do not count either way: they are extras, not the story.
     */
    fun isWatched(seasons: List<Season>, progress: Map<String, PlaybackProgress>): Boolean {
        val regular = ShowPlayRules.viewingOrder(seasons)
        return regular.isNotEmpty() && regular.all { episode ->
            progress[episode.id]?.let(ResumeRules::isWatched) == true
        }
    }

    /**
     * The library's watched filter for a show. A half-watched show is "unwatched", exactly as a
     * half-watched film is.
     */
    fun matches(filter: WatchedFilter, seasons: List<Season>, progress: Map<String, PlaybackProgress>): Boolean =
        when (filter) {
            WatchedFilter.ALL -> true
            WatchedFilter.WATCHED -> isWatched(seasons, progress)
            WatchedFilter.UNWATCHED -> !isWatched(seasons, progress)
        }
}
