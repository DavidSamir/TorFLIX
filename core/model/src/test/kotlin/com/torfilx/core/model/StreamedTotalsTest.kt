package com.torfilx.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The three "Data streamed" figures in Settings, from the per-day rollup and the per-title record. */
class StreamedTotalsTest {

    private val today = 20_000L

    private fun day(offset: Long, down: Long, up: Long) =
        DailyContribution(epochDay = today - offset, uploadedBytes = up, downloadedBytes = down)

    private fun title(down: Long, up: Long) = TitleContribution(
        infoHash = "hash$down$up",
        title = "Film",
        uploadedBytes = up,
        downloadedBytes = down,
        sizeBytes = 0,
        firstSharedAtMs = 0,
        lastActiveAtMs = 0,
        stillOnDisk = false,
    )

    @Test
    fun `today counts only today`() {
        val totals = StreamedTotals.from(
            days = listOf(day(0, down = 100, up = 10), day(1, down = 1_000, up = 1_000)),
            titles = emptyList(),
            todayEpochDay = today,
        )
        assertThat(totals.today).isEqualTo(TransferTotals(downloadedBytes = 100, uploadedBytes = 10))
    }

    @Test
    fun `the last 30 days include today and stop at the 30th day back`() {
        val totals = StreamedTotals.from(
            days = listOf(
                day(0, down = 1, up = 2),
                day(29, down = 10, up = 20),
                day(30, down = 100, up = 200), // the 31st day, outside the window
            ),
            titles = emptyList(),
            todayEpochDay = today,
        )
        assertThat(totals.last30Days).isEqualTo(TransferTotals(downloadedBytes = 11, uploadedBytes = 22))
    }

    @Test
    fun `all time comes from the titles, which outlive the pruned days`() {
        val totals = StreamedTotals.from(
            days = listOf(day(0, down = 5, up = 5)),
            titles = listOf(title(down = 4_000, up = 1_000), title(down = 6_000, up = 3_000)),
            todayEpochDay = today,
        )
        assertThat(totals.allTime).isEqualTo(TransferTotals(downloadedBytes = 10_000, uploadedBytes = 4_000))
    }

    @Test
    fun `nothing streamed reads as zero everywhere`() {
        assertThat(StreamedTotals.from(emptyList(), emptyList(), today)).isEqualTo(StreamedTotals())
    }
}
