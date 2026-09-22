package com.torfilx.core.data.catalog

import com.torfilx.core.model.Episode
import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.ShowPlayRules

/**
 * One catalogue, parsed and indexed, together with the [info] describing it.
 *
 * Everything derived from the titles is computed once, here. With a few thousand titles that matters:
 * a per-id linear scan and a fresh `map { it.item }` on every flow emission turn a progress tick (every
 * ten seconds while playing) into thousands of allocations on a device with a very small CPU budget.
 *
 * Immutable, so a snapshot handed to a screen stays consistent even while a newer catalogue is being
 * swapped in behind it.
 */
class CatalogSnapshot internal constructor(
    val info: CatalogueInfo,
    val items: List<CatalogItem>,
    /** Titles the source declares, counted independently of the parser; 0 when unknown. */
    val declaredCount: Int,
) {
    /** Domain items in catalogue order, so rows never re-map the list. */
    val mediaItems: List<MediaItem> = items.map { it.item }

    private val byId: Map<String, CatalogItem> = items.associateBy { it.item.id }

    /**
     * Every episode of every show by its id, built once. Continue Watching and the player look an
     * episode up on every progress tick, so this must not be a walk through the shows.
     */
    private val episodesById: Map<String, Playable.EpisodeOf> = buildMap {
        items.forEach { show ->
            show.seasons.forEach { season ->
                season.episodes.forEach { episode ->
                    put(episode.id, Playable.EpisodeOf(show.item, episode, show.episodeSources[episode.id].orEmpty()))
                }
            }
        }
    }

    /** Titles that are shows, in catalogue order. */
    val shows: List<MediaItem> = mediaItems.filter { it.isShow }

    val genres: List<String> = mediaItems.flatMap { it.genres }.distinct().sorted()

    /** Lower-cased titles, parallel to [mediaItems], so search does no per-keystroke lowercasing. */
    private val searchKeys: List<String> = mediaItems.map { it.title.lowercase() }

    /**
     * Lower-cased names of every named episode, each with its show, in viewing order (specials last).
     * Episodes with no name are left out: "Episode 3" would match every show.
     */
    private val episodeSearchKeys: List<Pair<String, Playable.EpisodeOf>> = buildList {
        items.filter { it.item.isShow }.forEach { show ->
            ShowPlayRules.allEpisodes(show.seasons).forEach { episode ->
                val name = episode.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
                add(name.lowercase() to episodesById.getValue(episode.id))
            }
        }
    }

    /** True when fewer titles were parsed than the source declares. */
    val isIncomplete: Boolean get() = declaredCount > 0 && items.size < declaredCount

    /**
     * Only a complete, non-empty catalogue is kept. A failed or short read used to be cached like any
     * other outcome, which left the app with a broken library for the life of the process; now it is
     * read again on the next access instead.
     */
    internal val isCacheable: Boolean get() = items.isNotEmpty() && !isIncomplete

    fun item(id: String): CatalogItem? = byId[id]

    /** A film or an episode. A show's own id resolves to nothing: it is not itself playable. */
    fun playable(id: String): Playable? {
        byId[id]?.let { entry -> return if (entry.item.isShow) null else Playable.Film(entry.item, entry.sources) }
        return episodesById[id]
    }

    /** Case-insensitive title search: prefix matches first, then titles containing the query. */
    fun search(query: String, limit: Int): List<MediaItem> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val prefix = ArrayList<MediaItem>(limit)
        val contains = ArrayList<MediaItem>(limit)
        for (index in searchKeys.indices) {
            val key = searchKeys[index]
            when {
                key.startsWith(needle) -> prefix += mediaItems[index]
                key.contains(needle) -> contains += mediaItems[index]
                else -> continue
            }
            if (prefix.size >= limit) break
        }
        return (prefix + contains).take(limit)
    }

    /**
     * Shows with an episode whose name contains [query], one per show — its first such episode in
     * viewing order. Shows in [excluding] (already found by title) are left out.
     *
     * Needs [MIN_EPISODE_QUERY] characters: a letter or two is in nearly every episode name, and would
     * bury the title results under every show in the catalogue.
     */
    fun searchEpisodes(query: String, limit: Int, excluding: Set<String> = emptySet()): List<EpisodeMatch> {
        val needle = query.trim().lowercase()
        if (needle.length < MIN_EPISODE_QUERY || limit <= 0) return emptyList()
        val found = LinkedHashMap<String, EpisodeMatch>()
        for ((key, playable) in episodeSearchKeys) {
            val showId = playable.show.id
            if (showId in excluding || showId in found) continue
            if (key.contains(needle)) {
                found[showId] = EpisodeMatch(playable.show, playable.episode)
                if (found.size >= limit) break
            }
        }
        return found.values.toList()
    }

    /** A show found by one of its episodes' names. */
    data class EpisodeMatch(val show: MediaItem, val episode: Episode)

    companion object {
        const val MIN_EPISODE_QUERY = 3
    }
}
