package com.torfilx.core.model

/**
 * Lifetime record of what this device has given back for one title.
 *
 * Kept per info hash rather than per catalogue id, because a contribution outlives the thing that
 * produced it: the film can be evicted from disk, dropped from the catalogue, or renamed, and what
 * was shared still happened.
 */
data class TitleContribution(
    val infoHash: String,
    val title: String,
    val uploadedBytes: Long,
    val downloadedBytes: Long,
    val sizeBytes: Long,
    val firstSharedAtMs: Long,
    val lastActiveAtMs: Long,
    /** False once the data has been evicted — the record stays, the file is gone. */
    val stillOnDisk: Boolean,
) {
    /**
     * Uploaded relative to the size of the film, which is the figure that means something.
     *
     * "You have sent this film 2.3 times over" is legible; "4.8 GB" is not, unless you happen to know
     * how big the film was.
     */
    val copiesShared: Float
        get() = if (sizeBytes <= 0) 0f else uploadedBytes.toFloat() / sizeBytes

    val ratio: Float
        get() = if (downloadedBytes <= 0) 0f else uploadedBytes.toFloat() / downloadedBytes
}

/** One day's totals, for the chart. */
data class DailyContribution(
    /** Days since the epoch, so a day is one integer and sorting is free. */
    val epochDay: Long,
    val uploadedBytes: Long,
    val downloadedBytes: Long,
)

/** Bytes streamed in from peers and out to them over some period. */
data class TransferTotals(
    val downloadedBytes: Long = 0,
    val uploadedBytes: Long = 0,
)

/**
 * What Settings shows under "Data streamed": today, the last 30 days, and everything since the
 * record was last cleared.
 */
data class StreamedTotals(
    val today: TransferTotals = TransferTotals(),
    val last30Days: TransferTotals = TransferTotals(),
    val allTime: TransferTotals = TransferTotals(),
) {
    companion object {
        /**
         * Folds the per-day rollup and the per-title record into the three figures.
         *
         * All-time comes from the per-title rows rather than the days, because days older than the
         * retention window are pruned and titles are not.
         */
        fun from(days: List<DailyContribution>, titles: List<TitleContribution>, todayEpochDay: Long) = StreamedTotals(
            today = days.filter { it.epochDay == todayEpochDay }.total(),
            last30Days = days.filter { it.epochDay > todayEpochDay - DAYS_IN_MONTH_WINDOW }.total(),
            allTime = TransferTotals(
                downloadedBytes = titles.sumOf { it.downloadedBytes },
                uploadedBytes = titles.sumOf { it.uploadedBytes },
            ),
        )

        private fun List<DailyContribution>.total() = TransferTotals(
            downloadedBytes = sumOf { it.downloadedBytes },
            uploadedBytes = sumOf { it.uploadedBytes },
        )

        private const val DAYS_IN_MONTH_WINDOW = 30
    }
}

/**
 * Which parts of a film are on this device.
 *
 * [buckets] is a downsampled view of libtorrent's piece bitfield — one entry per segment the UI will
 * draw, never one per piece. A two-hour film can be several thousand pieces, and neither the flow
 * nor the composable has any use for that resolution.
 */
data class CachedParts(
    val buckets: List<Float>,
    val haveBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float get() = if (totalBytes <= 0) 0f else haveBytes.toFloat() / totalBytes

    companion object {
        /** Segments drawn for a piece strip. Chosen to be legible on a TV, not to be precise. */
        const val BUCKETS = 120
    }
}

/**
 * Folds libtorrent's session-scoped counter into a lifetime total.
 *
 * Pure, and unit-tested, because the two cases that matter cannot be observed on a television:
 *
 * - **Normal tick.** The counter only ever rises within a session, so the delta is the difference.
 * - **Session restart.** libtorrent starts counting from zero again, so a value *lower* than the last
 *   one seen is not a decrease — it is a fresh count, and the whole of it is new. Clamping that to
 *   zero (the obvious defensive move) would silently discard everything shared since the restart,
 *   which is precisely the bug that makes a contribution figure untrustworthy.
 *
 * @param lastSeen the previous reading within this process, or null on the first tick.
 * @param current what libtorrent reports now.
 * @return bytes to add to the lifetime total.
 */
fun contributionDelta(lastSeen: Long?, current: Long): Long = when {
    current < 0 -> 0L
    lastSeen == null -> current
    current >= lastSeen -> current - lastSeen
    else -> current
}
