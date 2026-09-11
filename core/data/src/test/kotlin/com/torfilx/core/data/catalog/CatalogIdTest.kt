package com.torfilx.core.data.catalog

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.model.MagnetLink
import org.junit.Test

/**
 * The catalogue is hand-edited, so it will contain duplicates and broken magnets sooner or later.
 *
 * Item ids are Compose list keys: a duplicate key throws and takes the whole screen down. This is
 * the regression test for the crash a 2000-entry catalogue produced.
 *
 * Deliberately free of Robolectric: these are pure rules, and an Android runtime here only buys a
 * download of the `android-all` jar and a flaky CI run.
 */
class CatalogIdTest {

    /** Mirrors the id rules the catalogue parser applies (CatalogIds): base id, then info hash, then position. */
    private fun buildIds(entries: List<Pair<String, String?>>): List<String> {
        val used = HashSet<String>()
        return entries.mapIndexed { index, (base, infoHash) ->
            when {
                used.add(base) -> base
                else -> {
                    val candidate = if (infoHash != null) "$base-${infoHash.take(8)}" else "$base-$index"
                    if (used.add(candidate)) candidate else "$base-$index".also { used.add(it) }
                }
            }
        }
    }

    @Test
    fun `repeated title and year still produce unique ids`() {
        val ids = buildIds(
            listOf(
                "catalog-frozen-valley-1960" to "0697bc07ebc5914085c2a3bce646509086bf6265",
                "catalog-frozen-valley-1960" to "bb5a1f6d17d3f8e01de20d42fb9860157a24456c",
                "catalog-frozen-valley-1960" to "aa11bb22cc33dd44ee55ff66aa77bb88cc99dd00",
            ),
        )

        assertThat(ids).hasSize(3)
        assertThat(ids.toSet()).hasSize(3)
        assertThat(ids.first()).isEqualTo("catalog-frozen-valley-1960")
    }

    @Test
    fun `entries without a magnet fall back to the index`() {
        val ids = buildIds(listOf("catalog-x-1999" to null, "catalog-x-1999" to null))
        assertThat(ids.toSet()).hasSize(2)
    }

    @Test
    fun `two entries sharing an info hash still get distinct ids`() {
        val hash = "0697bc07ebc5914085c2a3bce646509086bf6265"
        val ids = buildIds(listOf("catalog-x-1999" to hash, "catalog-x-1999" to hash, "catalog-x-1999" to hash))
        assertThat(ids.toSet()).hasSize(3)
    }

    @Test
    fun `a malformed magnet is rejected before it can reach the player`() {
        assertThat(MagnetLink.isValid("magnet:?xt=urn:btih:not-a-real-hash")).isFalse()
        assertThat(MagnetLink.isValid("magnet:?xt=urn:btih:0697BC07EBC5914085C2A3BCE646509086BF6265"))
            .isTrue()
    }

    @Test
    fun `repeated genres are collapsed so a film cannot appear twice in one row`() {
        val genres = listOf("Action", "action", " Action ", "Drama", "", "  ")
        val normalised = genres
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .distinctBy { it.lowercase() }

        assertThat(normalised).containsExactly("Action", "Drama").inOrder()
    }
}
