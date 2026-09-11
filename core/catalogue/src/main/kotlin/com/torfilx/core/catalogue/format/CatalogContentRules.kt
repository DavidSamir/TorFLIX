package com.torfilx.core.catalogue.format

/**
 * What a catalogue must satisfy to be published.
 *
 * The app's parser stays lenient with a hand-edited file. A signed release is held to more: every entry
 * titled, every id explicit, valid and unique, and the entry count matching what the raw bytes declare.
 * The publisher checks this before signing and the app checks it again before installing, so a release
 * that would orphan progress, crash a row with a duplicate key or silently lose titles never gets in.
 */
object CatalogContentRules {

    private const val MAX_REPORTED = 10
    private const val TITLE_PREVIEW = 40

    /**
     * Every problem found, as readable sentences; empty when the catalogue is publishable.
     *
     * @param declaredTitles the `"title"` keys counted in the raw bytes (see [countDeclaredTitles]).
     * @param requireExplicitIds true for a release; false to validate a hand-edited file before pinning.
     */
    fun problems(
        entries: List<CatalogEntryDto>,
        declaredTitles: Int,
        requireExplicitIds: Boolean,
    ): List<String> {
        val problems = ArrayList<String>()
        if (entries.size > CatalogRelease.MAX_TITLES) {
            problems += "${entries.size} titles is more than the limit of ${CatalogRelease.MAX_TITLES}"
        }
        if (declaredTitles != entries.size) {
            problems += "the raw file declares $declaredTitles \"title\" keys but decodes to " +
                "${entries.size} entries"
        }
        val firstIndexOfId = HashMap<String, Int>()
        entries.forEachIndexed { index, entry ->
            if (entry.title.isBlank()) problems += "entry $index has no title"
            val id = entry.id
            when {
                id == null -> if (requireExplicitIds) {
                    problems += "entry $index (\"${entry.title.take(TITLE_PREVIEW)}\") has no id"
                }
                !CatalogIds.isValid(id) -> problems += "entry $index has an invalid id \"${id.take(TITLE_PREVIEW)}\""
                else -> firstIndexOfId.put(id, index)?.let { first ->
                    problems += "entries $first and $index share the id \"$id\""
                }
            }
        }
        return if (problems.size > MAX_REPORTED) {
            problems.take(MAX_REPORTED) + "and ${problems.size - MAX_REPORTED} more"
        } else {
            problems
        }
    }
}
