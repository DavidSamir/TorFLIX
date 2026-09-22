package com.torfilx.core.data.torrent

import com.torfilx.core.common.di.ApplicationScope
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.data.catalog.CatalogueSessionGate
import com.torfilx.core.data.repository.ContributionRepository
import com.torfilx.core.data.settings.SettingsRepository
import com.torfilx.core.model.FileSelection
import com.torfilx.core.torrent.LibTorrentEngine
import com.torfilx.core.torrent.SharingConsentProvider
import com.torfilx.core.torrent.SharingStats
import com.torfilx.core.torrent.StorageBudget
import com.torfilx.core.torrent.TorrentConfigProvider
import com.torfilx.core.torrent.TorrentEngine
import com.torfilx.core.torrent.TorrentStream
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TorrentCoord"

/**
 * Applies user settings to the torrent engine and exposes it to the rest of the app.
 *
 * The engine deliberately knows nothing about DataStore; this is the only place where the sharing
 * consent, the seeding switch and the storage budget are translated into engine behaviour.
 */
@Singleton
class TorrentCoordinator @Inject constructor(
    private val engine: TorrentEngine,
    private val libTorrentEngine: LibTorrentEngine,
    private val settingsRepository: SettingsRepository,
    private val contributionRepository: ContributionRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : CatalogueSessionGate {

    @Volatile
    private var lastOnDiskSyncMs: Long = 0L

    val stats: Flow<SharingStats> = engine.stats

    /** Per-torrent status, so the player can show peers/speed/progress for the title it is streaming. */
    val torrents: Flow<List<com.torfilx.core.torrent.TorrentStatus>> = engine.torrents

    init {
        settingsRepository.sharingConsent
            .onEach { consented ->
                settingsRepository.cachedSharingConsent = consented
                libTorrentEngine.onConsentChanged(consented)
                if (consented) {
                    warmUp()
                } else {
                    // Withdrawing consent stops sharing immediately and gives the disk back. The
                    // session goes with it, so a later re-consent must warm a fresh one.
                    warmedUp = false
                    runCatching { engine.stop() }
                        .onFailure { TorfilxLog.w(TAG, "Could not stop engine after consent removal", it) }
                }
            }
            .launchIn(scope)

        settingsRepository.storageFraction
            .onEach { fraction ->
                libTorrentEngine.storageBudget = StorageBudget(fractionOfFree = fraction)
                runCatching { engine.enforceStorageBudget() }
            }
            .launchIn(scope)

        // Keep the engine's synchronous config snapshot current. These take effect on the next play;
        // they are not applied mid-stream because they change how a session is built.
        settingsRepository.settings
            .onEach { s ->
                settingsRepository.cachedUseDht = s.useDht
                settingsRepository.cachedUseExtraTrackers = s.useExtraTrackers
                settingsRepository.cachedMetadataTimeoutSeconds = s.metadataTimeout.seconds
            }
            .launchIn(scope)

        // Fold the engine's session-scoped counters into a lifetime record.
        //
        // Deliberately attached to the status flow the engine already emits once a second rather
        // than given a loop of its own: the contribution record must cost a subtraction per active
        // torrent, not a second timer competing for a very small CPU. Catalogue torrents are not in
        // this flow, so they never count as titles shared.
        engine.torrents
            .onEach { statuses ->
                val now = System.currentTimeMillis()
                contributionRepository.record(
                    statuses = statuses,
                    consented = settingsRepository.cachedSharingConsent,
                    nowMs = now,
                )
                // Reconciled rarely: this is only about whether a row can still show a piece strip,
                // and it costs two small writes.
                if (now - lastOnDiskSyncMs >= ON_DISK_SYNC_INTERVAL_MS) {
                    lastOnDiskSyncMs = now
                    contributionRepository.syncOnDisk(statuses.map { it.infoHash })
                }
            }
            .launchIn(scope)
    }

    fun isAvailable(): Boolean = engine.isAvailable()

    /**
     * Brings the session up before anything is played.
     *
     * The DHT takes tens of seconds to bootstrap from cold. Starting the session only when the user
     * presses Play meant the first title of every session raced a DHT with zero nodes and timed out
     * looking for peers: the reported "it says network error, and works on the third or fourth
     * retry", because by then the DHT had finally populated.
     *
     * Only runs once consent exists: without it nothing may be downloaded or uploaded anyway, and
     * bringing up a DHT node would put the viewer's address on the network before they agreed to it.
     * The consent flow calls back into here the moment that changes, so a first-time viewer gets a
     * warm session from the point they accept rather than from their first play.
     */
    private fun warmUp() {
        if (warmedUp) return
        warmedUp = true
        scope.launch {
            val startedAt = System.currentTimeMillis()
            runCatching { engine.start() }
                .onSuccess {
                    TorfilxLog.i(TAG, "Torrent session warmed in ${System.currentTimeMillis() - startedAt}ms")
                }
                .onFailure {
                    // Not fatal: the play path starts the engine itself and reports failure there,
                    // where the user can see it. Reset so a later play retries the start.
                    warmedUp = false
                    TorfilxLog.w(TAG, "Could not warm the torrent session (will retry on play)", it)
                }
        }
    }

    @Volatile
    private var warmedUp: Boolean = false

    /**
     * Starts the session for a catalogue check the viewer asked for, if their consent allows it.
     *
     * The same rule as [warmUp]: no consent, no session. With consent this is just an early start of
     * the session the app would bring up anyway.
     */
    override suspend fun ensureRunning(): Boolean {
        if (!settingsRepository.sharingConsent.first()) return false
        settingsRepository.cachedSharingConsent = true
        return runCatching { engine.start() }
            .onFailure { TorfilxLog.w(TAG, "Could not start the torrent session for a catalogue check", it) }
            .isSuccess
    }

    /**
     * Which parts of a title are cached here, for the contribution page.
     *
     * Null once the data has been evicted; the contribution record outlives the file.
     */
    fun cachedParts(infoHash: String, buckets: Int = com.torfilx.core.model.CachedParts.BUCKETS) =
        engine.cachedParts(infoHash, buckets)

    /**
     * Resolves a magnet into a locally-served URL the player can open.
     *
     * [selection] says which file of the torrent to play; see [TorrentEngine.stream].
     */
    suspend fun stream(
        magnet: String,
        selection: FileSelection = FileSelection.LargestVideo,
        displayName: String? = null,
    ): TorrentStream {
        settingsRepository.cachedSharingConsent = settingsRepository.sharingConsent.first()
        return engine.stream(magnet, selection, displayName)
    }

    /**
     * Leaving the player: keep seeding what was downloaded, or let it go entirely.
     *
     * With seeding off the torrent is removed **with its data**. It used to be removed with the data
     * left behind, which was the worst of both: the files stayed on disk, but a torrent that is no
     * longer in the session is invisible to [TorrentEngine.enforceStorageBudget], which only evicts
     * torrents it can see. Watching several titles in one sitting — an evening of episodes — piled
     * up gigabytes nothing could reclaim until the next start, and on a small stick the free-space
     * guard then paused every download, including the one being watched. No resume data is kept, so
     * those files could never have been reused anyway.
     */
    suspend fun stopStreaming(infoHash: String) {
        if (!settingsRepository.seedingEnabled.first()) {
            engine.remove(infoHash, deleteData = true)
        } else {
            engine.stopStreaming(infoHash)
        }
    }

    /**
     * Drops a torrent that was fetched ahead of need and failed part-way (the next episode warmed during
     * a countdown). The engine adds a torrent to its session before its metadata arrives, so a failed
     * fetch can leave one behind that nothing manages; it goes, with whatever it wrote.
     */
    suspend fun discard(infoHash: String) {
        engine.remove(infoHash, deleteData = true)
    }

    /**
     * Deletes every downloaded byte from disk; watch history and My List are untouched.
     *
     * The contribution record goes too. A per-title log of what someone has seeded is exactly the
     * sort of thing "clear my data" has to mean, and leaving it behind would be a nasty surprise.
     * The downloaded catalogue is not film data and stays; Settings has its own control for it.
     */
    suspend fun clearAllData() {
        engine.purgeAllData()
        contributionRepository.clear()
    }

    suspend fun shutdown() {
        // Flush before the session goes away, or the last half-minute of sharing is lost.
        runCatching { contributionRepository.flush(System.currentTimeMillis()) }
        engine.stop()
    }

    private companion object {
        /** How often the on-disk flags are reconciled. Rare on purpose: it is two small writes. */
        const val ON_DISK_SYNC_INTERVAL_MS = 60_000L
    }
}

/** Bridges the persisted consent flag into the engine without a dependency cycle. */
@Singleton
class SettingsSharingConsentProvider @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : SharingConsentProvider {
    override fun hasConsented(): Boolean = settingsRepository.cachedSharingConsent
}

/** Bridges the persisted engine preferences into the engine, synchronously. */
@Singleton
class SettingsTorrentConfigProvider @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : TorrentConfigProvider {
    override fun useDht(): Boolean = settingsRepository.cachedUseDht
    override fun useExtraTrackers(): Boolean = settingsRepository.cachedUseExtraTrackers
    override fun metadataTimeoutMs(): Long = settingsRepository.cachedMetadataTimeoutSeconds * 1000L
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TorrentCoordinatorModule {
    @Binds
    @Singleton
    abstract fun bindsSharingConsentProvider(
        impl: SettingsSharingConsentProvider,
    ): SharingConsentProvider

    @Binds
    @Singleton
    abstract fun bindsTorrentConfigProvider(
        impl: SettingsTorrentConfigProvider,
    ): TorrentConfigProvider

    @Binds
    @Singleton
    abstract fun bindsCatalogueSessionGate(impl: TorrentCoordinator): CatalogueSessionGate
}
