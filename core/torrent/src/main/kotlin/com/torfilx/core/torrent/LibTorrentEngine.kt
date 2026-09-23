package com.torfilx.core.torrent

import android.content.Context
import android.os.StatFs
import com.torfilx.core.catalogue.swarm.DhtStateStore
import com.torfilx.core.catalogue.swarm.HolePuncher
import com.torfilx.core.catalogue.swarm.SessionShutdown
import com.torfilx.core.catalogue.swarm.SwarmCatalogueTransport
import com.torfilx.core.catalogue.swarm.SwarmLog
import com.torfilx.core.catalogue.swarm.SwarmSessions
import com.torfilx.core.catalogue.transport.CatalogueDownload
import com.torfilx.core.catalogue.transport.CatalogueDownloadRequest
import com.torfilx.core.catalogue.transport.CatalogueTransport
import com.torfilx.core.catalogue.transport.PointerLookup
import com.torfilx.core.common.di.ApplicationScope
import com.torfilx.core.common.di.Dispatcher
import com.torfilx.core.common.di.TorfilxDispatcher
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.model.CachedParts
import com.torfilx.core.model.EpisodeFileMatcher
import com.torfilx.core.model.FileSelection
import com.torfilx.core.model.MagnetLink
import com.torfilx.core.model.UploadLimit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.swig.settings_pack
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Torrent"

/**
 * BitTorrent engine built on libtorrent4j.
 *
 * Streaming, rather than downloading-then-playing, is the whole point: the file the user picked is
 * the only one downloaded, its pieces are prioritised in play order, and the first pieces get a
 * deadline so playback can start in seconds.
 *
 * Sharing is deliberate: nothing is uploaded until [consentProvider] says the user opted in, and the
 * data kept for seeding never exceeds the space [storageBudget] allows.
 *
 * The same session also carries the catalogue: the DHT pointer to the latest signed release and the
 * small release torrent itself ([CatalogueTransport]).
 */
@Singleton
class LibTorrentEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val consentProvider: SharingConsentProvider,
    private val config: TorrentConfigProvider,
    @Dispatcher(TorfilxDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) : TorrentEngine, CatalogueTransport {

    /**
     * Created on first use, never in the constructor.
     *
     * Constructing a [SessionManager] runs libtorrent's static initialiser, which loads
     * `libtorrent4j.so`. Hilt builds this singleton on the main thread during `MainActivity.onCreate`,
     * so a device whose ABI is missing from the APK — or whose loader rejects the library — used to
     * die with a `LinkageError` before a single frame was drawn, with no way to catch it.
     */
    private val sessionRef: SessionManager by lazy { SessionManager() }

    /** Turns any native-loading failure into the app's own typed error instead of a [LinkageError]. */
    private val session: SessionManager
        get() = runCatching { sessionRef }.getOrElse { throw TorrentError.EngineUnavailable(it) }
    private val sessionMutex = Mutex()

    /** Bounds stop(): libtorrent's session destructor now and then never returns. */
    private val sessionShutdown = SessionShutdown("TorfilxSessionStop")
    private val streamServer = TorrentStreamServer()

    /** Records network events so a failed play can explain itself on screen (see [diagnosticsText]). */
    private val diagnostics = TorrentDiagnostics()
    /** Streaming *and* seeding torrents; a torrent leaves this map only when it is removed. */
    private val managedTorrents = java.util.concurrent.ConcurrentHashMap<String, StreamedTorrent>()

    private val _torrents = MutableStateFlow<List<TorrentStatus>>(emptyList())
    override val torrents: Flow<List<TorrentStatus>> = _torrents.asStateFlow()

    override val stats: Flow<SharingStats> = _torrents.map { list -> aggregate(list) }

    @Volatile
    private var nativeAvailable: Boolean? = null

    @Volatile
    private var started = false

    private val _sessionRunning = MutableStateFlow(false)

    /** True between a successful [start] and [stop]. The catalogue updater follows this. */
    override val sessionRunning: StateFlow<Boolean> = _sessionRunning.asStateFlow()

    /**
     * Catalogue releases travel over this session, through the same code the publisher tool runs.
     *
     * Catalogue torrents are added straight to the session and never enter [managedTorrents], so they
     * sit outside the storage budget, the sharing statistics and the contribution record. They are a
     * few hundred kilobytes, and they live under `filesDir/catalogue`, never under [downloadDir].
     */
    private val catalogueTransport = SwarmCatalogueTransport(
        session = { if (started) sessionRef else null },
        sessionRunning = sessionRunning,
        dhtEnabled = { config.useDht() },
        extraTrackers = { if (config.useExtraTrackers()) FALLBACK_TRACKERS else emptyList() },
        log = SwarmLog { level, message, error ->
            when (level) {
                SwarmLog.Level.DEBUG -> TorfilxLog.d(CATALOGUE_TAG, message)
                SwarmLog.Level.INFO -> TorfilxLog.i(CATALOGUE_TAG, message)
                SwarmLog.Level.WARN -> TorfilxLog.w(CATALOGUE_TAG, message, error)
                SwarmLog.Level.ERROR -> TorfilxLog.e(CATALOGUE_TAG, message, error)
            }
        },
        ioDispatcher = ioDispatcher,
        holePunchScope = scope,
    )

    /**
     * Keeps title torrents, streaming or seeding, reaching peers whose routers accept no incoming
     * connections, as the catalogue transport does for catalogue torrents. See [HolePuncher].
     */
    private val titlePuncher = HolePuncher(
        session = { if (started) sessionRef else null },
        scope = scope,
        log = SwarmLog { level, message, error ->
            if (level == SwarmLog.Level.WARN || level == SwarmLog.Level.ERROR) {
                TorfilxLog.w(TAG, message, error)
            } else {
                TorfilxLog.d(TAG, message)
            }
        },
    )

    /**
     * Where torrent data lives: internal app storage.
     *
     * Deliberately *not* `getExternalFilesDir`: that path is served by the MediaProvider FUSE daemon,
     * which the engine.s random-access reads and writes can crash — taking the whole app down with
     * it when the platform then kills every process touching the volume. Internal storage is plain
     * ext4, app-private, invisible to the media scanner, and removed on uninstall.
     */
    private val downloadDir: File by lazy {
        File(context.filesDir, "torrents").apply { mkdirs() }
    }

    /**
     * The DHT routing table from the last session.
     *
     * Kept outside [downloadDir], which is emptied on every start, so a restarted session rejoins the
     * DHT from the nodes it already knew instead of bootstrapping from nothing.
     */
    private val dhtStateFile: File by lazy {
        File(context.filesDir, "torrent-session/dht.state")
    }

    override fun isAvailable(): Boolean {
        nativeAvailable?.let { return it }
        val available = runCatching {
            // Touching a native-backed class is the only reliable way to know the .so loaded.
            org.libtorrent4j.LibTorrent.version()
            true
        }.getOrElse {
            TorfilxLog.e(TAG, "libtorrent native library unavailable", it)
            false
        }
        nativeAvailable = available
        return available
    }

    override suspend fun start() = withContext(ioDispatcher) {
        sessionMutex.withLock {
            if (started) return@withLock
            if (!isAvailable()) throw TorrentError.EngineUnavailable(null)
            // A stop that overran its wait may still be inside libtorrent's session destructor, and the
            // session manager cannot start again until that returns. Give it a moment, then fail with the
            // engine's own error rather than blocking every caller behind it.
            if (!sessionShutdown.awaitPending(PENDING_STOP_WAIT_MS)) {
                TorfilxLog.e(TAG, "The previous torrent session is still shutting down; not starting a new one yet")
                throw TorrentError.EngineUnavailable(null)
            }

            // Attach the diagnostics listener before the session starts so no early alert is missed.
            runCatching { session.addListener(diagnostics) }

            // Start with the LIBRARY'S OWN default session, then layer our tweaks on top.
            //
            // This is a hard-won correction. Handing libtorrent a hand-built SettingsPack makes that
            // pack the entire configuration, and any field we left out — crucially the listen
            // interfaces and the DHT/LSD defaults — comes up empty rather than at libtorrent's
            // carefully chosen default. The symptom was a session that never bound a listen socket,
            // never announced to a tracker, and never bootstrapped the DHT: total silence on the
            // network. libtorrent4j's no-arg start() uses the C++ defaults (which *do* bind and
            // *do* enable the DHT) and only sets the bootstrap nodes; we do the same, then apply our
            // limits with applySettings() once the session is up and correctly listening.
            runCatching { session.start() }
                .onFailure { throw TorrentError.EngineUnavailable(it) }

            val tweaks = SettingsPack().apply {
                // Modest limits: a Fire Stick has a weak CPU and shares the household Wi-Fi.
                setInteger(settings_pack.int_types.active_downloads.swigValue(), MAX_ACTIVE_DOWNLOADS)
                setInteger(settings_pack.int_types.active_seeds.swigValue(), MAX_ACTIVE_SEEDS)
                setInteger(settings_pack.int_types.connections_limit.swigValue(), CONNECTION_LIMIT)
                setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
                setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
                // Announce every minute, not every fifteen: a television seeding the catalogue or a
                // title then finds, and connects out to, one that is waiting for it, even when its own
                // router accepts no incoming connections. See SwarmSessions.DHT_ANNOUNCE_INTERVAL_S.
                setInteger(
                    settings_pack.int_types.dht_announce_interval.swigValue(),
                    SwarmSessions.DHT_ANNOUNCE_INTERVAL_S,
                )
                // Retry a peer 15 s after a failed attempt, not 60: with HolePuncher that is how two
                // routers that accept no incoming connections are crossed.
                setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), HolePuncher.RECONNECT_INTERVAL_S)
                setString(settings_pack.string_types.user_agent.swigValue(), USER_AGENT)
                // Upload is only allowed once the user has consented to sharing, and even then it is
                // capped by default: seeding must not saturate the household uplink and degrade the
                // viewer's own streaming. The viewer may raise the cap in Settings, never lower it.
                setInteger(
                    settings_pack.int_types.upload_rate_limit.swigValue(),
                    uploadRateLimit(consentProvider.hasConsented()),
                )
            }
            runCatching { session.applySettings(tweaks) }
                .onFailure { TorfilxLog.w(TAG, "Could not apply tuning settings (continuing)", it) }

            // Honour the user's DHT preference. On by default; a network that blocks or is harmed by
            // the DHT can turn it off in Settings and rely on trackers. Never fail either way.
            runCatching {
                if (config.useDht()) {
                    if (!session.isDhtRunning()) {
                        session.startDht()
                        TorfilxLog.i(TAG, "DHT started")
                    }
                } else if (session.isDhtRunning()) {
                    session.stopDht()
                    TorfilxLog.i(TAG, "DHT disabled by setting")
                }
            }.onFailure { TorfilxLog.w(TAG, "Could not toggle the DHT (continuing on trackers)", it) }

            // Rejoin the DHT from the routing table the last session saved. A cold bootstrap through the
            // public routers takes tens of seconds; a warm one takes a few. Failure only means cold.
            if (config.useDht()) {
                runCatching { DhtStateStore.load(session, dhtStateFile) }
                    .onSuccess { loaded -> if (loaded) TorfilxLog.i(TAG, "DHT state restored from the last session") }
                    .onFailure { TorfilxLog.w(TAG, "Could not restore the DHT state (starting cold)", it) }
            }

            started = true
            // No resume data is kept, so anything already on disk at this (cold) start is an orphan
            // that can never be resumed — only dead weight that would accumulate and fill the disk
            // across restarts. Reclaim it before adding anything new. managedTorrents is always
            // empty here because start()'s body only runs when the session is not already up.
            purgeOrphanedData()
            // Binding the loopback socket can fail on a locked-down device; surface it as a typed
            // error the player screen can show, not a raw IOException that crashes the play coroutine.
            runCatching { streamServer.start() }
                .onFailure { throw TorrentError.EngineUnavailable(it) }
            startStatusPolling()
            TorfilxLog.i(
                TAG,
                "Torrent session started (dir=${downloadDir.absolutePath}, dhtRunning=${
                    runCatching { session.isDhtRunning() }.getOrDefault(false)
                })",
            )
            _sessionRunning.value = true
        }
    }

    override suspend fun stop() = withContext(ioDispatcher) {
        sessionMutex.withLock {
            if (!started) return@withLock
            // Followers hear first, so a catalogue check in flight is cancelled before its session goes.
            _sessionRunning.value = false
            saveDhtState()
            streamServer.stop()
            managedTorrents.clear()
            titlePuncher.untrackAll()
            // libtorrent 1.2 now and then never returns from its session destructor. The wait is bounded
            // so this lock is released either way; start() refuses to run until the old session is gone.
            val manager = session
            val stoppedInTime = sessionShutdown.stopWithin(
                STOP_WAIT_MS,
                onError = { TorfilxLog.w(TAG, "Stopping the torrent session failed", it) },
            ) { manager.stop() }
            if (!stoppedInTime) {
                TorfilxLog.e(TAG, "libtorrent did not finish shutting down within ${STOP_WAIT_MS / MS_PER_SECOND} s; it continues in the background")
            }
            started = false
            TorfilxLog.i(TAG, "Torrent session stopped")
        }
    }

    override suspend fun stream(
        magnet: String,
        selection: FileSelection,
        displayName: String?,
    ): TorrentStream = withContext(ioDispatcher) {
        if (!consentProvider.hasConsented()) throw TorrentError.NotConsented()
        val infoHash = MagnetLink.infoHashOf(magnet) ?: throw TorrentError.InvalidMagnet(magnet)

        start()
        enforceStorageBudget()

        // Refuse to start a download on a critically low disk. A positive reading below the reserve
        // floor means there is not enough headroom to buffer without wedging the device; 0 means the
        // stat failed, so we do not block on it.
        val freeAtStart = freeSpaceBytes()
        if (freeAtStart in 1 until storageBudget.reserveBytes) {
            throw TorrentError.NoSpace(needed = storageBudget.reserveBytes, available = freeAtStart)
        }

        managedTorrents[infoHash]?.let { existing ->
            // Already in the session (possibly seeding): resume streaming from it rather than
            // re-adding the torrent and re-checking every piece on disk. A season pack may be asked for
            // a different episode than the one it last served: the file is switched in place.
            val info = existing.handle.torrentFile() ?: throw TorrentError.MetadataTimeout()
            val files = info.files()
            val candidates = (0 until files.numFiles()).map { index ->
                EpisodeFileMatcher.Candidate(index, files.filePath(index), files.fileSize(index))
            }
            val fileIndex = chooseVideoFile(candidates, selection) ?: throw TorrentError.NoPlayableFile()
            if (fileIndex != existing.fileIndex) {
                TorfilxLog.i(TAG, "Switching $infoHash from file ${existing.fileIndex} to ${files.filePath(fileIndex)}")
                existing.select(selectedFile(files, fileIndex), files.numFiles())
            }
            existing.isStreaming = true
            displayName?.let { existing.displayName = it }
            // A seeding torrent can have been paused (consent withdrawn, or the disk ran low). Streaming
            // it again needs it downloading again; the free-space guard still stops it if it must.
            runCatching { existing.handle.resume() }
            streamServer.register(existing)
            return@withContext existing.toStream(streamServer.port)
        }

        diagnostics.markAttemptStart()
        // The magnet was already validated at the top of stream(), so a failure here is a session
        // problem, not a bad link — reporting it as an invalid magnet would mislead the user.
        runCatching { session.download(magnet, downloadDir) }
            .onFailure { throw TorrentError.EngineUnavailable(it) }
        titlePuncher.track(infoHash)

        // libtorrent4j 1.2's download() strips AUTO_MANAGED from the add-torrent flags but leaves
        // libtorrent's default PAUSED flag in place — and the auto-manager is the only thing that
        // would ever un-pause it. Left alone, the torrent never announces, so no peer ever sends
        // metadata and every title dies with "no peers responded". (2.x keeps AUTO_MANAGED, which
        // is why the same code worked there.) libtorrent4j's own fetchMagnet() resumes for exactly
        // this reason; so do we, the moment the handle exists.
        var prepared = false

        // Metadata arrives from peers; without it there is nothing to prioritise or serve.
        val handle = withTimeoutOrNull(config.metadataTimeoutMs()) {
            var found: TorrentHandle? = null
            while (found == null) {
                val candidate = session.find(infoHash.toSha1Hash())
                if (candidate != null && candidate.isValid) {
                    if (!prepared) {
                        prepareHandle(candidate, magnet)
                        prepared = true
                    }
                    if (candidate.torrentFile() != null) found = candidate
                }
                delay(POLL_INTERVAL_MS)
            }
            found
        } ?: throw TorrentError.MetadataTimeout(diagnosticsText("metadata timeout"))

        val info = handle.torrentFile() ?: throw TorrentError.MetadataTimeout()
        val files = info.files()

        // The video is the largest file — or, for an episode, that episode's file (see
        // chooseVideoFile). Sample and subtitle files are ignored entirely.
        val candidates = (0 until files.numFiles()).map { index ->
            EpisodeFileMatcher.Candidate(index, files.filePath(index), files.fileSize(index))
        }
        val fileIndex = chooseVideoFile(candidates, selection) ?: throw TorrentError.NoPlayableFile()
        if (selection != FileSelection.LargestVideo) {
            TorfilxLog.i(TAG, "Streaming ${files.filePath(fileIndex)} for $selection")
        }

        val fileSize = files.fileSize(fileIndex)
        val free = freeSpaceBytes()
        if (fileSize > storageBudget.capBytes(free)) {
            TorfilxLog.w(
                TAG,
                "File (${fileSize / 1_000_000} MB) exceeds the storage budget; " +
                    "streaming with aggressive eviction",
            )
        }

        // Download only the chosen file.
        val priorities = Array(files.numFiles()) { Priority.IGNORE }
        priorities[fileIndex] = Priority.DEFAULT
        handle.prioritizeFiles(priorities)
        handle.resume()

        val streamed = StreamedTorrent(
            infoHash = infoHash,
            handle = handle,
            file = selectedFile(files, fileIndex),
            pieceLength = info.pieceLength(),
            numPieces = info.numPieces(),
            lastTouchedMs = System.currentTimeMillis(),
            displayName = displayName,
        )
        managedTorrents[infoHash] = streamed
        streamServer.register(streamed)
        enforceHandleCap()

        // Prime the beginning of the file so playback can start without waiting for the whole thing.
        streamed.prioritiseFrom(0L)

        streamed.toStream(streamServer.port)
    }

    override suspend fun stopStreaming(infoHash: String) = withContext(ioDispatcher) {
        val streamed = managedTorrents[infoHash] ?: return@withContext
        streamServer.unregister(infoHash)
        streamed.isStreaming = false
        // The torrent stays in the session so it keeps seeding what was downloaded; without consent
        // it is paused instead, which stops all upload immediately.
        if (consentProvider.hasConsented()) {
            runCatching { streamed.handle.resume() }
            TorfilxLog.i(TAG, "Now seeding ${streamed.fileName}")
        } else {
            runCatching { streamed.handle.pause() }
        }
        enforceStorageBudget()
        enforceHandleCap()
    }

    override suspend fun remove(infoHash: String, deleteData: Boolean) = withContext(ioDispatcher) {
        streamServer.unregister(infoHash)
        managedTorrents.remove(infoHash)
        titlePuncher.untrack(infoHash)
        val handle = runCatching { session.find(infoHash.toSha1Hash()) }.getOrNull()
        if (handle != null && handle.isValid) {
            // Only film data is removed here. A torrent saved anywhere else is a catalogue release,
            // whose lifetime belongs to the catalogue updater.
            if (!isFilmTorrent(handle)) {
                TorfilxLog.w(TAG, "Not removing $infoHash: it is not a film torrent")
                return@withContext
            }
            runCatching {
                if (deleteData) {
                    session.remove(handle, org.libtorrent4j.swig.session_handle.delete_files)
                } else {
                    session.remove(handle)
                }
            }.onFailure { TorfilxLog.w(TAG, "Could not remove torrent $infoHash", it) }
        }
    }

    /** File [index] of a torrent, as the stream server and the piece maths need it. */
    private fun selectedFile(files: org.libtorrent4j.FileStorage, index: Int) = SelectedFile(
        index = index,
        name = files.fileName(index),
        sizeBytes = files.fileSize(index),
        path = File(downloadDir, files.filePath(index)),
        offset = files.fileOffset(index),
    )

    private fun isFilmTorrent(handle: TorrentHandle): Boolean = runCatching {
        File(handle.savePath()).canonicalFile.startsWith(downloadDir.canonicalFile)
    }.getOrDefault(true)

    /**
     * Keeps disk usage inside the budget.
     *
     * Eviction is oldest-touched-first and never removes what is currently playing, so a long
     * seeding session can never cost the user the film they are watching.
     */
    override suspend fun enforceStorageBudget() = withContext(ioDispatcher) {
        val free = freeSpaceBytes()
        val cap = storageBudget.capBytes(free)
        var used = directorySize(downloadDir)
        if (used <= cap) return@withContext

        TorfilxLog.i(TAG, "Torrent data ${used / 1_000_000} MB over cap ${cap / 1_000_000} MB; evicting")

        val candidates = _torrents.value
            .filterNot { managedTorrents[it.infoHash]?.isStreaming == true }
            .sortedBy { lastTouched[it.infoHash] ?: 0L }

        for (candidate in candidates) {
            if (used <= cap) break
            remove(candidate.infoHash, deleteData = true)
            used = directorySize(downloadDir)
        }

        if (used > cap) {
            TorfilxLog.w(TAG, "Still over the storage cap after eviction (${used / 1_000_000} MB)")
        }
    }

    /**
     * Keeps the number of title torrents in the session within [HandleCap.MAX_TORRENTS], removing the
     * oldest-touched ones that are not streaming — with their data, because a torrent gone from the
     * session is invisible to [enforceStorageBudget] and its files could never be reclaimed.
     */
    private suspend fun enforceHandleCap() {
        val entries = managedTorrents.values.map { t ->
            HandleCap.Entry(t.infoHash, lastTouched[t.infoHash] ?: t.lastTouchedMs, t.isStreaming)
        }
        val surplus = HandleCap.surplus(entries)
        if (surplus.isEmpty()) return
        TorfilxLog.i(TAG, "${entries.size} torrents in the session; removing ${surplus.size} touched longest ago")
        surplus.forEach { remove(it, deleteData = true) }
    }

    /**
     * Which parts of a title's video file are held on this device.
     *
     * Read on demand rather than polled. libtorrent's bitfield covers the whole torrent and is one
     * entry per piece — several thousand for a feature film — so this is deliberately not part of the
     * status tick: it is computed only when something is actually going to draw it, restricted to the
     * pieces belonging to the one file that was downloaded, and downsampled before it leaves here.
     * Nothing downstream ever sees the full-resolution bitfield.
     */
    override fun cachedParts(infoHash: String, buckets: Int): CachedParts? {
        val streamed = managedTorrents[infoHash] ?: return null
        // One file throughout: a season pack can switch episodes while this is being read.
        val file = streamed.selected
        return runCatching {
            val range = filePieceRange(
                fileOffset = file.offset,
                fileSizeBytes = file.sizeBytes,
                pieceLength = streamed.pieceLength,
                numPieces = streamed.numPieces,
            )
            if (range.isEmpty()) return null

            val have = ArrayList<Boolean>(range.last - range.first + 1)
            var havePieces = 0
            for (piece in range) {
                val present = streamed.handle.havePiece(piece)
                have += present
                if (present) havePieces++
            }

            CachedParts(
                buckets = downsamplePieces(have, buckets),
                // Approximated from whole pieces, which is the only resolution libtorrent reports.
                // Capped at the file size so a part-piece at each end cannot read as >100%.
                haveBytes = minOf(
                    havePieces.toLong() * streamed.pieceLength,
                    file.sizeBytes,
                ),
                totalBytes = file.sizeBytes,
            )
        }.getOrElse {
            TorfilxLog.w(TAG, "Could not read the piece map for $infoHash", it)
            null
        }
    }

    /** Applies a consent change immediately: uploading is throttled to nothing without consent. */
    fun onConsentChanged(consented: Boolean) {
        if (!started) return
        runCatching {
            session.applySettings(
                SettingsPack().apply {
                    setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), uploadRateLimit(consented))
                },
            )
            if (!consented) {
                _torrents.value.forEach { status ->
                    session.find(status.infoHash.toSha1Hash())
                        ?.takeIf { it.isValid && status.isSeeding }
                        ?.pause()
                }
            }
        }.onFailure { TorfilxLog.w(TAG, "Could not apply consent change", it) }
    }

    /**
     * Applies a new upload cap from Settings to the running session straight away.
     *
     * Unlike the DHT switch this needs no new session, so there is no reason to make the viewer wait
     * for the next title. Without consent the throttle stays at nothing whatever the cap says.
     */
    fun onUploadLimitChanged() {
        if (!started) return
        val limit = uploadRateLimit(consentProvider.hasConsented())
        runCatching {
            session.applySettings(
                SettingsPack().apply {
                    setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), limit)
                },
            )
            TorfilxLog.i(TAG, "Upload cap now ${if (limit == 0) "unlimited" else "${limit / BYTES_PER_KIB} KiB/s"}")
        }.onFailure { TorfilxLog.w(TAG, "Could not apply the upload cap", it) }
    }

    /**
     * libtorrent's `upload_rate_limit` for the current consent and configured cap.
     *
     * 1 byte/s without consent: libtorrent reads 0 as "unlimited", so 1 is the nearest thing to off.
     * With consent, 0 (no cap) passes through, and any other value is held to the built-in floor so a
     * stale or hand-edited preference can never throttle sharing below what the app always gave.
     */
    private fun uploadRateLimit(consented: Boolean): Int {
        if (!consented) return 1
        val configured = config.uploadRateLimitBytes()
        return if (configured == 0) 0 else maxOf(configured, UploadLimit.FLOOR_BYTES_PER_SECOND)
    }

    /** The budget in force; overridable from Settings. */
    @Volatile
    var storageBudget: StorageBudget = StorageBudget.DEFAULT

    // Read from enforceStorageBudget (IO) and written from the status poller (Default), so it must
    // be concurrent — a plain HashMap touched from two dispatchers risks ConcurrentModificationException.
    private val lastTouched = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun startStatusPolling() {
        scope.launch {
            var lastDhtStateSaveMs = System.currentTimeMillis()
            while (isActive && started) {
                val snapshot = runCatching { collectStatuses() }.getOrDefault(emptyList())
                _torrents.value = snapshot
                snapshot.forEach { lastTouched.putIfAbsent(it.infoHash, System.currentTimeMillis()) }
                managedTorrents.values.filter { it.isStreaming }
                    .forEach { lastTouched[it.infoHash] = System.currentTimeMillis() }
                runCatching { guardFreeSpace() }
                val now = System.currentTimeMillis()
                if (now - lastDhtStateSaveMs >= DHT_STATE_SAVE_INTERVAL_MS) {
                    lastDhtStateSaveMs = now
                    // The process can be killed without stop() ever running, so the routing table is
                    // also saved while the session is up.
                    withContext(ioDispatcher) { saveDhtState() }
                }
                delay(STATUS_POLL_MS)
            }
        }
    }

    /** Writes the DHT routing table for the next session. Never throws. */
    private fun saveDhtState() {
        if (!config.useDht()) return
        runCatching { DhtStateStore.save(sessionRef, dhtStateFile) }
            .onSuccess { bytes -> if (bytes != null) TorfilxLog.d(TAG, "DHT state saved ($bytes bytes)") }
            .onFailure { TorfilxLog.w(TAG, "Could not save the DHT state", it) }
    }

    /**
     * Keeps the device's disk headroom above the reserve floor.
     *
     * A single large title downloads sequentially and there is no way to drop already-downloaded
     * pieces of the active torrent, so it can march the disk toward zero on its own. When free space
     * dips below the reserve, evict what can be evicted; if that is not enough, pause everything so
     * the disk keeps its headroom. Playback then stalls with an error, which is far better than
     * filling internal storage and wedging the whole TV.
     */
    private suspend fun guardFreeSpace() {
        val reserve = storageBudget.reserveBytes
        if (freeSpaceBytes().let { it in 1 until reserve }) {
            enforceStorageBudget()
            if (freeSpaceBytes().let { it in 1 until reserve }) {
                managedTorrents.values.forEach { runCatching { it.handle.pause() } }
                TorfilxLog.w(
                    TAG,
                    "Free space below the ${reserve / 1_000_000} MB reserve; paused downloads to " +
                        "protect the device",
                )
            }
        }
    }

    /** Deletes leftover on-disk data that no longer belongs to any managed torrent. */
    private fun purgeOrphanedData() {
        runCatching {
            val entries = downloadDir.listFiles()?.takeIf { it.isNotEmpty() } ?: return
            val freed = directorySize(downloadDir)
            entries.forEach { it.deleteRecursively() }
            TorfilxLog.i(TAG, "Purged ${freed / 1_000_000} MB of orphaned torrent data on start")
        }.onFailure { TorfilxLog.w(TAG, "Could not purge orphaned torrent data", it) }
    }

    override suspend fun purgeAllData() = withContext(ioDispatcher) {
        // Stop the session first so nothing is writing while the files are deleted, then remove the
        // data directly rather than relying on libtorrent's asynchronous delete.
        stop()
        val freed = directorySize(downloadDir)
        runCatching { downloadDir.listFiles()?.forEach { it.deleteRecursively() } }
            .onFailure { TorfilxLog.w(TAG, "Could not delete downloaded data", it) }
        _torrents.value = emptyList()
        lastTouched.clear()
        TorfilxLog.i(TAG, "Cleared ${freed / 1_000_000} MB of downloaded data")
    }

    // --- Catalogue transport ---------------------------------------------------------------------

    override fun dhtNodes(): Long = catalogueTransport.dhtNodes()

    override suspend fun resolvePointer(publicKeys: List<ByteArray>, salt: ByteArray, timeoutMs: Long): PointerLookup =
        catalogueTransport.resolvePointer(publicKeys, salt, timeoutMs)

    override suspend fun download(request: CatalogueDownloadRequest, onProgress: (Float) -> Unit): CatalogueDownload =
        catalogueTransport.download(request, onProgress)

    override suspend fun seed(torrentBytes: ByteArray, saveDir: File): String =
        catalogueTransport.seed(torrentBytes, saveDir)

    override suspend fun stopSeeding(infoHash: String) = catalogueTransport.stopSeeding(infoHash)

    // --- Status ----------------------------------------------------------------------------------

    private fun collectStatuses(): List<TorrentStatus> = managedTorrents.values.mapNotNull { streamed ->
        val handle = streamed.handle
        if (!handle.isValid) return@mapNotNull null
        val status = handle.status()
        TorrentStatus(
            infoHash = streamed.infoHash,
            name = streamed.displayName ?: streamed.fileName,
            progress = status.progress(),
            downloadRateBytesPerSecond = status.downloadRate(),
            uploadRateBytesPerSecond = status.uploadRate(),
            peers = status.numPeers(),
            seeds = status.numSeeds(),
            totalDownloadedBytes = status.totalDownload(),
            totalUploadedBytes = status.totalUpload(),
            isSeeding = status.isSeeding,
            isPaused = status.state() == org.libtorrent4j.TorrentStatus.State.UNKNOWN ||
                status.flags().and_(org.libtorrent4j.TorrentFlags.PAUSED).to_int() != 0,
            hasMetadata = handle.torrentFile() != null,
            sizeBytes = streamed.fileSizeBytes,
        )
    }

    private fun aggregate(list: List<TorrentStatus>): SharingStats {
        val free = freeSpaceBytes()
        return SharingStats(
            activeTorrents = list.size,
            downloadRateBytesPerSecond = list.sumOf { it.downloadRateBytesPerSecond },
            uploadRateBytesPerSecond = list.sumOf { it.uploadRateBytesPerSecond },
            totalUploadedBytes = list.sumOf { it.totalUploadedBytes },
            totalDownloadedBytes = list.sumOf { it.totalDownloadedBytes },
            diskUsedBytes = directorySize(downloadDir),
            diskCapBytes = storageBudget.capBytes(free),
            freeSpaceBytes = free,
            isSeeding = list.any { it.isSeeding && !it.isPaused },
        )
    }

    /**
     * Resumes a freshly added torrent and widens its peer sources.
     *
     * Resuming is mandatory (see the call site). Adding well-known public trackers on top of
     * whatever the magnet carried means peer discovery does not depend on any single path: a network
     * that blocks the DHT may still reach trackers, and a magnet whose own trackers are dead (several
     * in older catalogues are) still has a live one to fall back on.
     */
    private fun prepareHandle(handle: TorrentHandle, magnet: String) {
        runCatching { handle.resume() }
        if (config.useExtraTrackers()) {
            val existing = runCatching { handle.trackers().mapNotNull { it.url() }.toSet() }
                .getOrDefault(emptySet())
            FALLBACK_TRACKERS.filterNot { it in existing }.forEach { url ->
                runCatching { handle.addTracker(org.libtorrent4j.AnnounceEntry(url)) }
            }
        }
        runCatching { handle.forceReannounce() }
        TorfilxLog.i(
            TAG,
            "Torrent resumed; ${handle.trackers().size} trackers, waiting for metadata " +
                "(name=${MagnetLink.displayName(magnet) ?: "?"})",
        )
    }

    /** The on-screen, photographable diagnosis of a failed play attempt. Also written to the log. */
    private fun diagnosticsText(reason: String): String {
        val nodes = runCatching { session.dhtNodes() }.getOrDefault(-1L)
        val running = runCatching { session.isDhtRunning() }.getOrDefault(false)
        diagnostics.logSnapshot(reason, nodes, running)
        return diagnostics.summary(nodes, running)
    }

    private fun freeSpaceBytes(): Long = runCatching {
        val stat = StatFs(downloadDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)

    private fun directorySize(dir: File): Long = runCatching {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private companion object {
        const val CATALOGUE_TAG = "CatalogSwarm"

        /** How long stop() waits for libtorrent's session destructor before carrying on without it. */
        const val STOP_WAIT_MS = 20_000L

        /** How long start() waits for a stop that overran before reporting the engine unavailable. */
        const val PENDING_STOP_WAIT_MS = 5_000L
        const val MS_PER_SECOND = 1_000L
        const val MAX_ACTIVE_DOWNLOADS = 2
        const val MAX_ACTIVE_SEEDS = 4
        const val CONNECTION_LIMIT = 120
        const val USER_AGENT = "Torfilx/1.0 libtorrent/1.2"
        const val BYTES_PER_KIB = 1024

        /**
         * Live public trackers added to every torrent so peer discovery never rests on the DHT
         * alone. Kept current deliberately: dead trackers in a magnet are harmless, but a client
         * with *only* dead trackers and a blocked DHT finds nobody.
         */
        val FALLBACK_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.tracker.cl:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "https://tracker.tamersunion.org:443/announce",
        )

        const val POLL_INTERVAL_MS = 250L
        const val STATUS_POLL_MS = 1_000L
        const val DHT_STATE_SAVE_INTERVAL_MS = 10 * 60_000L
    }
}

/** Supplies the user's sharing decision to the engine without the engine knowing about settings. */
interface SharingConsentProvider {
    fun hasConsented(): Boolean
}

/**
 * Supplies the user's engine preferences synchronously.
 *
 * The engine reads configuration from paths that cannot suspend (session start, the metadata wait),
 * so it takes a plain provider rather than a coroutine flow. Defaults reproduce built-in behaviour,
 * so an implementation that returns them changes nothing.
 */
interface TorrentConfigProvider {
    /** Whether to use the DHT for peer discovery. */
    fun useDht(): Boolean = true

    /** Whether to add the built-in public trackers on top of a magnet's own. */
    fun useExtraTrackers(): Boolean = true

    /** How long to wait for a swarm to deliver a title's metadata. */
    fun metadataTimeoutMs(): Long = 120_000L

    /** Upload cap while sharing, in bytes per second; 0 for no cap. Held to [UploadLimit]'s floor. */
    fun uploadRateLimitBytes(): Int = UploadLimit.STANDARD.bytesPerSecond
}
