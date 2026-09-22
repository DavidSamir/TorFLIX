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

    /** What to look for. [ordinal] is the episode's 0-based position in its season, for the last resort. */
    data class Target(val season: Int, val episode: Int, val ordinal: Int, val episodesInSeason: Int)

    // S01E03, s1e3, S01.E03, S01 E03, and ranges: S01E03E04, S01E03-E04, S01E03-04. The range end
    // must stand alone, so "S01E03-720p" is episode 3, not episodes 3 to 720.
    private val SEASON_EPISODE =
        Regex("""(?i)(?<![a-z0-9])s(\d{1,3})[ ._-]*e(\d{1,4})(?:(?:[ ._-]*e|-)(\d{1,4})(?![0-9a-z]))?(?![0-9])""")

    // 1x03, 01x03. Needs a non-digit before, so 1920x1080 never matches.
    private val CROSS = Regex("""(?i)(?<![0-9a-z])(\d{1,2})x(\d{2,3})(?![0-9])""")

    // E03, Ep03, Ep.3, Episode 3 — only trusted when the torrent holds a single season.
    private val EPISODE_ONLY = Regex("""(?i)(?<![a-z0-9])(?:episode|ep|e)[ ._-]*(\d{1,3})(?![0-9])""")

    private val SAMPLE = Regex("""(?i)(?<![a-z])(sample|trailer)(?![a-z])""")

    /** Files smaller than this share of the median are extras, not episodes. */
    private const val EXTRA_FRACTION = 0.05

    /**
     * The index of the file to play, or null when no file can be trusted to be the episode.
     *
     * In order: an `S01E03`-style match for the right season; a `1x03` match; an `E03` match when the
     * torrent holds only one season; and, only when the number of real video files equals the number
     * of episodes in the season, the file at the episode's position in natural name order. Several
     * matches (two qualities of the same episode) resolve to the largest.
     */
    fun select(candidates: List<Candidate>, target: Target): Int? {
        val videos = episodesOnly(candidates)
        if (videos.isEmpty()) return null

        videos.filter { seasonEpisodeMatch(it.path, target) }.largest()?.let { return it }
        videos.filter { crossMatch(it.path, target) }.largest()?.let { return it }

        if (holdsOneSeason(videos, target.season)) {
            videos.filter { episodeOnlyMatch(it.path, target.episode) }.largest()?.let { return it }
        }

        // Position is only trusted when the files say nothing about which episode they are. Files that
        // do carry codes and none of them is this episode means the episode is not here — a pack of
        // another season with the same number of episodes must never answer by position.
        if (videos.none(::carriesEpisodeCode) &&
            target.episodesInSeason > 0 && videos.size == target.episodesInSeason &&
            target.ordinal in videos.indices
        ) {
            return videos.sortedWith(compareBy(NaturalOrder) { it.path }).getOrNull(target.ordinal)?.index
        }
        return null
    }

    private fun carriesEpisodeCode(candidate: Candidate): Boolean =
        SEASON_EPISODE.containsMatchIn(candidate.path) ||
            CROSS.containsMatchIn(fileName(candidate.path)) ||
            EPISODE_ONLY.containsMatchIn(fileName(candidate.path))

    /** Drops samples, trailers and anything far smaller than a typical file in the torrent. */
    internal fun episodesOnly(candidates: List<Candidate>): List<Candidate> {
        val named = candidates.filterNot { SAMPLE.containsMatchIn(fileName(it.path)) }
        if (named.size < 2) return named
        val sizes = named.map { it.sizeBytes }.sorted()
        val median = sizes[sizes.size / 2]
        return named.filter { it.sizeBytes >= median * EXTRA_FRACTION }
    }

    private fun seasonEpisodeMatch(path: String, target: Target): Boolean =
        SEASON_EPISODE.findAll(path).any { match ->
            val season = match.groupValues[1].toInt()
            val first = match.groupValues[2].toInt()
            val last = match.groupValues[3].toIntOrNull() ?: first
            season == target.season && target.episode in first..maxOf(first, last)
        }

    private fun crossMatch(path: String, target: Target): Boolean =
        CROSS.findAll(fileName(path)).any { match ->
            match.groupValues[1].toInt() == target.season && match.groupValues[2].toInt() == target.episode
        }

    private fun episodeOnlyMatch(path: String, episode: Int): Boolean =
        EPISODE_ONLY.findAll(fileName(path)).any { it.groupValues[1].toInt() == episode }

    /** True unless some file names a different season than the one asked for. */
    private fun holdsOneSeason(videos: List<Candidate>, season: Int): Boolean = videos.all { candidate ->
        SEASON_EPISODE.findAll(candidate.path).all { it.groupValues[1].toInt() == season } &&
            CROSS.findAll(fileName(candidate.path)).all { it.groupValues[1].toInt() == season }
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
