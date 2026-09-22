package com.torfilx.core.torrent

import com.torfilx.core.model.CachedParts
import com.torfilx.core.model.FileSelection
import kotlinx.coroutines.flow.Flow

/**
 * The app's BitTorrent capability.
 *
 * Two rules are baked into the contract rather than left to callers:
 * - nothing starts until the user has explicitly consented to sharing (uploading), because
 *   participating in a swarm means uploading to strangers;
 * - the engine never uses more disk than [StorageBudget] allows, computed from *currently free*
 *   space, so a full TV can never be the app's fault.
 */
interface TorrentEngine {

    /** Aggregate stats for the Settings screen. */
    val stats: Flow<SharingStats>

    /** Per-torrent status, keyed by info hash. */
    val torrents: Flow<List<TorrentStatus>>

    /** Starts the session. Safe to call repeatedly. */
    suspend fun start()

    /** Stops the session and releases the native handles. */
    suspend fun stop()

    /**
     * Prepares [magnet] for playback and returns a local HTTP URL the player can read.
     *
     * Downloading is sequential from the start of the chosen video file, so playback can begin long
     * before the whole file exists.
     *
     * @param selection which file to stream: the largest video, an episode's file preferred, or exactly
     *   one episode's file out of a season pack (see [FileSelection]).
     * @param displayName what the viewer calls this title, for the sharing figures; the release's file
     *   name otherwise.
     */
    suspend fun stream(
        magnet: String,
        selection: FileSelection = FileSelection.LargestVideo,
        displayName: String? = null,
    ): TorrentStream

    /** Stops streaming [infoHash]; it may keep seeding if the policy allows. */
    suspend fun stopStreaming(infoHash: String)

    /** Removes a torrent and deletes its data. */
    suspend fun remove(infoHash: String, deleteData: Boolean = true)

    /** Frees disk until usage is within the budget, oldest-touched torrents first. */
    suspend fun enforceStorageBudget()

    /**
     * Which parts of a title.s video file are on this device, downsampled for drawing.
     *
     * Null when the torrent is no longer in the session — the contribution record outlives the data,
     * so a row may legitimately have nothing to draw. Read on demand only: the bitfield is thousands
     * of entries and nothing should be paying for it while the screen is closed.
     */
    fun cachedParts(infoHash: String, buckets: Int): CachedParts?

    /** Stops the session and deletes every downloaded byte from disk. */
    suspend fun purgeAllData()

    /** True when the native library actually loaded on this device. */
    fun isAvailable(): Boolean
}
