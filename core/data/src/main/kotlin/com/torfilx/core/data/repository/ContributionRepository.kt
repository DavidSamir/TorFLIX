package com.torfilx.core.data.repository

import com.torfilx.core.common.di.ApplicationScope
import com.torfilx.core.common.di.Dispatcher
import com.torfilx.core.common.di.TorfilxDispatcher
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.data.database.ContributionDao
import com.torfilx.core.model.DailyContribution
import com.torfilx.core.model.StreamedTotals
import com.torfilx.core.model.TitleContribution
import com.torfilx.core.model.contributionDelta
import com.torfilx.core.torrent.TorrentStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Contribution"

/**
 * Lifetime record of what this device has given back to the swarm.
 *
 * libtorrent's counters are per *session*: they start at zero every launch, and a torrent that is
 * evicted takes its numbers with it. Left as-is, the sharing figures in Settings tell someone who
 * has seeded forty gigabytes that they seeded a couple of hundred megabytes. This turns those
 * volatile readings into a total that survives restarts and eviction.
 *
 * **Cost.** No polling of its own. The engine already samples torrent status once a second while a
 * session is up; [record] hooks into that existing tick and does one subtraction per active torrent
 * — and active torrents are capped at a handful by the engine's own limits. Deltas accumulate in
 * memory and reach the database at most once every [FLUSH_INTERVAL_MS]; writing per tick would be
 * tens of thousands of writes a day on slow flash, for numbers nobody is reading most of the time.
 */
@Singleton
class ContributionRepository @Inject constructor(
    private val dao: ContributionDao,
    @ApplicationScope private val scope: CoroutineScope,
    @Dispatcher(TorfilxDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /** Last reading seen this process, per info hash. Not persisted — it is session state. */
    private val lastUploaded = HashMap<String, Long>()
    private val lastDownloaded = HashMap<String, Long>()

    /** Deltas accumulated since the last flush. */
    private val pending = HashMap<String, Pending>()
    private val flushLock = Mutex()

    @Volatile
    private var lastFlushAtMs = 0L

    private class Pending(
        val title: String,
        val sizeBytes: Long,
        var uploaded: Long,
        var downloaded: Long,
        var onDisk: Boolean,
    )

    /**
     * Folds one status sample into the running totals.
     *
     * Called from the engine's existing status tick. Cheap and synchronous by design — it must never
     * be the reason a status poll gets slower.
     *
     * @param consented recording is skipped entirely without sharing consent. With upload throttled
     *   to nothing there is nothing to record, and keeping a per-title log of what someone shared
     *   when they have not agreed to share would be both wrong and intrusive.
     */
    fun record(statuses: List<TorrentStatus>, consented: Boolean, nowMs: Long) {
        if (!consented || statuses.isEmpty()) return

        synchronized(pending) {
            statuses.forEach { status ->
                val up = contributionDelta(lastUploaded[status.infoHash], status.totalUploadedBytes)
                val down = contributionDelta(lastDownloaded[status.infoHash], status.totalDownloadedBytes)
                lastUploaded[status.infoHash] = status.totalUploadedBytes
                lastDownloaded[status.infoHash] = status.totalDownloadedBytes
                if (up == 0L && down == 0L) return@forEach

                val entry = pending.getOrPut(status.infoHash) {
                    Pending(status.name, status.sizeBytes, 0, 0, onDisk = true)
                }
                entry.uploaded += up
                entry.downloaded += down
                entry.onDisk = true
            }
        }

        if (nowMs - lastFlushAtMs >= FLUSH_INTERVAL_MS) {
            lastFlushAtMs = nowMs
            scope.launch { flush(nowMs) }
        }
    }

    /**
     * Writes accumulated deltas out. Safe to call at any time — on app stop, for instance, so a
     * session's last half-minute is not lost when the process goes away.
     */
    suspend fun flush(nowMs: Long) = withContext(ioDispatcher) {
        val batch = synchronized(pending) {
            if (pending.isEmpty()) return@synchronized emptyMap<String, Pending>()
            val copy = HashMap(pending)
            pending.clear()
            copy
        }
        if (batch.isEmpty()) return@withContext

        // One lock around the write so two flushes cannot interleave and double-count a day.
        flushLock.withLock {
            runCatching {
                var dayUp = 0L
                var dayDown = 0L
                batch.forEach { (infoHash, entry) ->
                    dao.accumulate(
                        infoHash = infoHash,
                        title = entry.title,
                        uploaded = entry.uploaded,
                        downloaded = entry.downloaded,
                        sizeBytes = entry.sizeBytes,
                        nowMs = nowMs,
                        onDisk = entry.onDisk,
                    )
                    dayUp += entry.uploaded
                    dayDown += entry.downloaded
                }
                if (dayUp > 0 || dayDown > 0) {
                    val day = localEpochDay(nowMs)
                    dao.accumulateDay(day, dayUp, dayDown)
                    dao.pruneDaysBefore(day - RETAINED_DAYS)
                }
            }.onFailure {
                // Losing a batch of counters is a cosmetic loss; crashing the status tick is not.
                TorfilxLog.w(TAG, "Could not write contribution totals", it)
            }
        }
    }

    /** Reconciles the on-disk flags with what the engine actually still holds. */
    suspend fun syncOnDisk(presentInfoHashes: List<String>) = withContext(ioDispatcher) {
        runCatching {
            dao.clearOnDiskFlags()
            if (presentInfoHashes.isNotEmpty()) dao.markOnDisk(presentInfoHashes)
        }.onFailure { TorfilxLog.w(TAG, "Could not reconcile on-disk flags", it) }
        Unit
    }

    val contributions: Flow<List<TitleContribution>> = dao.observeAll().map { rows ->
        rows.map { row ->
            TitleContribution(
                infoHash = row.infoHash,
                title = row.title,
                uploadedBytes = row.uploadedBytes,
                downloadedBytes = row.downloadedBytes,
                sizeBytes = row.sizeBytes,
                firstSharedAtMs = row.firstSharedAtMs,
                lastActiveAtMs = row.lastActiveAtMs,
                stillOnDisk = row.stillOnDisk,
            )
        }
    }

    /** Daily totals for the chart, oldest first. */
    fun daily(nowMs: Long, days: Int = CHART_DAYS): Flow<List<DailyContribution>> =
        dao.observeDaysSince(localEpochDay(nowMs) - days + 1).map { rows ->
            rows.map { DailyContribution(it.epochDay, it.uploadedBytes, it.downloadedBytes) }
        }

    /**
     * Streamed in and out today, over the last 30 days, and since the record was last cleared.
     *
     * Follows the database, so it moves when [flush] writes: every [FLUSH_INTERVAL_MS] while
     * something is streaming or seeding.
     */
    fun streamedTotals(nowMs: Long): Flow<StreamedTotals> =
        combine(daily(nowMs, CHART_DAYS), contributions) { days, titles ->
            StreamedTotals.from(days, titles, todayEpochDay = localEpochDay(nowMs))
        }

    /** Erases the whole record. Wired to "Clear data" — a log of what you seeded must be erasable. */
    suspend fun clear() = withContext(ioDispatcher) {
        synchronized(pending) {
            pending.clear()
            lastUploaded.clear()
            lastDownloaded.clear()
        }
        runCatching {
            dao.clear()
            dao.clearDays()
        }.onFailure { TorfilxLog.w(TAG, "Could not clear contribution history", it) }
        Unit
    }

    companion object {
        /** Local calendar day, so "today" on the chart matches the viewer's day, not UTC's. */
        fun localEpochDay(nowMs: Long): Long {
            val offset = java.util.TimeZone.getDefault().getOffset(nowMs)
            return Math.floorDiv(nowMs + offset, MILLIS_PER_DAY)
        }

        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        const val FLUSH_INTERVAL_MS = 30_000L
        const val RETAINED_DAYS = 90L
        const val CHART_DAYS = 30
    }
}
