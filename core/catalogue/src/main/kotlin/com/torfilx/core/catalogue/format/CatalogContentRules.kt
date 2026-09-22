package com.torfilx.core.catalogue.format

/**
 * What a catalogue must satisfy to be published.
 *
 * The app's parser stays lenient with a hand-edited file. A signed release is held to more: every entry
 * titled, every id explicit, valid and unique, and the entry count matching what the raw bytes declare.
 * For a show, every season and episode numbered, numbers unique, and every episode's id as unique as
 * a film's — it is the key the viewer's progress on that episode is stored under. The publisher checks
 * this before signing and the app checks it again before installing, so a release that would orphan
 * progress, crash a row with a duplicate key or silently lose titles never gets in.
 */
object CatalogContentRules {

    private const val MAX_REPORTED = 10
    private const val TITLE_PREVIEW = 40

    /** Added to a title-count problem when the file has shows: by far the likeliest cause. */
    const val EPISODE_TITLE_HINT =
        "; episodes are named with \"name\", and a \"title\" key inside a season or episode breaks this count"

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
                "${entries.size} entries" + if (entries.any { it.seasons.isNotEmpty() }) EPISODE_TITLE_HINT else ""
        }
        val ids = IdRegistry(problems, requireExplicitIds)
        entries.forEachIndexed { index, entry ->
            val label = "entry $index (\"${entry.title.take(TITLE_PREVIEW)}\")"
            if (entry.title.isBlank()) problems += "entry $index has no title"
            ids.check(entry.id, Location(index, null), label)
            when {
                !entry.hasKnownType -> problems += "entry $index has an unknown type \"${entry.type?.take(TITLE_PREVIEW)}\"; " +
                    "it must be \"${CatalogEntryDto.TYPE_MOVIE}\" or \"${CatalogEntryDto.TYPE_SHOW}\""
                entry.isShow -> showProblems(entry, index, label, problems, ids)
                entry.seasons.isNotEmpty() -> problems += "$label is a film but has seasons; " +
                    "a show needs \"type\": \"${CatalogEntryDto.TYPE_SHOW}\""
            }
        }
        return if (problems.size > MAX_REPORTED) {
            problems.take(MAX_REPORTED) + "and ${problems.size - MAX_REPORTED} more"
        } else {
            problems
        }
    }

    /** Episodes across every show in [entries]: what a release manifest's `episodeCount` states. */
    fun episodeCount(entries: List<CatalogEntryDto>): Int =
        entries.filter { it.isShow }.sumOf { show -> show.seasons.sumOf { it.episodes.size } }

    private fun showProblems(
        entry: CatalogEntryDto,
        index: Int,
        label: String,
        problems: MutableList<String>,
        ids: IdRegistry,
    ) {
        if (entry.magnets.isNotEmpty()) {
            problems += "$label is a show, so its magnets belong to its episodes, not to the show"
        }
        if (entry.seasons.isEmpty()) {
            problems += "$label is a show with no seasons"
            return
        }
        val episodeTotal = entry.seasons.sumOf { it.episodes.size }
        if (episodeTotal > CatalogRelease.MAX_EPISODES_PER_SHOW) {
            problems += "$label has $episodeTotal episodes; the limit is ${CatalogRelease.MAX_EPISODES_PER_SHOW}"
        }
        val seasonNumbers = HashSet<Int>()
        entry.seasons.forEachIndexed { seasonPosition, season ->
            val seasonNumber = season.number
            if (seasonNumber == null || !CatalogIds.isValidSeasonNumber(seasonNumber)) {
                problems += "$label: season ${seasonPosition + 1} in the file has no valid number (0 or more)"
                return@forEachIndexed
            }
            if (!seasonNumbers.add(seasonNumber)) problems += "$label has two seasons numbered $seasonNumber"
            if (season.episodes.isEmpty()) problems += "$label: season $seasonNumber has no episodes"
            val episodeNumbers = HashSet<Int>()
            season.episodes.forEachIndexed { episodePosition, episode ->
                val number = episode.number
                if (number == null || !CatalogIds.isValidEpisodeNumber(number)) {
                    problems += "$label: season $seasonNumber, episode ${episodePosition + 1} in the file has no " +
                        "valid number (1 or more)"
                    return@forEachIndexed
                }
                if (!episodeNumbers.add(number)) problems += "$label has two episodes numbered S$seasonNumber E$number"
                ids.check(episode.id, Location(index, "S$seasonNumber E$number"), "$label S$seasonNumber E$number")
            }
        }
    }

    /** Where an id sits: a top-level entry, or one of a show's episodes. */
    private data class Location(val entry: Int, val episode: String?) {
        override fun toString(): String = if (episode == null) "entry $entry" else "entry $entry $episode"
    }

    /** Checks each id as it is met, and reports every one that is missing, unusable or taken. */
    private class IdRegistry(
        private val problems: MutableList<String>,
        private val requireExplicitIds: Boolean,
    ) {
        private val firstLocationOfId = HashMap<String, Location>()

        fun check(id: String?, location: Location, label: String) {
            when {
                id == null -> if (requireExplicitIds) problems += "$label has no id"
                !CatalogIds.isValid(id) -> problems += "$location has an invalid id \"${id.take(TITLE_PREVIEW)}\""
                else -> firstLocationOfId.put(id, location)?.let { first ->
                    problems += if (first.episode == null && location.episode == null) {
                        // The wording films have always had.
                        "entries ${first.entry} and ${location.entry} share the id \"$id\""
                    } else {
                        "$first and $location share the id \"$id\""
                    }
                }
            }
        }
    }
}
