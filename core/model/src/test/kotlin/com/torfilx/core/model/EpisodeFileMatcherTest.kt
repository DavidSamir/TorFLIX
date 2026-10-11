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
        assertThat(EpisodeFileMatcher.select(files("Show.S01E03-1080.mkv" to gb), target(1, 50))).isNull()
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
    fun `numbered files use their numbers rather than their positions even when counts agree`() {
        val pack = files(
            "Show/Part 10.mkv" to gb,
            "Show/Part 2.mkv" to gb,
            "Show/Part 1.mkv" to gb,
        )
        assertThat(EpisodeFileMatcher.select(pack, target(1, 2, ordinal = 1, count = 3))).isEqualTo(1)
        assertThat(EpisodeFileMatcher.select(pack, target(1, 3, ordinal = 2, count = 3))).isNull()
        assertThat(EpisodeFileMatcher.select(pack, target(1, 10))).isEqualTo(0)
    }

    @Test
    fun `a partial numbered pack still plays the episodes it actually contains`() {
        val pack = files("Show/Part 1.mkv" to gb, "Show/Part 2.mkv" to gb)
        assertThat(EpisodeFileMatcher.select(pack, target(1, 2, ordinal = 1, count = 3))).isEqualTo(1)
        assertThat(EpisodeFileMatcher.select(pack, target(1, 3, ordinal = 2, count = 3))).isNull()
    }

    @Test
    fun `full show folders scope repeated episode numbers to their own seasons`() {
        val pack = files(
            "Arcane (2021)/Season 1/E01 - Pilot.mkv" to gb,
            "Arcane (2021)/Season 2/E01 - Return.mkv" to gb,
            "Arcane (2021)/Season 2/E02 - Next.mkv" to gb,
        )
        assertThat(EpisodeFileMatcher.select(pack, target(1, 1))).isEqualTo(0)
        assertThat(EpisodeFileMatcher.select(pack, target(2, 1))).isEqualTo(1)
        assertThat(EpisodeFileMatcher.select(pack, target(2, 2))).isEqualTo(2)
        assertThat(EpisodeFileMatcher.select(pack, target(3, 1))).isNull()
    }

    @Test
    fun `season folders work with windows paths bare numbers and specials`() {
        val pack = files(
            "Show\\S01\\01.mkv" to gb,
            "Show\\S02\\02 - Title.mkv" to gb,
            "Show\\Specials\\01.mkv" to 5 * mb,
        )
        assertThat(EpisodeFileMatcher.select(pack, target(1, 1))).isEqualTo(0)
        assertThat(EpisodeFileMatcher.select(pack, target(2, 2))).isEqualTo(1)
        assertThat(EpisodeFileMatcher.select(pack, target(0, 1))).isEqualTo(2)
        assertThat(EpisodeFileMatcher.select(pack, target(1, 2))).isNull()
    }

    @Test
    fun `word forms and delimited episode numbers work without interpreting release years`() {
        val pack = files(
            "Arcane Season 2 Episode 1 1080p.mkv" to gb,
            "Season 2/Arcane - 02 - Title.mkv" to gb,
            "Season 2/[03] Title.mkv" to gb,
            "Arcane 2021 1080p.mkv" to gb,
        )
        assertThat(EpisodeFileMatcher.select(pack, target(2, 1))).isEqualTo(0)
        assertThat(EpisodeFileMatcher.select(pack, target(1, 1))).isNull()
        assertThat(EpisodeFileMatcher.select(pack, target(2, 2))).isEqualTo(1)
        assertThat(EpisodeFileMatcher.select(pack, target(2, 3))).isEqualTo(2)
        assertThat(EpisodeFileMatcher.select(pack, target(2, 108))).isNull()
    }

    @Test
    fun `file codes override folders and folder episode codes never label all their children`() {
        val pack = files("Season 1/Show.S02E03.mkv" to gb, "Show.S01E01/Unknown.mkv" to gb)
        assertThat(EpisodeFileMatcher.select(pack, target(2, 3))).isEqualTo(0)
        assertThat(EpisodeFileMatcher.select(pack, target(1, 1))).isNull()
    }

    @Test
    fun `an unscoped episode number is ambiguous in a multi season listing`() {
        val pack = files("Season 1/E01.mkv" to gb, "Season 2/E01.mkv" to gb, "E02.mkv" to gb)
        assertThat(EpisodeFileMatcher.select(pack, target(1, 2))).isNull()
        assertThat(EpisodeFileMatcher.select(pack, target(2, 2))).isNull()
    }

    @Test
    fun `episode chains name individual episodes and ranges name every contained episode`() {
        val chain = files("Show.S01E01E03E05.mkv" to gb)
        for (episode in listOf(1, 3, 5)) {
            assertThat(EpisodeFileMatcher.select(chain, target(1, episode))).isEqualTo(0)
        }
        assertThat(EpisodeFileMatcher.select(chain, target(1, 2))).isNull()
        val range = files("Show.S01E01-E03.mkv" to gb)
        for (episode in 1..3) assertThat(EpisodeFileMatcher.select(range, target(1, episode))).isEqualTo(0)
        assertThat(EpisodeFileMatcher.select(files("Season 1/E01-E03.mkv" to gb), target(1, 2))).isEqualTo(0)
        assertThat(EpisodeFileMatcher.select(files("Show.1x01-03.mkv" to gb), target(1, 2))).isEqualTo(0)
    }

    @Test
    fun `extras folders never participate and short legitimate episodes are retained`() {
        val pack = files(
            "Show.S01E01.mkv" to gb,
            "Show.S01E02.mkv" to 5 * mb,
            "Extras/Show.S01E03.mkv" to 2 * gb,
            "Sample/Show.S01E04.mkv" to gb,
        )
        assertThat(EpisodeFileMatcher.select(pack, target(1, 2))).isEqualTo(1)
        assertThat(EpisodeFileMatcher.select(pack, target(1, 3))).isNull()
        assertThat(EpisodeFileMatcher.select(pack, target(1, 4))).isNull()
        assertThat(EpisodeFileMatcher.select(files("Trailer Park Boys.S01E01.mkv" to gb), target(1, 1))).isEqualTo(0)
    }

    @Test
    fun `an unlabelled collection is not ordered by guesswork`() {
        val pack = files("Pilot.mkv" to gb, "Finale.mkv" to gb)
        assertThat(EpisodeFileMatcher.select(pack, target(1, 1, count = 2))).isNull()
    }

    @Test
    fun `show wide packs require season evidence for episode only names`() {
        val pack = files("E01.mkv" to gb, "E02.mkv" to gb)
        assertThat(EpisodeFileMatcher.select(pack, target(1, 1).copy(allowUnscopedEpisodeNumbers = false))).isNull()
        val scoped = files("Season 2/E01.mkv" to gb)
        assertThat(EpisodeFileMatcher.select(scoped, target(2, 1).copy(allowUnscopedEpisodeNumbers = false))).isEqualTo(0)
    }

    @Test
    fun `arcane style separate season releases match codes and reject the other season`() {
        val seasonOne = files(
            "Arcane (2021) Season 1 S01 (1080p NF WEB-DL x265 HEVC 10bit EAC3)/Arcane.S01E01.mkv" to gb,
            "Arcane (2021) Season 1 S01 (1080p NF WEB-DL x265 HEVC 10bit EAC3)/Arcane.S01E02.mkv" to gb,
        )
        val seasonTwo = files("Arcane.S02.COMPLETE.REPACK.1080p.NF.WEB-DL.DDP5.1.Atmos.H.264-FL/Arcane.S02E01.mkv" to gb)
        assertThat(EpisodeFileMatcher.select(seasonOne, target(1, 2))).isEqualTo(1)
        assertThat(EpisodeFileMatcher.select(seasonOne, target(2, 1))).isNull()
        assertThat(EpisodeFileMatcher.select(seasonTwo, target(2, 1))).isEqualTo(0)
        assertThat(EpisodeFileMatcher.select(seasonTwo, target(1, 1))).isNull()
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
