package com.torfilx.core.model

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.model.EpisodeFileMatcher.Candidate
import com.torfilx.core.model.EpisodeFileMatcher.Target
import org.junit.Test

/** Supplied magnets are real inputs; generated listings are scenarios, not fetched torrent metadata. */
class EpisodePackMatrixTest {
    private val magnets = checkNotNull(javaClass.getResourceAsStream("/torrent-magnets.txt"))
        .bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
    private val seasons = listOf(
        listOf(2), listOf(1), (1..9).toList(), (1..8).toList(), listOf(9), listOf(11),
        listOf(10), (1..10).toList(), listOf(1), listOf(2), listOf(3), listOf(5), listOf(4),
        listOf(1), (1..8).toList(),
    )
    private val gb = 1_000_000_000L

    @Test
    fun `all supplied magnets parse and decoded release names survive filename variations`() {
        assertThat(magnets).hasSize(15)
        magnets.forEachIndexed { release, magnet ->
            assertThat(MagnetLink.isValid(magnet)).isTrue()
            val root = checkNotNull(MagnetLink.displayName(magnet))
            for (style in 0..5) {
                val files = seasons[release].flatMap { season ->
                    (1..12).map { episode ->
                        val path = when (style) {
                            0 -> "$root/Show.S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}.1080p.mkv"
                            1 -> "$root/Season $season/Episode $episode.mp4"
                            2 -> "$root/S${season.toString().padStart(2, '0')}/$episode - Name.ts"
                            3 -> "$root/Season $season/[$episode] Name.m2ts"
                            4 -> "$root/Season $season/Show - $episode - Name.MKV"
                            else -> "$root\\Season $season\\Ep.$episode.mkv"
                        }
                        Candidate(season * 100 + episode, path, gb)
                    }
                }.reversed() // Torrent file order must never decide episode order.
                for (season in seasons[release]) {
                    for (episode in 1..12) {
                        val target = Target(season, episode, episode - 1, 12, allowUnscopedEpisodeNumbers = false)
                        assertThat(EpisodeFileMatcher.select(files, target)).isEqualTo(season * 100 + episode)
                    }
                    assertThat(EpisodeFileMatcher.select(files, Target(season, 13, 12, 13))).isNull()
                }
                assertThat(EpisodeFileMatcher.select(files, Target(99, 1, 0, 12))).isNull()
            }
        }
    }

    @Test
    fun `season lists and ranges on the root do not assign unscoped files to season one`() {
        for (root in listOf("Show Season 1 2 3", "Show Season 1, 2, 3", "Show Season 1-9", "Show S01-S09")) {
            val files = listOf(Candidate(0, "$root/E01.mkv", gb))
            assertThat(EpisodeFileMatcher.select(files, Target(1, 1, 0, 1))).isNull()
        }
    }

    @Test
    fun `episode codes embedded in longer identifiers are refused`() {
        for (name in listOf("Show.S01E01abc.mkv", "Show.1x01abc.mkv", "Season 1/E01abc.mkv")) {
            assertThat(EpisodeFileMatcher.select(listOf(Candidate(0, name, gb)), Target(1, 1, 0, 1))).isNull()
        }
    }

    @Test
    fun `partial mixed packs preserve gaps even with extras and duplicate quality files`() {
        val files = listOf(
            Candidate(5, "Season 2/E03.mkv", gb),
            Candidate(4, "Season 2/E01.mkv", gb),
            Candidate(3, "Season 1/E03.480p.mkv", gb / 2),
            Candidate(2, "Season 1/E03.1080p.mkv", gb),
            Candidate(1, "Extras/Season 1/E02.mkv", gb * 2),
            Candidate(0, "Season 1/E01.mkv", gb),
        )
        assertThat(EpisodeFileMatcher.select(files, Target(1, 3, 2, 3))).isEqualTo(2)
        assertThat(EpisodeFileMatcher.select(files, Target(2, 3, 2, 3))).isEqualTo(5)
        for (season in 1..2) assertThat(EpisodeFileMatcher.select(files, Target(season, 2, 1, 3))).isNull()
    }
}
