package com.torfilx.core.data.catalog

import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogIds
import com.torfilx.core.catalogue.format.CatalogMagnetDto
import com.torfilx.core.catalogue.format.countDeclaredTitles
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.model.Episode
import com.torfilx.core.model.EpisodeFileMatcher
import com.torfilx.core.model.FileSelection
import com.torfilx.core.model.HdrType
import com.torfilx.core.model.Images
import com.torfilx.core.model.MagnetLink
import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.MediaKind
import com.torfilx.core.model.MediaSource
import com.torfilx.core.model.Season
import com.torfilx.core.model.SourceKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence

private const val TAG = "Catalog"

/** A parsed catalogue and the number of titles its raw bytes declare. */
internal class ParsedCatalog(val items: List<CatalogItem>, val declaredCount: Int)

/**
 * Parses catalogue bytes, and checks the result against what the bytes declare.
 *
 * Both halves matter, because a partial catalogue is invisible from the sofa.
 *
 * The bytes are complete before parsing starts: the caller reads them fully, so a short read from
 * AssetManager's inflater can never masquerade as a clean end of input.
 *
 * The check afterwards exists because [parseCatalogStream] is deliberately resilient. It keeps
 * whatever it decoded before an error and stops quietly, which is right for one bad entry in a
 * hand-edited file and completely wrong as a silent outcome. Counting what the file *declares* costs
 * one pass over the bytes and turns "the app has fewer films than it should" from something only a
 * viewer can notice into something the app states.
 */
internal fun parseCatalogBytes(bytes: ByteArray, json: Json): ParsedCatalog {
    val declaredCount = countDeclaredTitles(bytes)
    TorfilxLog.i(TAG, "Catalogue read: ${bytes.size} bytes, $declaredCount titles declared")
    val raw = bytes.decodeToString()

    // Decode the whole array in one go, and only fall back to the entry-by-entry reader if that fails
    // outright.
    //
    // The order matters and is the fix for the library shrinking. Streaming with `decodeToSequence`
    // fails *soft*: a decoder error part-way through leaves the entries decoded before it and stops,
    // so the app carries on with a fraction of its content and nothing says so. An atomic decode either
    // has the whole catalogue or visibly none of it. The resilient reader is kept for what it is good
    // for, rescuing a hand-edited file with one bad line, and whichever path yields more titles wins.
    val atomic = runCatching { parseCatalogAtomic(raw, json) }
        .onFailure { TorfilxLog.e(TAG, "Atomic catalogue decode failed; trying the resilient reader", it) }
        .getOrNull()

    val items = when {
        atomic != null && (declaredCount == 0 || atomic.size >= declaredCount) -> atomic
        else -> {
            val streamed = parseCatalogStream(raw.byteInputStream(), json)
            val best = listOfNotNull(atomic, streamed).maxByOrNull { it.size }.orEmpty()
            TorfilxLog.w(
                TAG,
                "Atomic decode yielded ${atomic?.size ?: 0}, resilient reader ${streamed.size}; using ${best.size}",
            )
            best
        }
    }

    if (declaredCount > 0 && items.size < declaredCount) {
        TorfilxLog.e(TAG, "CATALOGUE INCOMPLETE: parsed ${items.size} of $declaredCount declared titles")
    } else {
        TorfilxLog.i(TAG, "Catalogue parsed in full: ${items.size} titles")
    }
    return ParsedCatalog(items, declaredCount)
}

/**
 * Decodes the whole array at once: all of it, or an exception.
 *
 * This is the primary path precisely *because* it cannot half-succeed. A decoder failure here is loud
 * and recoverable; a decoder failure in the streaming reader is silent and permanent.
 */
internal fun parseCatalogAtomic(raw: String, json: Json): List<CatalogItem> =
    mapCatalogEntries(json.decodeFromString<List<CatalogEntryDto>>(raw))

/** Maps decoded entries into items. Shared by every reader, so none can produce a different library. */
internal fun mapCatalogEntries(entries: List<CatalogEntryDto>): List<CatalogItem> {
    val items = ArrayList<CatalogItem>(entries.size)
    consumeEntries(entries.asSequence(), items, HashSet())
    return items
}

/**
 * Maps decoded entries into items, skipping individually bad ones.
 *
 * @return how many entries were seen, which the caller uses for its error message.
 */
private fun consumeEntries(
    entries: Sequence<CatalogEntryDto>,
    into: MutableList<CatalogItem>,
    usedIds: MutableSet<String>,
): Int {
    var index = 0
    entries.forEach { entry ->
        runCatching { mapCatalogEntry(entry, index, usedIds) }
            .onFailure { TorfilxLog.w(TAG, "Catalogue entry $index skipped: ${it.message}") }
            .getOrNull()
            ?.let { into.add(it) }
        index++
    }
    return index
}

/**
 * Streams the catalogue JSON array, mapping and validating each entry as it arrives.
 *
 * Resilient: a malformed entry is skipped individually, and if a JSON syntax error stops the stream
 * part-way, the entries parsed *before* it are kept. It is the fallback, never the primary path.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal fun parseCatalogStream(stream: java.io.InputStream, json: Json): List<CatalogItem> {
    val usedIds = HashSet<String>()
    val items = ArrayList<CatalogItem>()
    var index = 0
    try {
        index = consumeEntries(json.decodeToSequence<CatalogEntryDto>(stream), items, usedIds)
    } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
        // A JSON syntax error mid-stream: keep everything parsed so far instead of losing it all. Logged
        // as an error: a partial catalogue looks exactly like a small library from the sofa.
        TorfilxLog.e(TAG, "Catalogue parse STOPPED EARLY after $index entries -- the library is incomplete", error)
    }
    TorfilxLog.i(TAG, "Streamed catalogue: ${items.size} titles")
    return items
}

/**
 * Convenience for tests: the same atomic-first, resilient-fallback pair the app uses.
 *
 * Tests that specifically want the resilient reader call [parseCatalogStream] directly.
 */
internal fun parseCatalog(raw: String, json: Json): List<CatalogItem> =
    runCatching { parseCatalogAtomic(raw, json) }
        .getOrElse { parseCatalogStream(raw.byteInputStream(), json) }

/** Maps one decoded entry to a validated item, or null when it has no usable title or an unknown type. */
private fun mapCatalogEntry(
    entry: CatalogEntryDto,
    index: Int,
    usedIds: MutableSet<String>,
): CatalogItem? {
    val title = entry.title.trim()
    if (title.isEmpty()) {
        TorfilxLog.w(TAG, "Catalogue entry $index has no title; skipped")
        return null
    }
    // Skipped before any id is assigned, exactly as the publisher's pinning skips it, so the ids of
    // every entry after it are the same on both sides.
    if (!entry.hasKnownType) {
        TorfilxLog.w(TAG, "\"$title\" has a type this build does not know (\"${entry.type}\"); skipped")
        return null
    }
    if (entry.isShow) return mapShow(entry, title, index, usedIds)

    val sources = torrentSources(title, entry.magnets)

    // Ids are Compose list keys (a duplicate crashes a row) and the key watch progress and My List are
    // stored under. A published catalogue pins every id; a hand-edited one may not, and then the
    // historical derived rule applies, so ids already in use on devices never change.
    val explicitId = entry.id
    if (explicitId != null && !CatalogIds.isValid(explicitId.trim())) {
        TorfilxLog.w(TAG, "\"$title\": id \"$explicitId\" is not usable; deriving one")
    }
    val id = CatalogIds.assign(
        explicitId = explicitId,
        title = title,
        year = entry.year,
        firstInfoHash = sources.firstOrNull()?.id?.removePrefix("torrent-"),
        index = index,
        usedIds = usedIds,
    )
    return CatalogItem(
        item = MediaItem(
            id = id,
            title = title,
            sortTitle = title.removePrefix("The ").trim(),
            year = entry.year?.filter { it.isDigit() }?.toIntOrNull(),
            runtimeMs = entry.runtimeMinutes?.let { it * 60_000L },
            overview = entry.overview,
            // A repeated genre would put the same film twice in one row, and a duplicate key inside a
            // lazy list is a hard crash, so genres are normalised here, once.
            genres = entry.genres
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .distinctBy { it.lowercase() },
            images = Images(poster = entry.imageUrl, backdrop = entry.backdropUrl ?: entry.imageUrl),
            addedAtMs = null,
            updatedAtMs = 0L,
        ),
        sources = sources,
    )
}

/** Turns catalogue magnets into torrent sources, dropping (and logging) the malformed ones. */
private fun torrentSources(label: String, magnets: List<CatalogMagnetDto>): List<MediaSource> =
    magnets.mapIndexedNotNull { magnetIndex, magnet ->
        val infoHash = MagnetLink.infoHashOf(magnet.magnet)
        if (infoHash == null) {
            TorfilxLog.w(TAG, "\"$label\": magnet ${magnetIndex + 1} is malformed; skipped")
            return@mapIndexedNotNull null
        }
        val quality = magnet.quality?.trim().orEmpty()
        MediaSource(
            id = "torrent-$infoHash",
            kind = SourceKind.TORRENT,
            url = magnet.magnet,
            magnetUri = magnet.magnet,
            label = if (quality.isEmpty()) "Torrent" else "Torrent · $quality",
            videoCodec = null,
            height = quality.qualityHeight(),
            hdr = HdrType.NONE,
        )
    }

/**
 * Maps a show: its id and every episode's through the same walk the publisher pins with, then its
 * seasons in display order.
 *
 * Lenient like the film path, because a hand-edited file must still load what it can. A season or
 * episode without a usable number is skipped with a log line (a release carrying one never signs). Two
 * seasons given the same number are merged rather than listed twice, because the season number is a
 * list key on the details screen and a duplicate key is a crash. A show left with no episodes at all
 * is still returned, as a film with no magnets is: it stays in the library as unplayable, and a
 * release's title count still adds up.
 */
private fun mapShow(entry: CatalogEntryDto, title: String, index: Int, usedIds: MutableSet<String>): CatalogItem {
    val ids = CatalogIds.assignShow(entry, title, index, usedIds)
    val showId = ids.showId
    val episodeSources = HashMap<String, List<MediaSource>>()
    val bySeason = LinkedHashMap<Int, SeasonBuilder>()

    entry.seasons.forEachIndexed { seasonIndex, seasonDto ->
        val seasonNumber = seasonDto.number?.takeIf { CatalogIds.isValidSeasonNumber(it) }
        if (seasonNumber == null) {
            TorfilxLog.w(TAG, "\"$title\": season ${seasonIndex + 1} in the file has no usable number; skipped")
            return@forEachIndexed
        }
        val builder = bySeason.getOrPut(seasonNumber) {
            SeasonBuilder(seasonNumber, seasonDto.name?.trim()?.takeIf { it.isNotEmpty() }, seasonDto.imageUrl)
        }
        builder.packs += torrentSources("$title S$seasonNumber (season)", seasonDto.packs)
        seasonDto.episodes.forEachIndexed { episodeIndex, episodeDto ->
            val id = ids.episodeIds[seasonIndex][episodeIndex]
            val number = episodeDto.number
            if (id == null || number == null) {
                TorfilxLog.w(TAG, "\"$title\" season $seasonNumber: episode ${episodeIndex + 1} has no usable number; skipped")
                return@forEachIndexed
            }
            val sources = torrentSources("$title S$seasonNumber E$number", episodeDto.magnets)
            episodeSources[id] = sources
            builder.episodes += Episode(
                id = id,
                showId = showId,
                season = seasonNumber,
                number = number,
                name = episodeDto.name?.trim()?.takeIf { it.isNotEmpty() },
                overview = episodeDto.overview?.takeIf { it.isNotBlank() },
                runtimeMs = episodeDto.runtimeMinutes?.takeIf { it > 0 }?.let { it * 60_000L },
                airDateMs = parseAirDate(episodeDto.airDate),
                image = episodeDto.imageUrl?.takeIf { it.isNotBlank() },
                isPlayable = sources.isNotEmpty(),
            )
        }
    }

    val seasons = bySeason.values
        .filter { it.episodes.isNotEmpty() }
        .map { it.build(episodeSources) }
        // Regular seasons ascending, Specials last.
        .sortedWith(compareBy<Season> { it.isSpecials }.thenBy { it.number })
    val episodeCount = seasons.sumOf { it.episodes.size }
    if (episodeCount == 0) TorfilxLog.w(TAG, "\"$title\" is a show with no usable episodes")

    return CatalogItem(
        item = MediaItem(
            id = showId,
            title = title,
            sortTitle = title.removePrefix("The ").trim(),
            year = entry.year?.filter { it.isDigit() }?.toIntOrNull(),
            runtimeMs = null,
            overview = entry.overview,
            genres = entry.genres
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .distinctBy { it.lowercase() },
            images = Images(poster = entry.imageUrl, backdrop = entry.backdropUrl ?: entry.imageUrl),
            kind = MediaKind.SHOW,
            seasonCount = seasons.count { !it.isSpecials },
            episodeCount = episodeCount,
        ),
        sources = emptyList(),
        seasons = seasons,
        episodeSources = episodeSources,
    )
}

private class SeasonBuilder(val number: Int, val name: String?, val image: String?) {
    val episodes = ArrayList<Episode>()

    /** Whole-season torrents, before they are told which episode's file each source plays. */
    val packs = ArrayList<MediaSource>()

    /**
     * The season, episodes in order (a stable sort: two episodes given the same number keep their
     * file order, and distinct ids).
     *
     * Each season pack becomes one more source for every episode of the season, naming that
     * episode's file exactly ([FileSelection.Episode]): out of a pack, the wrong file is some other
     * episode, never an acceptable fallback. An episode with no torrent of its own but a pack is
     * playable.
     *
     * @param sources every episode's sources, by id; the pack sources are added to it here.
     */
    fun build(sources: MutableMap<String, List<MediaSource>>): Season {
        val ordered = episodes.sortedBy { it.number }
        return Season(
            number = number,
            name = name ?: Season.defaultName(number),
            image = image?.takeIf { it.isNotBlank() },
            episodes = ordered.mapIndexed { ordinal, episode ->
                val target = EpisodeFileMatcher.Target(number, episode.number, ordinal, ordered.size)
                val fromPacks = packs.map { pack ->
                    pack.copy(
                        id = "pack-" + pack.id.removePrefix("torrent-"),
                        label = "${pack.label} (season)",
                        fileSelection = FileSelection.Episode(target),
                    )
                }
                val all = sources[episode.id].orEmpty() + fromPacks
                sources[episode.id] = all
                episode.copy(isPlayable = all.isNotEmpty())
            },
        )
    }
}

/** `1959-10-02` as epoch milliseconds at UTC midnight; null when absent or not a date. */
internal fun parseAirDate(text: String?): Long? {
    val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        java.time.LocalDate.parse(trimmed).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    }.getOrNull()
}

private fun String.qualityHeight(): Int? = when {
    contains("2160") || contains("4k", ignoreCase = true) -> 2160
    contains("1080") -> 1080
    contains("720") -> 720
    contains("480") -> 480
    else -> null
}
