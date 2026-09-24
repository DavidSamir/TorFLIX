package com.torfilx.core.data.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Overviews copied from TVmaze carry HTML; the screen must only ever see the prose. */
class CatalogTextTest {

    @Test
    fun `tags go and breaks become spaces`() {
        assertThat("<p><b>Ted Lasso </b>centers on an idealistic coach.</p>".asPlainText())
            .isEqualTo("Ted Lasso centers on an idealistic coach.")
        assertThat("<p>One.</p><p>Two.</p>".asPlainText()).isEqualTo("One. Two.")
        assertThat("First line<br/>second line".asPlainText()).isEqualTo("First line second line")
        assertThat("<i>Ted</i>'s team".asPlainText()).isEqualTo("Ted's team")
    }

    @Test
    fun `entities are decoded once`() {
        assertThat("Tom &amp; Jerry &quot;live&quot; &#39;99".asPlainText()).isEqualTo("Tom & Jerry \"live\" '99")
        assertThat("&amp;lt; stays text".asPlainText()).isEqualTo("&lt; stays text")
    }

    @Test
    fun `plain text is only trimmed, and nothing readable is null`() {
        assertThat("  Tom & Jerry < 3 ".asPlainText()).isEqualTo("Tom & Jerry < 3")
        assertThat("A < B and C > D".asPlainText()).isEqualTo("A < B and C > D")
        assertThat("<p> </p>".asPlainText()).isNull()
        assertThat("   ".asPlainText()).isNull()
        assertThat((null as String?).asPlainText()).isNull()
    }
}
