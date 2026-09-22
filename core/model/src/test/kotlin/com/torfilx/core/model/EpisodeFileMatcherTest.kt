package com.torfilx.core.model

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.model.EpisodeFileMatcher.Candidate
import com.torfilx.core.model.EpisodeFileMatcher.Target
import org.junit.Test

/**
 * Picking one episode's file out of a multi-file torrent.
 *
 * The listings below are shaped like real season packs: a folder per season, quality tags, sample
 * clips, and the odd release that numbers episodes only by position.
 */
class EpisodeFileMatcherTest {

    private fun files(vararg paths: Pair<String, Long>) =
        paths.mapIndexed { index, (path, size) -> Candidate(index, path, size) }

    private val gb = 1_000_000_000L
    private val mb = 1_000_000L

    private fun target(season: Int, episode: Int, ordinal: Int = episode - 1, count: Int = 0) =
        Target(season, episode, ordinal, count)

    @Test
    fun `an SxxEyy name matches its season and episode only`() {
        val pack = files(
            "Show.S01.720p/Show.S01E01.720p.mkv" to gb,
            "Show.S01.720p/Show.S01E02.720p.mkv" to gb,
            "Show.S01.720p/Show.S01E03.720p.mkv" to gb,
        )
        assertThat(EpisodeFileMatcher.select(pack, target(1, 2))).isEqualTo(1)
        assertThat(EpisodeFileMatcher.select(pack, target(2, 2))).isNull()
    }

    @Test
    fun `lower-case, unpadded and separated forms are all understood`() {
        assertThat(EpisodeFileMatcher.select(files("show s1e3.mp4" to gb), target(1, 3))).isEqualTo(0)
        assertThat(EpisodeFileMatcher.select(files("Show - S01.E03 - Title.mkv" to gb), target(1, 3))).isEqualTo(0)
        assertThat(EpisodeFileMatcher.select(files("Show S01 E03.avi" to gb), target(1, 3))).isEqualTo(0)
    }

    @Test
    fun `a double episode file serves both episodes it contains`() {
        val pack = files("Show.S02E05E06.mkv" to gb, "Show.S02E07.mkv" to gb)
        assertThat(EpisodeFileMatcher.select(pack, target(2, 5))).isEqualTo(0)
        assertThat(EpisodeFileMatcher.select(pack, target(2, 6))).isEqualTo(0)
        assertThat(EpisodeFileMatcher.select(files("Show.S02E05-06.mkv" to gb), target(2, 6))).isEqualTo(0)
        assertThat(EpisodeFileMatcher.select(files("Show.S02E05-E06.mkv" to gb), target(2, 6))).isEqualTo(0)
    }

    @Test
    fun `a quality tag after a dash is not read as an episode range`() {
        val pack = files("Show.S01E03-720p.mkv" to gb, "Show.S01E05.mkv" to gb)
        assertThat(EpisodeFileMatcher.select(pack, target(1, 5))).isEqualTo(1)
        assertThat(EpisodeFileMatcher.select(pack, target(1, 3))).isEqualTo(0)
    }

    @Test
    fun `the 1x03 form matches, and a resolution never does`() {
        val pack = files("Show 1x02 1920x1080.mkv" to gb, "Show 1x03 1920x1080.mkv" to gb)
        assertThat(EpisodeFileMatcher.select(pack, target(1, 3))).isEqualTo(1)
        assertThat(EpisodeFileMatcher.select(files("Film 1920x1080.mkv" to gb), target(19, 108))).isNull()
    }

    @Test
    fun `an episode-only name is trusted when the pack holds one season`() {
        val pack = files("Season 1/E01 - Pilot.mkv" to gb, "Season 1/E02 - Second.mkv" to gb)
        assertThat(EpisodeFileMatcher.select(pack, target(1, 2))).isEqualTo(1)
        assertThat(EpisodeFileMatcher.select(files("Ep.3 Title.mp4" to gb, "Ep.4 Title.mp4" to gb), target(1, 3))).isEqualTo(0)
    }

    @Test
    fun `an episode-only name is not trusted when the pack names other seasons`() {
        val mixed = files("Show.S01E01.mkv" to gb, "Show.S02E01.mkv" to gb, "Extras/E03 Making of.mkv" to gb)
        assertThat(EpisodeFileMatcher.select(mixed, target(1, 3))).isNull()
    }

    @Test
    fun `samples, trailers and tiny extras are never chosen`() {
        val pack = files(
            "Show.S01E01.mkv" to gb,
            "Sample/Show.S01E01.sample.mkv" to 30 * mb,
            "Show.S01E01.Trailer.mkv" to 20 * mb,
        )
        assertThat(EpisodeFileMatcher.select(pack, target(1, 1))).isEqualTo(0)
    }

    @Test
    fun `two qualities of one episode resolve to the larger`() {
        val pack = files("Show.S01E01.480p.mkv" to 400 * mb, "Show.S01E01.1080p.mkv" to 2 * gb)
        assertThat(EpisodeFileMatcher.select(pack, target(1, 1))).isEqualTo(1)
    }

    @Test
    fun `files named only by position are matched by natural order when the counts agree`() {
        val pack = files(
            "Show/Part 10.mkv" to gb,
            "Show/Part 2.mkv" to gb,
            "Show/Part 1.mkv" to gb,
        )
        // Natural order is Part 1, Part 2, Part 10 — not the lexical Part 1, Part 10, Part 2.
        assertThat(EpisodeFileMatcher.select(pack, target(1, 2, ordinal = 1, count = 3))).isEqualTo(1)
        assertThat(EpisodeFileMatcher.select(pack, target(1, 3, ordinal = 2, count = 3))).isEqualTo(0)
    }

    @Test
    fun `files named only by position are refused when the counts disagree`() {
        val pack = files("Show/Part 1.mkv" to gb, "Show/Part 2.mkv" to gb)
        assertThat(EpisodeFileMatcher.select(pack, target(1, 2, ordinal = 1, count = 3))).isNull()
    }

    @Test
    fun `a pack of another season never answers by position, even with the same number of episodes`() {
        val seasonOne = files("Show.S01E01.mkv" to gb, "Show.S01E02.mkv" to gb, "Show.S01E03.mkv" to gb)
        assertThat(EpisodeFileMatcher.select(seasonOne, target(2, 1, ordinal = 0, count = 3))).isNull()

        val numberedOnly = files("Ep01.mkv" to gb, "Ep02.mkv" to gb, "Ep03.mkv" to gb)
        assertThat(EpisodeFileMatcher.select(numberedOnly, target(1, 7, ordinal = 2, count = 3))).isNull()
    }

    @Test
    fun `an empty listing matches nothing`() {
        assertThat(EpisodeFileMatcher.select(emptyList(), target(1, 1))).isNull()
    }

    @Test
    fun `natural order compares digit runs as numbers`() {
        val sorted = listOf("e10", "e2", "e1", "E3").sortedWith(EpisodeFileMatcher.NaturalOrder)
        assertThat(sorted).containsExactly("e1", "e2", "E3", "e10").inOrder()
    }
}
