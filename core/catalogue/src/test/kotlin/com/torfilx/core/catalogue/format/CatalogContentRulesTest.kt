package com.torfilx.core.catalogue.format

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.testing.TestCatalogues
import org.junit.Test

class CatalogContentRulesTest {

    private fun entry(title: String, id: String?) = CatalogEntryDto(id = id, title = title)

    private fun problems(entries: List<CatalogEntryDto>, declared: Int = entries.size, release: Boolean = true) =
        CatalogContentRules.problems(entries, declared, requireExplicitIds = release)

    @Test
    fun `a pinned catalogue is publishable`() {
        assertThat(problems(TestCatalogues.entries(5))).isEmpty()
    }

    @Test
    fun `a missing id is a problem for a release but not for a hand-edited file`() {
        val entries = listOf(entry("A", null))
        assertThat(problems(entries, release = true)).hasSize(1)
        assertThat(problems(entries, release = false)).isEmpty()
    }

    @Test
    fun `duplicate ids are reported with both positions`() {
        val reported = problems(listOf(entry("A", "same"), entry("B", "other"), entry("C", "same")))
        assertThat(reported).containsExactly("entries 0 and 2 share the id \"same\"")
    }

    @Test
    fun `blank titles and unusable ids are reported`() {
        val reported = problems(listOf(entry(" ", "ok-id"), entry("B", "bad id")))
        assertThat(reported).containsExactly(
            "entry 0 has no title",
            "entry 1 has an invalid id \"bad id\"",
        )
    }

    @Test
    fun `a declared title count that disagrees with the entries is reported`() {
        val reported = problems(TestCatalogues.entries(2), declared = 3)
        assertThat(reported.single()).contains("declares 3")
    }

    // --- Shows -----------------------------------------------------------------------------------

    private fun pinnedShow(edit: (CatalogEntryDto) -> CatalogEntryDto = { it }): List<CatalogEntryDto> =
        listOf(edit(CatalogIds.pin(listOf(TestCatalogues.show())).single()))

    private fun CatalogEntryDto.editSeason(index: Int, edit: (CatalogSeasonDto) -> CatalogSeasonDto) =
        copy(seasons = seasons.mapIndexed { i, s -> if (i == index) edit(s) else s })

    @Test
    fun `a pinned catalogue of films and shows is publishable`() {
        assertThat(problems(TestCatalogues.mixed(films = 3, shows = 2))).isEmpty()
        assertThat(problems(CatalogIds.pin(listOf(TestCatalogues.show(withSpecials = true))))).isEmpty()
    }

    @Test
    fun `an unknown type is refused`() {
        assertThat(problems(listOf(CatalogEntryDto(id = "x", type = "podcast", title = "A"))).single())
            .contains("unknown type \"podcast\"")
    }

    @Test
    fun `a film with seasons is told it needs the show type`() {
        val film = CatalogEntryDto(id = "x", title = "A", seasons = TestCatalogues.show().seasons)
        assertThat(problems(listOf(film)).single()).contains("is a film but has seasons")
    }

    @Test
    fun `a show with magnets of its own, or with no seasons, is refused`() {
        val withMagnets = pinnedShow { it.copy(magnets = listOf(CatalogMagnetDto(magnet = TestCatalogues.magnet(1)))) }
        assertThat(problems(withMagnets).single()).contains("its magnets belong to its episodes")

        val empty = pinnedShow { it.copy(seasons = emptyList()) }
        assertThat(problems(empty).single()).contains("a show with no seasons")
    }

    @Test
    fun `seasons need a number of zero or more, unique within the show, and at least one episode`() {
        assertThat(problems(pinnedShow { s -> s.editSeason(1) { it.copy(number = null) } }).single()).contains("no valid number")
        assertThat(problems(pinnedShow { s -> s.editSeason(1) { it.copy(number = -2) } }).single()).contains("no valid number")
        assertThat(problems(pinnedShow { s -> s.editSeason(1) { it.copy(number = 1) } })).contains(
            "entry 0 (\"Fixture Show\") has two seasons numbered 1",
        )
        assertThat(problems(pinnedShow { s -> s.editSeason(0) { it.copy(episodes = emptyList()) } }).single())
            .contains("season 1 has no episodes")
    }

    @Test
    fun `episodes need a number of one or more, unique within the season`() {
        val unnumbered = pinnedShow { s -> s.editSeason(0) { it.copy(episodes = it.episodes.mapIndexed { i, e -> if (i == 0) e.copy(number = 0) else e }) } }
        assertThat(problems(unnumbered).single()).contains("episode 1 in the file has no valid number")

        val duplicate = pinnedShow { s -> s.editSeason(0) { it.copy(episodes = it.episodes.map { e -> e.copy(number = 1) }) } }
        assertThat(problems(duplicate)).contains("entry 0 (\"Fixture Show\") has two episodes numbered S1 E1")
    }

    @Test
    fun `every episode needs an id in a release, and it must be unique across the whole catalogue`() {
        val unpinned = listOf(TestCatalogues.show().copy(id = "show-x"))
        assertThat(problems(unpinned, release = true)).hasSize(6)
        assertThat(problems(unpinned, release = true).first()).isEqualTo("entry 0 (\"Fixture Show\") S1 E1 has no id")
        assertThat(problems(unpinned, release = false)).isEmpty()

        val clash = listOf(entry("A", "same")) + pinnedShow { s -> s.editSeason(0) { season -> season.copy(episodes = season.episodes.mapIndexed { i, e -> if (i == 0) e.copy(id = "same") else e }) } }
        assertThat(problems(clash).single()).isEqualTo("entry 0 and entry 1 S1 E1 share the id \"same\"")
    }

    @Test
    fun `an unusable episode id is reported where it sits`() {
        val bad = pinnedShow { s -> s.editSeason(1) { season -> season.copy(episodes = season.episodes.mapIndexed { i, e -> if (i == 2) e.copy(id = "a b") else e }) } }
        assertThat(problems(bad).single()).isEqualTo("entry 0 S2 E3 has an invalid id \"a b\"")
    }

    @Test
    fun `a show over the episode cap is refused`() {
        val huge = pinnedShow().single().let { show ->
            CatalogIds.pin(
                listOf(
                    show.copy(
                        seasons = listOf(
                            CatalogSeasonDto(
                                number = 1,
                                episodes = (1..CatalogRelease.MAX_EPISODES_PER_SHOW + 1).map { CatalogEpisodeDto(number = it) },
                            ),
                        ),
                    ),
                ),
            )
        }
        assertThat(problems(huge).single()).contains("the limit is ${CatalogRelease.MAX_EPISODES_PER_SHOW}")
    }

    @Test
    fun `a title count mismatch in a file with shows points at the likely cause`() {
        val reported = problems(TestCatalogues.mixed(), declared = 5)
        assertThat(reported.single()).contains("a \"title\" key inside a season or episode")
        assertThat(problems(TestCatalogues.entries(2), declared = 3).single()).doesNotContain("season")
    }

    @Test
    fun `episodes are counted across every show and never in films`() {
        assertThat(CatalogContentRules.episodeCount(TestCatalogues.mixed(films = 4, shows = 2, seasons = 3, episodesPerSeason = 2)))
            .isEqualTo(12)
        assertThat(CatalogContentRules.episodeCount(TestCatalogues.entries(3))).isEqualTo(0)
    }

    @Test
    fun `a long list of problems is capped so it still fits in a log line`() {
        val entries = (0 until 30).map { entry("", null) }
        val reported = problems(entries)
        assertThat(reported).hasSize(11)
        assertThat(reported.last()).isEqualTo("and 50 more")
    }
}
