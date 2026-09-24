package com.torfilx.core.ui.util

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.model.MediaItem
import org.junit.Test

class FormatTest {

    private val minute = 60_000L

    @Test
    fun `runtime renders hours and minutes without stray zeros`() {
        assertThat(Format.runtime(134 * minute)).isEqualTo("2h 14m")
        assertThat(Format.runtime(48 * minute)).isEqualTo("48m")
        assertThat(Format.runtime(120 * minute)).isEqualTo("2h")
        assertThat(Format.runtime(0)).isEmpty()
        assertThat(Format.runtime(null)).isEmpty()
    }

    @Test
    fun `runtime rounds to the nearest minute and carries into hours`() {
        // 59 min 40 s must read as 1h, not "0h 60m".
        assertThat(Format.runtime(59 * minute + 40_000)).isEqualTo("1h")
        assertThat(Format.runtime(29_000)).isEqualTo("0m")
    }

    @Test
    fun `timecode omits the hour for short content and clamps negatives`() {
        assertThat(Format.timecode(0)).isEqualTo("0:00")
        assertThat(Format.timecode(63_000)).isEqualTo("1:03")
        assertThat(Format.timecode(3_723_000)).isEqualTo("1:02:03")
        assertThat(Format.timecode(-5_000)).isEqualTo("0:00")
    }

    @Test
    fun `meta line skips whatever the server did not provide`() {
        val sparse = MediaItem(id = "m", title = "Untitled")
        assertThat(Format.metaLine(sparse)).isEmpty()

        val full = MediaItem(
            id = "m",
            title = "Movie",
            year = 2024,
            runtimeMs = 134 * minute,
            ageRating = "16+",
            genres = listOf("Action", "Sci-Fi", "Drama"),
        )
        assertThat(Format.metaLine(full)).isEqualTo("2024 · 2h 14m · 16+ · Action, Sci-Fi")
    }

    @Test
    fun `a show's meta line gives its seasons in place of a runtime`() {
        val show = MediaItem(
            id = "show-x-1959",
            title = "Show",
            year = 1959,
            runtimeMs = 25 * minute, // ignored for a show
            genres = listOf("Sci-Fi", "Drama"),
            kind = com.torfilx.core.model.MediaKind.SHOW,
            seasonCount = 5,
            episodeCount = 156,
        )
        assertThat(Format.metaLine(show)).isEqualTo("1959 · 5 seasons · Sci-Fi, Drama")
    }

    @Test
    fun `a single-season show counts its episodes, and an empty one says nothing`() {
        val base = MediaItem(id = "s", title = "S", kind = com.torfilx.core.model.MediaKind.SHOW)
        assertThat(Format.showLength(base.copy(seasonCount = 1, episodeCount = 8))).isEqualTo("8 episodes")
        assertThat(Format.showLength(base.copy(seasonCount = 1, episodeCount = 1))).isEqualTo("1 episode")
        assertThat(Format.showLength(base.copy(seasonCount = 0, episodeCount = 3))).isEqualTo("3 episodes")
        assertThat(Format.showLength(base)).isEmpty()
        assertThat(Format.metaLine(base)).isEmpty()
    }

    @Test
    fun `rating is hidden when the server sent nothing or zero`() {
        assertThat(Format.rating(8.44)).isEqualTo("8.4")
        assertThat(Format.rating(0.0)).isNull()
        assertThat(Format.rating(null)).isNull()
    }

    @Test
    fun `percent complete is clamped and safe for zero duration`() {
        assertThat(Format.percentComplete(30, 100)).isEqualTo(30)
        assertThat(Format.percentComplete(300, 100)).isEqualTo(100)
        assertThat(Format.percentComplete(10, 0)).isEqualTo(0)
    }

    @Test
    fun `sections are numbered in roman numerals, posters in two digits`() {
        assertThat((1..5).map(Format::sectionNumeral)).containsExactly("i.", "ii.", "iii.", "iv.", "v.").inOrder()
        assertThat(Format.sectionNumeral(9)).isEqualTo("ix.")
        assertThat(Format.sectionNumeral(14)).isEqualTo("xiv.")
        assertThat(Format.sectionNumeral(39)).isEqualTo("xxxix.")
        assertThat(Format.sectionNumeral(40)).isEqualTo("40.")
        assertThat(Format.sectionNumeral(0)).isEqualTo("0.")
        assertThat(Format.rank(1)).isEqualTo("01")
        assertThat(Format.rank(12)).isEqualTo("12")
        assertThat(Format.rank(100)).isEqualTo("100")
    }
}
