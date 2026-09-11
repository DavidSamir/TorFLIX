package com.torfilx.core.data.catalog

import com.torfilx.core.model.MediaItem

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

    val genres: List<String> = mediaItems.flatMap { it.genres }.distinct().sorted()

    /** Lower-cased titles, parallel to [mediaItems], so search does no per-keystroke lowercasing. */
    private val searchKeys: List<String> = mediaItems.map { it.title.lowercase() }

    /** True when fewer titles were parsed than the source declares. */
    val isIncomplete: Boolean get() = declaredCount > 0 && items.size < declaredCount

    /**
     * Only a complete, non-empty catalogue is kept. A failed or short read used to be cached like any
     * other outcome, which left the app with a broken library for the life of the process; now it is
     * read again on the next access instead.
     */
    internal val isCacheable: Boolean get() = items.isNotEmpty() && !isIncomplete

    fun item(id: String): CatalogItem? = byId[id]

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
}
