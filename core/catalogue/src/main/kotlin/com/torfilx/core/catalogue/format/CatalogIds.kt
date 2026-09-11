package com.torfilx.core.catalogue.format

import com.torfilx.core.model.MagnetLink

/**
 * How a catalogue entry gets its id.
 *
 * Ids are Compose list keys (a duplicate crashes a row), navigation route arguments, and the Room key
 * for watch progress and My List. So they must be unique within a catalogue, safe inside a route, and
 * above all **stable across catalogue releases**.
 *
 * The derived rule below is byte-for-byte the one the app has always used, so pinning ids into an
 * existing catalogue changes nothing for anyone who already has progress saved.
 */
object CatalogIds {

    private const val MAX_ID_LENGTH = 200
    private const val HASH_SUFFIX_LENGTH = 8

    /** Characters that would break a navigation route, a URL or a log line. */
    private const val FORBIDDEN = "/?#&%\\\"'<>"
    private val DASHES = Regex("-+")

    fun slug(title: String): String = title.lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .replace(DASHES, "-")
        .trim('-')

    fun baseId(title: String, year: String?): String = "catalog-${slug(title)}-${year.orEmpty()}"

    /** Whether [id] may be used as an explicit id. Ids derived from real titles always pass. */
    fun isValid(id: String): Boolean =
        id.isNotEmpty() &&
            id.length <= MAX_ID_LENGTH &&
            id.none { it.isWhitespace() || it.isISOControl() || it in FORBIDDEN }

    /** The first info hash in [entry] that the app would accept, exactly as the parser finds it. */
    fun firstInfoHash(entry: CatalogEntryDto): String? =
        entry.magnets.firstNotNullOfOrNull { MagnetLink.infoHashOf(it.magnet) }

    /**
     * The id for one entry: its explicit id when it has a usable, unused one, otherwise the derived id.
     *
     * @param title the entry's title, already trimmed.
     * @param index the entry's position in the file, counting entries that were skipped.
     * @param usedIds ids already taken earlier in the same catalogue; updated with the result.
     */
    fun assign(
        explicitId: String?,
        title: String,
        year: String?,
        firstInfoHash: String?,
        index: Int,
        usedIds: MutableSet<String>,
    ): String {
        val explicit = explicitId?.trim()
        if (!explicit.isNullOrEmpty() && isValid(explicit) && usedIds.add(explicit)) return explicit
        return derive(title, year, firstInfoHash, index, usedIds)
    }

    /**
     * The historical rule: `catalog-<slug>-<year>`, disambiguated on collision by the first eight
     * characters of the first valid info hash, then by position.
     */
    fun derive(
        title: String,
        year: String?,
        firstInfoHash: String?,
        index: Int,
        usedIds: MutableSet<String>,
    ): String {
        val baseId = baseId(title, year)
        if (usedIds.add(baseId)) return baseId
        val hashSuffix = firstInfoHash?.take(HASH_SUFFIX_LENGTH)
        val candidate = if (hashSuffix != null) "$baseId-$hashSuffix" else "$baseId-$index"
        return if (usedIds.add(candidate)) candidate else "$baseId-$index".also { usedIds.add(it) }
    }

    /**
     * Gives every titled entry an explicit id, keeping the ids it already has.
     *
     * The walk mirrors the app's parser exactly (same order, same skipped entries, same index), so an
     * entry pinned here gets the id the app was already deriving for it.
     */
    fun pin(entries: List<CatalogEntryDto>): List<CatalogEntryDto> {
        val used = HashSet<String>()
        return entries.mapIndexed { index, entry ->
            val title = entry.title.trim()
            if (title.isEmpty()) {
                entry
            } else {
                entry.copy(id = assign(entry.id, title, entry.year, firstInfoHash(entry), index, used))
            }
        }
    }
}
