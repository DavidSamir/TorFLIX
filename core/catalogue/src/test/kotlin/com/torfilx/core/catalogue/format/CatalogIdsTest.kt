package com.torfilx.core.catalogue.format

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The id rules. The derived rule must stay exactly what the app has always used, because watch
 * progress and My List are already stored under those ids on devices in the field.
 */
class CatalogIdsTest {

    private val hashA = "0697bc07ebc5914085c2a3bce646509086bf6265"
    private val hashB = "bb5a1f6d17d3f8e01de20d42fb9860157a24456c"

    private fun magnet(hash: String) = CatalogMagnetDto(quality = "720p", magnet = "magnet:?xt=urn:btih:$hash")

    @Test
    fun `derived ids follow the historical rule, then the hash, then the position`() {
        val used = HashSet<String>()
        assertThat(CatalogIds.derive("The Kid", "1921", hashA, 0, used)).isEqualTo("catalog-the-kid-1921")
        assertThat(CatalogIds.derive("The Kid", "1921", hashB, 1, used)).isEqualTo("catalog-the-kid-1921-bb5a1f6d")
        assertThat(CatalogIds.derive("The Kid", "1921", hashB, 2, used)).isEqualTo("catalog-the-kid-1921-2")
        assertThat(CatalogIds.derive("The Kid", "1921", null, 3, used)).isEqualTo("catalog-the-kid-1921-3")
        assertThat(used).hasSize(4)
    }

    @Test
    fun `slugs collapse punctuation and keep letters from any script`() {
        assertThat(CatalogIds.slug("Avengers: Endgame")).isEqualTo("avengers-endgame")
        assertThat(CatalogIds.slug("  --Amélie!!  ")).isEqualTo("amélie")
        assertThat(CatalogIds.baseId("Metropolis", null)).isEqualTo("catalog-metropolis-")
    }

    @Test
    fun `an explicit id wins over the derived one`() {
        val used = HashSet<String>()
        assertThat(CatalogIds.assign("the-kid", "The Kid", "1921", hashA, 0, used)).isEqualTo("the-kid")
        // The derived id is still free for another entry that needs it.
        assertThat(CatalogIds.assign(null, "The Kid", "1921", hashA, 1, used)).isEqualTo("catalog-the-kid-1921")
    }

    @Test
    fun `an explicit id that is already taken falls back to the derived id`() {
        val used = hashSetOf("the-kid")
        assertThat(CatalogIds.assign("the-kid", "The Kid", "1921", hashA, 4, used)).isEqualTo("catalog-the-kid-1921")
    }

    @Test
    fun `an unusable explicit id falls back to the derived id`() {
        listOf("a/b", "has space", "", "   ", "q?x", "50%").forEach { bad ->
            val used = HashSet<String>()
            assertThat(CatalogIds.assign(bad, "Nosferatu", "1922", hashA, 0, used)).isEqualTo("catalog-nosferatu-1922")
        }
    }

    @Test
    fun `pinning gives every titled entry the id the parser derived before`() {
        val entries = listOf(
            CatalogEntryDto(title = "Dup", year = "1999", magnets = listOf(magnet(hashA))),
            CatalogEntryDto(title = "   ", year = "2000"),
            CatalogEntryDto(title = " Dup ", year = "1999", magnets = listOf(magnet(hashA))),
            CatalogEntryDto(title = "Dup", year = "1999", magnets = listOf(magnet(hashA))),
            CatalogEntryDto(title = "Other"),
        )

        val pinned = CatalogIds.pin(entries)

        assertThat(pinned.map { it.id }).containsExactly(
            "catalog-dup-1999",
            null,
            "catalog-dup-1999-0697bc07",
            "catalog-dup-1999-3",
            "catalog-other-",
        ).inOrder()
        // Pinning adds ids and changes nothing else.
        assertThat(pinned.map { it.copy(id = null) }).isEqualTo(entries)
    }

    @Test
    fun `pinning keeps ids that are already there and is idempotent`() {
        val entries = listOf(
            CatalogEntryDto(id = "kept", title = "The Kid", year = "1921"),
            CatalogEntryDto(title = "The Gold Rush", year = "1925"),
        )
        val once = CatalogIds.pin(entries)
        assertThat(once.map { it.id }).containsExactly("kept", "catalog-the-gold-rush-1925").inOrder()
        assertThat(CatalogIds.pin(once)).isEqualTo(once)
    }

    @Test
    fun `a renamed title keeps its pinned id`() {
        val pinned = CatalogIds.pin(listOf(CatalogEntryDto(title = "The Kid", year = "1921")))
        val renamed = pinned.map { it.copy(title = "The Kid (Restored)", year = "1921/1971") }

        assertThat(CatalogIds.pin(renamed).single().id).isEqualTo("catalog-the-kid-1921")
    }

    @Test
    fun `validity rejects characters that break a route or a url`() {
        assertThat(CatalogIds.isValid("catalog-the-kid-1921")).isTrue()
        assertThat(CatalogIds.isValid("catalog-amélie-2001")).isTrue()
        listOf("", "a b", "a\tb", "a b", "a/b", "a?b", "a#b", "a&b", "a%b", "a\\b", "a\"b", "a'b", "a<b", "x".repeat(201))
            .forEach { assertThat(CatalogIds.isValid(it)).isFalse() }
    }

    // --- Shows -----------------------------------------------------------------------------------

    private fun episode(number: Int?, id: String? = null, hash: String? = null) = CatalogEpisodeDto(
        id = id,
        number = number,
        magnets = hash?.let { listOf(magnet(it)) }.orEmpty(),
    )

    private fun show(title: String, year: String?, vararg seasons: CatalogSeasonDto, id: String? = null) =
        CatalogEntryDto(id = id, type = CatalogEntryDto.TYPE_SHOW, title = title, year = year, seasons = seasons.toList())

    @Test
    fun `a show and its episodes get derived ids that cannot collide with films`() {
        val pinned = CatalogIds.pin(
            listOf(
                CatalogEntryDto(title = "The Twilight Zone", year = "1959"),
                show(
                    "The Twilight Zone",
                    "1959",
                    CatalogSeasonDto(number = 1, episodes = listOf(episode(1), episode(12))),
                    CatalogSeasonDto(number = 0, episodes = listOf(episode(1))),
                ),
            ),
        )

        assertThat(pinned[0].id).isEqualTo("catalog-the-twilight-zone-1959")
        assertThat(pinned[1].id).isEqualTo("show-the-twilight-zone-1959")
        assertThat(pinned[1].seasons[0].episodes.map { it.id })
            .containsExactly("show-the-twilight-zone-1959-s01e01", "show-the-twilight-zone-1959-s01e12").inOrder()
        assertThat(pinned[1].seasons[1].episodes.single().id).isEqualTo("show-the-twilight-zone-1959-s00e01")
    }

    @Test
    fun `episode numbers of three digits widen the id rather than truncate it`() {
        assertThat(CatalogIds.episodeBaseId("show-x-", 1, 123)).isEqualTo("show-x--s01e123")
        assertThat(CatalogIds.episodeBaseId("show-x-", 12, 3)).isEqualTo("show-x--s12e03")
    }

    @Test
    fun `two episodes given the same number get distinct ids in file order`() {
        val pinned = CatalogIds.pin(
            listOf(show("Dup", "2000", CatalogSeasonDto(number = 1, episodes = listOf(episode(1), episode(1), episode(1))))),
        )
        assertThat(pinned.single().seasons.single().episodes.map { it.id })
            .containsExactly("show-dup-2000-s01e01", "show-dup-2000-s01e01-2", "show-dup-2000-s01e01-3").inOrder()
    }

    @Test
    fun `two shows of the same name and year are told apart by their first episode's hash`() {
        val pinned = CatalogIds.pin(
            listOf(
                show("Same", "1960", CatalogSeasonDto(number = 1, episodes = listOf(episode(1, hash = hashA)))),
                show("Same", "1960", CatalogSeasonDto(number = 1, episodes = listOf(episode(1, hash = hashB)))),
            ),
        )
        assertThat(pinned.map { it.id }).containsExactly("show-same-1960", "show-same-1960-bb5a1f6d").inOrder()
        assertThat(pinned[1].seasons.single().episodes.single().id).isEqualTo("show-same-1960-bb5a1f6d-s01e01")
    }

    @Test
    fun `an explicit episode id already used by a film falls back to the derived one`() {
        val pinned = CatalogIds.pin(
            listOf(
                CatalogEntryDto(id = "taken", title = "Film"),
                show("S", "1990", CatalogSeasonDto(number = 1, episodes = listOf(episode(1, id = "taken")))),
            ),
        )
        assertThat(pinned[1].seasons.single().episodes.single().id).isEqualTo("show-s-1990-s01e01")
    }

    @Test
    fun `episodes without a usable number, or in a season without one, get no id`() {
        val pinned = CatalogIds.pin(
            listOf(
                show(
                    "S",
                    "1990",
                    CatalogSeasonDto(number = 1, episodes = listOf(episode(null), episode(0), episode(2))),
                    CatalogSeasonDto(number = null, episodes = listOf(episode(1))),
                    CatalogSeasonDto(number = -1, episodes = listOf(episode(1))),
                ),
            ),
        )
        val seasons = pinned.single().seasons
        assertThat(seasons[0].episodes.map { it.id }).containsExactly(null, null, "show-s-1990-s01e02").inOrder()
        assertThat(seasons[1].episodes.single().id).isNull()
        assertThat(seasons[2].episodes.single().id).isNull()
    }

    @Test
    fun `pinning a show keeps existing ids, is idempotent and changes nothing else`() {
        val entries = listOf(
            show(
                "S",
                "1990",
                CatalogSeasonDto(number = 1, episodes = listOf(episode(1, id = "kept-episode"), episode(2))),
                id = "kept-show",
            ),
        )
        val once = CatalogIds.pin(entries)
        assertThat(once.single().id).isEqualTo("kept-show")
        assertThat(once.single().seasons.single().episodes.map { it.id })
            .containsExactly("kept-episode", "kept-show-s01e02").inOrder()
        assertThat(CatalogIds.pin(once)).isEqualTo(once)
        val stripped = once.single().let { show ->
            show.copy(id = null, seasons = show.seasons.map { s -> s.copy(episodes = s.episodes.map { it.copy(id = null) }) })
        }
        assertThat(stripped.copy(id = null)).isEqualTo(
            entries.single().let { show ->
                show.copy(id = null, seasons = show.seasons.map { s -> s.copy(episodes = s.episodes.map { it.copy(id = null) }) })
            },
        )
    }

    @Test
    fun `a renamed and renumbered show keeps every pinned id`() {
        val pinned = CatalogIds.pin(listOf(show("Old Name", "1959", CatalogSeasonDto(number = 1, episodes = listOf(episode(1))))))
        val renamed = pinned.map { show ->
            show.copy(
                title = "New Name",
                year = "1960",
                seasons = show.seasons.map { s -> s.copy(number = 2, episodes = s.episodes.map { it.copy(number = 9) }) },
            )
        }
        val again = CatalogIds.pin(renamed).single()
        assertThat(again.id).isEqualTo("show-old-name-1959")
        assertThat(again.seasons.single().episodes.single().id).isEqualTo("show-old-name-1959-s01e01")
    }

    @Test
    fun `an entry of an unknown type is skipped by pinning, as the parser skips it`() {
        val pinned = CatalogIds.pin(
            listOf(
                CatalogEntryDto(type = "podcast", title = "Nope"),
                CatalogEntryDto(title = "Film", year = "1921"),
            ),
        )
        assertThat(pinned[0].id).isNull()
        assertThat(pinned[1].id).isEqualTo("catalog-film-1921")
    }

    @Test
    fun `a show's first info hash is its first episode's, and its own magnets are ignored`() {
        val entry = show(
            "S",
            "1990",
            CatalogSeasonDto(
                number = 1,
                episodes = listOf(
                    CatalogEpisodeDto(number = 1, magnets = listOf(CatalogMagnetDto(magnet = "magnet:?xt=urn:btih:bad"))),
                    episode(2, hash = hashB),
                ),
            ),
        ).copy(magnets = listOf(magnet(hashA)))
        assertThat(CatalogIds.firstInfoHash(entry)).isEqualTo(hashB)
    }

    @Test
    fun `type is read without regard to case or spacing`() {
        assertThat(CatalogEntryDto(type = " Show ").isShow).isTrue()
        assertThat(CatalogEntryDto(type = "MOVIE").hasKnownType).isTrue()
        assertThat(CatalogEntryDto(type = null).isShow).isFalse()
        assertThat(CatalogEntryDto(type = "series").hasKnownType).isFalse()
    }

    @Test
    fun `the first accepted info hash skips malformed magnets`() {
        val entry = CatalogEntryDto(
            title = "X",
            magnets = listOf(CatalogMagnetDto(magnet = "magnet:?xt=urn:btih:not-a-hash"), magnet(hashB)),
        )
        assertThat(CatalogIds.firstInfoHash(entry)).isEqualTo(hashB)
        assertThat(CatalogIds.firstInfoHash(CatalogEntryDto(title = "Y"))).isNull()
    }
}
