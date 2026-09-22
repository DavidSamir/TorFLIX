package com.torfilx.tools.catalog

import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogMagnetDto
import java.net.URLDecoder

/**
 * Trims the tracker lists carried inside catalogue magnets.
 *
 * Most of a catalogue's bytes are the same handful of tracker URLs repeated in every magnet, many of
 * them long dead. The app adds its own live trackers to every torrent, so a magnet needs its info hash
 * and a couple of trackers of its own, not seven. Keeping two — preferring ones the app also uses —
 * means discovery never rests on the app's list alone if the viewer turns extra trackers off.
 *
 * Only `tr=` parameters are touched; the info hash, the display name and everything else stay exactly
 * as they were, in the same order, so ids (which come from titles and info hashes) cannot change.
 */
object MagnetTrackers {

    /** Keeps at most [keep] `tr=` parameters of [magnet], taking those in [preferred] first. */
    fun strip(magnet: String, keep: Int, preferred: List<String>): String {
        if (!magnet.startsWith(PREFIX, ignoreCase = true)) return magnet
        val params = magnet.substring(PREFIX.length).split('&').filter { it.isNotEmpty() }
        val trackers = params.filter { it.startsWith(TRACKER, ignoreCase = true) }
        if (trackers.size <= keep) return magnet

        val preferredSet = preferred.toSet()
        fun url(param: String) = runCatching { URLDecoder.decode(param.substring(TRACKER.length), "UTF-8") }.getOrDefault("")
        val kept = (trackers.filter { url(it) in preferredSet } + trackers.filterNot { url(it) in preferredSet })
            .distinct()
            .take(keep)
            .toSet()
        return PREFIX + params.filter { !it.startsWith(TRACKER, ignoreCase = true) || it in kept }.joinToString("&")
    }

    /** [strip] applied to every magnet in [entries]: films, episodes and season packs. */
    fun stripAll(entries: List<CatalogEntryDto>, keep: Int, preferred: List<String>): List<CatalogEntryDto> {
        fun List<CatalogMagnetDto>.stripped() = map { it.copy(magnet = strip(it.magnet, keep, preferred)) }
        return entries.map { entry ->
            entry.copy(
                magnets = entry.magnets.stripped(),
                seasons = entry.seasons.map { season ->
                    season.copy(
                        packs = season.packs.stripped(),
                        episodes = season.episodes.map { it.copy(magnets = it.magnets.stripped()) },
                    )
                },
            )
        }
    }

    /** Trackers kept per magnet by `build --strip-trackers`. */
    const val DEFAULT_KEEP = 2

    private const val PREFIX = "magnet:?"
    private const val TRACKER = "tr="
}
