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

    @Test
    fun `a long list of problems is capped so it still fits in a log line`() {
        val entries = (0 until 30).map { entry("", null) }
        val reported = problems(entries)
        assertThat(reported).hasSize(11)
        assertThat(reported.last()).isEqualTo("and 50 more")
    }
}
