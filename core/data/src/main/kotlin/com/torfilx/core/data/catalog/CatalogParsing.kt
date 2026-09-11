package com.torfilx.core.data.catalog

import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogIds
import com.torfilx.core.catalogue.format.countDeclaredTitles
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.model.HdrType
import com.torfilx.core.model.Images
import com.torfilx.core.model.MagnetLink
import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.MediaSource
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

/** Maps one decoded entry to a validated item, or null when it has no usable title. */
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

    val sources = entry.magnets.mapIndexedNotNull { magnetIndex, magnet ->
        val infoHash = MagnetLink.infoHashOf(magnet.magnet)
        if (infoHash == null) {
            TorfilxLog.w(TAG, "\"$title\": magnet ${magnetIndex + 1} is malformed; skipped")
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
            images = Images(poster = entry.imageUrl, backdrop = entry.imageUrl),
            addedAtMs = null,
            updatedAtMs = 0L,
        ),
        sources = sources,
    )
}

private fun String.qualityHeight(): Int? = when {
    contains("2160") || contains("4k", ignoreCase = true) -> 2160
    contains("1080") -> 1080
    contains("720") -> 720
    contains("480") -> 480
    else -> null
}
