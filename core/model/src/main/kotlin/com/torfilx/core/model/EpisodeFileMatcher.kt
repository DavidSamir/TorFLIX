package com.torfilx.core.model

/**
 * Finds one episode's video file inside a torrent that holds several.
 *
 * Used two ways: as a safeguard when a single episode's magnet turns out to be a whole-season pack
 * (a catalogue mistake that would otherwise play the largest file, not the episode asked for), and
 * as the way season packs are played at all.
 *
 * Pure and unit-tested against real pack listings, because the failure it prevents — playing the
 * wrong episode — is silent: nothing errors, the viewer just sees the wrong story.
 */
object EpisodeFileMatcher {

    /** One file of the torrent, already known to be a video. */
    data class Candidate(val index: Int, val path: String, val sizeBytes: Long)

    /** Episode identity. Show-wide packs cannot assume an unscoped number belongs to this season. */
    data class Target(
        val season: Int,
        val episode: Int,
        val ordinal: Int,
        val episodesInSeason: Int,
        val allowUnscopedEpisodeNumbers: Boolean = true,
    )

    // S01E03, s1e3, S01.E03, S01 E03, and ranges: S01E03E04, S01E03-E04, S01E03-04. The range end
    // must stand alone, so "S01E03-720p" is episode 3, not episodes 3 to 720.
    private val SEASON_EPISODE =
        Regex("""(?i)(?<![a-z0-9])s(\d{1,3})[ ._-]*e(\d{1,4})(?=$|[^a-z0-9]|e\d)""")
    private val NEXT_EPISODE = Regex("""(?i)^([ ._]*e|-e?)(\d{1,4})(?=$|[^a-z0-9]|e\d)""")

    // 1x03, 01x03. Needs a non-digit before, so 1920x1080 never matches.
    private val CROSS = Regex("""(?i)(?<![0-9a-z])(\d{1,2})x(\d{1,3})(?![0-9a-z])""")

    // E03, Ep03, Ep.3, Episode 3 — only trusted when the torrent holds a single season.
    private val EPISODE_ONLY = Regex("""(?i)(?<![a-z0-9])(?:episode|ep|e)[ ._-]*(\d{1,3})(?=$|[^a-z0-9]|e\d)""")

    private val SAMPLE = Regex("""(?i)(?:^sample(?:[ ._-]|$)|(?:^|[ ._-])(?:sample|trailer)(?:[ ._-]\d{3,4}p)?$)""")

    private val SEASON = Regex("""(?i)(?<![a-z0-9])(?:season[ ._-]*|s)(\d{1,3})(?![a-z0-9])""")
    private val NEXT_SEASON = Regex("""(?i)^([ ,]+|[ ._]*-[ ._]*)(?:s)?(\d{1,3})(?![a-z0-9])""")
    private val SPECIALS = Regex("""(?i)^(?:specials?|season[ ._-]*0|s0+)$""")
    private val NUMBERED = Regex("""(?i)^(?:(?:episode|ep|e|part)[ ._-]*)?(\d{1,3})(?=$|[ ._-])""")
    private val DELIMITED_NUMBER = Regex("""(?:^| - |\[)(\d{1,3})(?= - |\]|$)""")
    private val EXTRAS = Regex("""(?i)^(?:samples?|trailers?|extras?|featurettes?|behind[ ._-]*the[ ._-]*scenes|bonus(?:[ ._-]*features)?)$""")

    private fun components(path: String): List<String> = path.replace('\\', '/').split('/')

    private fun seasonContext(path: String): Set<Int> = components(path).asReversed().firstNotNullOfOrNull { part ->
        val seasons = if (SPECIALS.matches(part)) setOf(0)
            else SEASON.findAll(part).flatMap { match ->
                val first = match.groupValues[1].toInt()
                val numbers = mutableSetOf(first)
                var previous = first
                var tail = part.substring(match.range.last + 1)
                while (true) {
                    val next = NEXT_SEASON.find(tail) ?: break
                    val number = next.groupValues[2].toInt()
                    if ('-' in next.groupValues[1]) {
                        if (number < previous) break
                        numbers.addAll(previous..number)
                    } else numbers.add(number)
                    previous = number
                    tail = tail.substring(next.value.length)
                }
                numbers
            }.toSet()
        seasons.takeIf { it.isNotEmpty() }
    }.orEmpty()

    private fun explicitCodes(path: String): List<Pair<Int, Set<Int>>> = buildList {
        SEASON_EPISODE.findAll(fileName(path)).forEach { match ->
            val first = match.groupValues[2].toInt()
            add(match.groupValues[1].toInt() to episodeNumbers(first, fileName(path).substring(match.range.last + 1)))
        }
        CROSS.findAll(fileName(path)).forEach { match ->
            val episode = match.groupValues[2].toInt()
            add(match.groupValues[1].toInt() to episodeNumbers(episode, fileName(path).substring(match.range.last + 1)))
        }
    }

    private fun episodeNumbers(first: Int, suffix: String): Set<Int> {
        val episodes = mutableSetOf(first)
        var previous = first
        var tail = suffix
        while (true) {
            val next = NEXT_EPISODE.find(tail) ?: break
            val number = next.groupValues[2].toInt()
            if (next.groupValues[1].startsWith('-')) {
                if (number < previous || number in setOf(264, 265, 480, 576, 720, 1080, 2160, 4320)) break
                episodes.addAll(previous..number)
            } else episodes.add(number)
            previous = number
            tail = tail.substring(next.value.length)
        }
        return episodes
    }

    private fun looseEpisodes(path: String): Set<Int> {
        val name = fileName(path).substringBeforeLast('.')
        val labelled = EPISODE_ONLY.findAll(name).flatMap { match ->
            episodeNumbers(match.groupValues[1].toInt(), name.substring(match.range.last + 1))
        }.toSet()
        if (labelled.isNotEmpty()) return labelled
        val match = NUMBERED.find(name) ?: DELIMITED_NUMBER.find(name) ?: return emptySet()
        val number = match.groupValues[1].toInt()
        return if (number in setOf(264, 265, 480, 576, 720)) emptySet()
            else episodeNumbers(number, name.substring(match.range.last + 1))
    }

    /**
     * The index of the file to play, or null when no file can be trusted to be the episode.
     *
     * Explicit season/episode codes take priority. Episode-only and numbered names are scoped to
     * their nearest season folder, or to a single-season listing. Missing numbers never shift later
     * episodes into earlier slots. Several qualities of the same episode resolve to the largest.
     */
    fun select(candidates: List<Candidate>, target: Target): Int? {
        val videos = episodesOnly(candidates)
        if (videos.isEmpty()) return null

        // File-level codes win over folder names. Never read an episode code from a parent folder:
        // a folder called S01E01 must not make every video inside it episode one.
        videos.filter { candidate ->
            explicitCodes(candidate.path).any { (season, episodes) ->
                season == target.season && target.episode in episodes
            }
        }.largest()?.let { return it }

        val knownSeasons = videos.flatMap { candidate ->
            explicitCodes(candidate.path).map { it.first } + seasonContext(candidate.path)
        }.toSet()
        return videos.filter { candidate ->
            if (explicitCodes(candidate.path).isNotEmpty()) return@filter false
            val context = seasonContext(candidate.path)
            val seasonMatches = if (context.isNotEmpty()) context == setOf(target.season)
                else target.allowUnscopedEpisodeNumbers && (knownSeasons.isEmpty() || knownSeasons == setOf(target.season))
            seasonMatches && target.episode in looseEpisodes(candidate.path)
        }.largest()
    }

    /** An episode-specific source may have one unlabelled video, but never an arbitrary pack file. */
    fun selectEpisodeSource(candidates: List<Candidate>, target: Target): Int? {
        select(candidates, target)?.let { return it }
        val only = episodesOnly(candidates).singleOrNull() ?: return null
        return only.index.takeIf {
            explicitCodes(only.path).isEmpty() && looseEpisodes(only.path).isEmpty() &&
                seasonContext(only.path).let { seasons -> seasons.isEmpty() || seasons == setOf(target.season) }
        }
    }

    /** Drops named extras. File size alone cannot distinguish a short special from an extra. */
    internal fun episodesOnly(candidates: List<Candidate>): List<Candidate> {
        return candidates.filterNot {
            SAMPLE.containsMatchIn(fileName(it.path).substringBeforeLast('.')) ||
                components(it.path).dropLast(1).any(EXTRAS::matches)
        }
    }

    private fun List<Candidate>.largest(): Int? = maxByOrNull { it.sizeBytes }?.index

    private fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    /** "Episode 2" before "Episode 10": digit runs compare as numbers. */
    internal object NaturalOrder : Comparator<String> {
        private val CHUNK = Regex("""\d+|\D+""")

        override fun compare(a: String, b: String): Int {
            val left = CHUNK.findAll(a.lowercase()).map { it.value }.toList()
            val right = CHUNK.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(left.size, right.size)) {
                val x = left[i]
                val y = right[i]
                val result = if (x[0].isDigit() && y[0].isDigit()) {
                    compareValues(x.trimStart('0').length, y.trimStart('0').length)
                        .takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
                } else {
                    x.compareTo(y)
                }
                if (result != 0) return result
            }
            return left.size - right.size
        }
    }
}
