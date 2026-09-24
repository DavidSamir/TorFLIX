package com.torfilx.tools.catalog.merge

import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogSeasonDto
import kotlinx.serialization.json.JsonElement

/**
 * Keeps one copy of every title: the newest.
 *
 * Files are fed newest first. The first usable copy of a title wins and every later one is an older
 * copy, passed over. A title is identified by its key: its explicit id, or else the id derived from its
 * type, title and year, which is the id the app itself gives it. So a copy with an id and a copy
 * without one, same title and year, are the same title.
 *
 * A copy that cannot be used (no title, an unknown type) does not count as found: the next usable,
 * older copy is used instead, and the report says so.
 *
 * Titles come out in the order they were introduced, newest introductions first: a title's place is
 * set by the oldest file that has it. Brand-new titles lead, and re-adding a title in a newer file to
 * update its artwork does not move it.
 */
internal class TitleMerger(private val log: MergeLog, private val mergeEpisodes: Boolean) {

    private class Kept(
        var entry: CatalogEntryDto,
        val key: String,
        val where: String,
        val rank: Int,
        var introducedRank: Int,
        var introducedPosition: Int,
    ) {
        /** Episodes, as `S01E03 (file)`, that only older copies of this show have. */
        val olderOnlyEpisodes = LinkedHashSet<String>()
        var episodesTakenFromOlder = 0
    }

    private val reader = EntryReader(log)
    private val kept = HashMap<String, Kept>()

    /** Keys whose newest copy was refused, and where; cleared when an older copy stands in. */
    private val refusedNewest = HashMap<String, String>()

    var copies = 0
        private set
    var superseded = 0
        private set
    var refused = 0
        private set

    /**
     * Takes every title in one file.
     *
     * @param rank the file's place from the newest (0) to the oldest; files must come in this order.
     */
    fun add(rank: Int, file: InputFile, values: List<JsonElement>) {
        values.forEachIndexed { position, value ->
            copies++
            val read = reader.read(value, "${file.display} #${position + 1}")
            val entry = read.entry
            val key = read.key
            if (entry == null || key == null) {
                refused++
                if (key != null && key !in kept) refusedNewest.putIfAbsent(key, read.where)
                return@forEachIndexed
            }
            val existing = kept[key]
            if (existing == null) {
                kept[key] = Kept(entry, key, read.where, rank, rank, position)
                refusedNewest.remove(key)?.let { newer ->
                    log.warning(read.where, "this older copy was used because the newer one ($newer) could not be")
                }
                return@forEachIndexed
            }
            superseded++
            if (existing.rank == rank) {
                log.warning(read.where, "is listed again in the same file (first as ${existing.where}); the first listing was used")
            } else {
                // One line per older copy, kept short: in a folder of a million files there are millions.
                log.note(read.where, "older copy of ${existing.key}; used: ${existing.where.substringBefore(" \"")}")
            }
            if (rank > existing.introducedRank) {
                existing.introducedRank = rank
                existing.introducedPosition = position
            }
            compareOlder(existing, entry, read.where)
        }
    }

    /** Checks an older copy against the kept one: a changed kind of title, and episodes only it has. */
    private fun compareOlder(kept: Kept, older: CatalogEntryDto, where: String) {
        val keptEntry = kept.entry
        if (keptEntry.isShow != older.isShow) {
            log.warning(
                where,
                "is a ${kindName(older)}, but the newer copy (${kept.where}) is a ${kindName(keptEntry)} with the same id; the newer one was used",
            )
            return
        }
        if (!keptEntry.isShow) return
        if (mergeEpisodes) {
            val (merged, added) = withOlderEpisodes(keptEntry, older)
            kept.entry = merged
            kept.episodesTakenFromOlder += added
        } else {
            val have = episodeCodes(keptEntry)
            episodeCodes(older).filter { it !in have }.forEach { kept.olderOnlyEpisodes += "$it (${where.substringBefore(" #")})" }
        }
    }

    /**
     * [newer] with every episode, and every season, that only [older] has. Everything [newer] has is
     * kept as it is, season names, images and packs included.
     *
     * @return the merged show and how many episodes came from [older].
     */
    private fun withOlderEpisodes(newer: CatalogEntryDto, older: CatalogEntryDto): Pair<CatalogEntryDto, Int> {
        var added = 0
        val seasons = newer.seasons.toMutableList()
        older.seasons.forEach { olderSeason ->
            val index = seasons.indexOfFirst { it.number == olderSeason.number }
            if (index < 0) {
                seasons += olderSeason
                added += olderSeason.episodes.size
            } else {
                val newerSeason = seasons[index]
                val numbers = newerSeason.episodes.mapTo(HashSet()) { it.number }
                val missing = olderSeason.episodes.filter { it.number !in numbers }
                if (missing.isNotEmpty()) {
                    seasons[index] = newerSeason.copy(episodes = (newerSeason.episodes + missing).sortedBy { it.number })
                    added += missing.size
                }
            }
        }
        if (added == 0) return newer to 0
        return newer.copy(seasons = seasons.sortedWith(SEASON_ORDER)) to added
    }

    /**
     * The kept titles in catalogue order, after reporting what is known only once every file is read:
     * shows whose older copies have episodes the newest lacks, and refused copies nothing replaced.
     */
    fun result(): List<Merged> {
        refusedNewest.forEach { (key, where) ->
            log.note(where, "no usable copy of $key was found in any file, so it is not in the catalogue")
        }
        val ordered = kept.values.sortedWith(compareBy<Kept> { it.introducedRank }.thenBy { it.introducedPosition })
        ordered.forEach { title ->
            if (title.olderOnlyEpisodes.isNotEmpty()) {
                log.warning(
                    title.where,
                    "${title.olderOnlyEpisodes.size} episodes are only in older copies of this show, so they are left out: " +
                        "${preview(title.olderOnlyEpisodes.toList())}. Put them in the newest copy, or merge with --merge-episodes",
                )
            }
            if (title.episodesTakenFromOlder > 0) {
                log.note(title.where, "${title.episodesTakenFromOlder} episodes were taken from older copies of this show (--merge-episodes)")
            }
        }
        return ordered.map { Merged(it.entry, it.key, it.where) }
    }

    /** One title of the merged catalogue: its newest copy, its key, and where that copy came from. */
    class Merged(val entry: CatalogEntryDto, val key: String, val where: String)

    private fun episodeCodes(show: CatalogEntryDto): Set<String> = show.seasons.flatMapTo(LinkedHashSet()) { season ->
        season.episodes.map { "S${twoDigits(season.number!!)}E${twoDigits(it.number!!)}" }
    }

    private fun kindName(entry: CatalogEntryDto) = if (entry.isShow) "show" else "film"

    private companion object {
        /** Regular seasons in order, Specials (0) last: the order the app shows them in. */
        val SEASON_ORDER = compareBy<CatalogSeasonDto> { it.number == 0 }.thenBy { it.number }
    }
}
