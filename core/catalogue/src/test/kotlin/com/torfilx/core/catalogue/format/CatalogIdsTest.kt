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
