package com.torfilx.core.catalogue.swarm

import com.torfilx.core.catalogue.Hex
import com.torfilx.core.catalogue.format.CataloguePointer
import com.torfilx.core.catalogue.transport.CatalogueDownload
import com.torfilx.core.catalogue.transport.CatalogueDownloadRequest
import com.torfilx.core.catalogue.transport.CatalogueTransport
import com.torfilx.core.catalogue.transport.CatalogueTransportException
import com.torfilx.core.catalogue.transport.PointerLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.CacheFlushedAlert
import org.libtorrent4j.alerts.DhtMutableItemAlert
import java.io.File

/**
 * [CatalogueTransport] over a live libtorrent session.
 *
 * Session-agnostic on purpose: the app hands it the engine's session, the publisher tool hands it its
 * own, and the tests hand it sessions on a private loopback network. All three run this exact code.
 *
 * Catalogue torrents are added to the session directly. In the app they never become managed titles,
 * so they count against no storage budget and appear in no sharing statistics. They are tiny, and they
 * are what lets every other viewer find new films.
 *
 * @param session the running session, or null while there is none.
 * @param dhtEnabled whether the user allows the DHT; a lookup never runs against their wishes.
 * @param extraTrackers trackers added to a download on top of whatever the swarm provides.
 * @param holePunchScope where [HolePuncher] runs while a catalogue downloads or seeds, so that peers
 *   behind routers that accept no incoming connections still reach each other. Null turns it off.
 */
class SwarmCatalogueTransport(
    private val session: () -> SessionManager?,
    override val sessionRunning: StateFlow<Boolean>,
    private val dhtEnabled: () -> Boolean = { true },
    private val extraTrackers: () -> List<String> = { emptyList() },
    private val log: SwarmLog = SwarmLog.NONE,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    holePunchScope: CoroutineScope? = null,
) : CatalogueTransport {

    private val holePuncher = holePunchScope?.let { HolePuncher(session, it, log, blockingDispatcher = ioDispatcher) }

    /** One DHT lookup at a time, so a lookup's completion signal cannot be confused with another's. */
    private val lookupMutex = Mutex()

    override fun dhtNodes(): Long = runCatching { session()?.dhtNodes() ?: 0L }.getOrDefault(0L)

    // --- Pointer lookup --------------------------------------------------------------------------

    override suspend fun resolvePointer(
        publicKeys: List<ByteArray>,
        salt: ByteArray,
        timeoutMs: Long,
    ): PointerLookup = withContext(ioDispatcher) {
        val live = session() ?: return@withContext PointerLookup.Unavailable("the peer network session is not running")
        if (!dhtEnabled()) return@withContext PointerLookup.Unavailable("the DHT is turned off")
        if (!runCatching { live.isDhtRunning }.getOrDefault(false)) {
            return@withContext PointerLookup.Unavailable("the DHT is not running")
        }
        if (publicKeys.isEmpty()) return@withContext PointerLookup.Unavailable("there is no publisher key to look up")

        val startedAt = System.nanoTime()
        val nodeWaitMs = (timeoutMs / 2).coerceAtMost(MAX_NODE_WAIT_MS)
        if (!waitForNodes(live, nodeWaitMs)) {
            return@withContext PointerLookup.Unavailable("the DHT found no nodes within ${nodeWaitMs / MS_PER_S} s")
        }

        lookupMutex.withLock {
            var best: PointerLookup.Found? = null
            var incomplete = false
            for (key in publicKeys) {
                val remaining = timeoutMs - elapsedMs(startedAt)
                if (remaining <= 0) {
                    incomplete = true
                    break
                }
                val result = lookupKey(live, key, salt, remaining)
                if (!result.completed) incomplete = true
                val found = result.found
                val current = best
                if (found != null && (current == null || found.isNewerThan(current))) best = found
            }
            val chosen = best
            when {
                chosen != null -> chosen
                incomplete -> PointerLookup.Unavailable("the DHT lookup did not finish within ${timeoutMs / MS_PER_S} s")
                else -> PointerLookup.NotFound
            }
        }
    }

    private class KeyLookup(val found: PointerLookup.Found?, val completed: Boolean)

    private suspend fun lookupKey(live: SessionManager, key: ByteArray, salt: ByteArray, timeoutMs: Long): KeyLookup {
        val keyHex = Hex.encode(key)
        val lock = Any()
        var best: PointerLookup.Found? = null
        val completed = CompletableDeferred<Unit>()

        val listener = object : AlertListener {
            override fun types(): IntArray = intArrayOf(AlertType.DHT_MUTABLE_ITEM.swig())

            override fun alert(alert: Alert<*>) {
                val item = (alert as? DhtMutableItemAlert)?.swig() ?: return
                runCatching {
                    val authoritative = item.getAuthoritative()
                    val alertKey = org.libtorrent4j.Vectors.byte_vector2bytes(item.get_key())
                    val alertSalt = org.libtorrent4j.Vectors.byte_vector2bytes(item.get_salt())
                    if (alertKey.contentEquals(key) && alertSalt.contentEquals(salt)) {
                        // libtorrent reports each better item as it hears of it, then a final
                        // authoritative report once every node it asked has answered.
                        val pointer = Bencoding.dictionary(item.getItem())?.let(CataloguePointer::fromMap)
                        if (pointer != null) {
                            val candidate = PointerLookup.Found(pointer, item.get_seq(), authoritative, keyHex)
                            synchronized(lock) {
                                val current = best
                                if (current == null || !current.isNewerThan(candidate)) best = candidate
                            }
                        }
                        if (authoritative) completed.complete(Unit)
                    } else if (authoritative && alertKey.all { it == ZERO }) {
                        // A lookup that found nothing may report an empty item with no key. Lookups
                        // run one at a time, so it can only belong to this one.
                        completed.complete(Unit)
                    }
                }.onFailure { log.warn("Could not read a DHT item", it) }
            }
        }

        live.addListener(listener)
        try {
            SessionHandle(live.swig()).dhtGetItem(key, salt)
            withTimeoutOrNull(timeoutMs) { completed.await() }
        } finally {
            live.removeListener(listener)
        }

        val found = synchronized(lock) { best }
        log.info(
            when {
                found != null -> "DHT lookup for key ${keyHex.take(KEY_PREVIEW)}: catalogue ${found.pointer.catalogVersion} " +
                    "at ${found.pointer.infoHash} (seq=${found.seq}, authoritative=${found.authoritative})"
                completed.isCompleted -> "DHT lookup for key ${keyHex.take(KEY_PREVIEW)}: nothing published"
                else -> "DHT lookup for key ${keyHex.take(KEY_PREVIEW)}: no answer within ${timeoutMs / MS_PER_S} s"
            },
        )
        return KeyLookup(found, completed.isCompleted)
    }

    private suspend fun waitForNodes(live: SessionManager, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MS
        while (true) {
            if (runCatching { live.dhtNodes() }.getOrDefault(0L) > 0) return true
            if (System.nanoTime() >= deadline) return false
            delay(NODE_POLL_MS)
        }
    }

    // --- Download --------------------------------------------------------------------------------

    override suspend fun download(
        request: CatalogueDownloadRequest,
        onProgress: (Float) -> Unit,
    ): CatalogueDownload = withContext(ioDispatcher) {
        val live = session() ?: throw CatalogueTransportException.NoSession()
        if (!CataloguePointer.isValidInfoHash(request.infoHash)) {
            throw CatalogueTransportException.UnexpectedContents("\"${request.infoHash}\" is not an info hash")
        }
        val hash = Sha1Hash(request.infoHash)
        val deadline = System.nanoTime() + request.timeoutMs * NANOS_PER_MS

        // A handle left from an earlier attempt would keep its old save path; start clean.
        removeAndWait(live, hash, deleteFiles = false)
        if (!request.saveDir.isDirectory && !request.saveDir.mkdirs()) {
            throw CatalogueTransportException.Engine("could not create ${request.saveDir.path}")
        }

        try {
            addMagnet(live, request)
            holePuncher?.track(request.infoHash)
            log.info("Fetching catalogue torrent ${request.infoHash}")
            var nextDhtAnnounceAt = System.nanoTime() + DHT_REANNOUNCE_MS * NANOS_PER_MS

            // 1. Metadata: the torrent's file list, from a peer.
            val handle = pollUntil(deadline) {
                val candidate = live.find(hash)?.takeIf { it.isValid } ?: return@pollUntil null
                failIfErrored(candidate)
                nextDhtAnnounceAt = reannounceIfDue(candidate, nextDhtAnnounceAt)
                candidate.takeIf { it.torrentFile() != null }
            } ?: throw CatalogueTransportException.MetadataTimeout(
                "${request.timeoutMs / MS_PER_S} s, ${dhtNodes()} DHT nodes, ${peerCount(live, hash)} peers",
            )
            // From here on, an owned copy. The torrent info a handle hands out points into the session's
            // own torrent and stays valid only while that torrent is in the session.
            val torrentBytes = handle.torrentFile()?.bencode()
                ?: throw CatalogueTransportException.Engine("libtorrent dropped the metadata of ${request.infoHash}")
            val info = TorrentInfo(torrentBytes)
            if (info.totalSize() > request.maxBytes) {
                throw CatalogueTransportException.TooLarge(info.totalSize(), request.maxBytes)
            }
            CatalogueTorrents.layoutProblem(info)?.let { throw CatalogueTransportException.UnexpectedContents(it) }
            runCatching { handle.resume() }

            // 2. Data.
            var lastProgress = 0f
            onProgress(lastProgress)
            val finished = pollUntil(deadline) {
                failIfErrored(handle)
                nextDhtAnnounceAt = reannounceIfDue(handle, nextDhtAnnounceAt)
                val status = handle.status()
                val progress = status.progress()
                if (progress != lastProgress) {
                    lastProgress = progress
                    onProgress(progress)
                }
                (status.isFinished || status.isSeeding).takeIf { it }
            }
            if (finished == null) throw CatalogueTransportException.DownloadTimeout(lastProgress)

            // 3. Everything on disk before anyone reads it: libtorrent writes through a cache.
            flushToDisk(live, handle)
            onProgress(1f)

            val rootDir = File(request.saveDir, info.name())
            log.info("Catalogue torrent ${request.infoHash} complete: ${info.totalSize()} bytes in ${rootDir.path}")
            CatalogueDownload(
                infoHash = request.infoHash,
                torrentBytes = torrentBytes,
                rootDir = rootDir,
                totalBytes = info.totalSize(),
            )
        } catch (error: CatalogueTransportException) {
            discard(live, hash)
            throw error
        } catch (cancelled: CancellationException) {
            discard(live, hash)
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            discard(live, hash)
            throw CatalogueTransportException.Engine(error.message ?: error::class.java.simpleName, error)
        } finally {
            // A finished download is handed to seed(), which tracks it again for as long as it seeds.
            holePuncher?.untrack(request.infoHash)
        }
    }

    private fun addMagnet(live: SessionManager, request: CatalogueDownloadRequest) {
        val params = AddTorrentParams.parseMagnetUri("magnet:?xt=urn:btih:${request.infoHash}")
        params.savePath(request.saveDir.absolutePath)
        params.flags(startImmediately(params))
        val trackers = extraTrackers()
        if (trackers.isNotEmpty()) params.trackers(trackers)
        if (request.peers.isNotEmpty()) params.peers(request.peers.map { TcpEndpoint(it.hostString, it.port) })
        SessionHandle(live.swig()).asyncAddTorrent(params)
    }

    /** Neither paused nor auto-managed: a catalogue torrent starts now, whatever the queue holds. */
    private fun startImmediately(params: AddTorrentParams) = params.flags()
        .and_(TorrentFlags.PAUSED.inv())
        .and_(TorrentFlags.AUTO_MANAGED.inv())

    /**
     * Asks the DHT for peers again once [dueAtNanos] has passed.
     *
     * libtorrent otherwise asks again only every fifteen minutes. A download that starts a moment
     * before its only seeder has announced (a television checking right after a release goes out) would
     * wait that long, and a catalogue download is given three minutes.
     *
     * @return when the next announce is due.
     */
    private fun reannounceIfDue(handle: TorrentHandle, dueAtNanos: Long): Long {
        val now = System.nanoTime()
        if (now < dueAtNanos) return dueAtNanos
        runCatching { handle.forceDHTAnnounce() }
        return now + DHT_REANNOUNCE_MS * NANOS_PER_MS
    }

    private fun failIfErrored(handle: TorrentHandle) {
        val error = runCatching { handle.status().errorCode() }.getOrNull() ?: return
        if (error.isError) throw CatalogueTransportException.Engine("the torrent failed: ${error.message()}")
    }

    private fun peerCount(live: SessionManager, hash: Sha1Hash): Int =
        runCatching { live.find(hash)?.status()?.numPeers() ?: 0 }.getOrDefault(0)

    private suspend fun flushToDisk(live: SessionManager, handle: TorrentHandle) {
        val hash = handle.infoHash()
        val flushed = CompletableDeferred<Unit>()
        val listener = object : AlertListener {
            override fun types(): IntArray = intArrayOf(AlertType.CACHE_FLUSHED.swig())

            override fun alert(alert: Alert<*>) {
                runCatching {
                    if ((alert as? CacheFlushedAlert)?.handle()?.infoHash() == hash) flushed.complete(Unit)
                }
            }
        }
        live.addListener(listener)
        try {
            handle.flushCache()
            if (withTimeoutOrNull(FLUSH_TIMEOUT_MS) { flushed.await() } == null) {
                log.warn("libtorrent did not confirm the cache flush for $hash within ${FLUSH_TIMEOUT_MS / MS_PER_S} s")
            }
        } finally {
            live.removeListener(listener)
        }
    }

    /** Removes a failed download and deletes what it wrote. Never throws; runs even when cancelled. */
    private suspend fun discard(live: SessionManager, hash: Sha1Hash) {
        withContext(NonCancellable) { runCatching { removeAndWait(live, hash, deleteFiles = true) } }
    }

    // --- Seeding ---------------------------------------------------------------------------------

    override suspend fun seed(torrentBytes: ByteArray, saveDir: File): String = withContext(ioDispatcher) {
        val live = session() ?: throw CatalogueTransportException.NoSession()
        val info = try {
            TorrentInfo(torrentBytes)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            throw CatalogueTransportException.UnexpectedContents("not a torrent: ${error.message}")
        }
        CatalogueTorrents.layoutProblem(info)?.let { throw CatalogueTransportException.UnexpectedContents(it) }
        val hex = info.infoHash().toHex()
        // An owned hash: the one the torrent info returns points into the torrent info's native memory.
        val hash = Sha1Hash(hex)
        log.debug("Seed $hex: torrent parsed")

        val existing = runCatching { live.find(hash) }.getOrNull()?.takeIf { it.isValid }
        if (existing != null) {
            if (File(existing.savePath()).absoluteFile == saveDir.absoluteFile) {
                runCatching { existing.resume() }
                return@withContext hex
            }
            removeAndWait(live, hash, deleteFiles = false)
        }

        val params = AddTorrentParams.createInstance()
        params.torrentInfo(info)
        params.savePath(saveDir.absolutePath)
        params.flags(startImmediately(params))
        val trackers = extraTrackers()
        if (trackers.isNotEmpty()) params.trackers(trackers)
        log.debug("Seed $hex: adding it to the session")
        SessionHandle(live.swig()).asyncAddTorrent(params)

        val addDeadline = System.nanoTime() + ADD_TIMEOUT_MS * NANOS_PER_MS
        val handle = pollUntil(addDeadline) { live.find(hash)?.takeIf { it.isValid } }
            ?: throw CatalogueTransportException.Engine("libtorrent did not add the catalogue torrent $hex")
        runCatching { handle.resume() }
        log.debug("Seed $hex: added, waiting for the file check")

        // libtorrent announces nothing while it is still checking the files, so wait for the check (a
        // few hundred kilobytes: well under a second) and then announce straight away, rather than on
        // the DHT's own schedule, which can be many minutes away.
        val checkDeadline = System.nanoTime() + CHECK_TIMEOUT_MS * NANOS_PER_MS
        val checked = pollUntil(checkDeadline) {
            handle.status().takeIf { it.isSeeding || it.isFinished }
        }
        if (checked == null) log.warn("Catalogue torrent $hex is still being checked; it will announce once that finishes")
        log.debug("Seed $hex: checked, announcing")
        runCatching { handle.forceDHTAnnounce() }
        runCatching { handle.forceReannounce() }
        holePuncher?.track(hex)
        log.info("Seeding catalogue torrent ${info.name()} ($hex) from ${saveDir.path}")
        hex
    }

    override suspend fun stopSeeding(infoHash: String) = withContext(ioDispatcher) {
        holePuncher?.untrack(infoHash)
        val live = session() ?: return@withContext
        if (!CataloguePointer.isValidInfoHash(infoHash)) return@withContext
        removeAndWait(live, Sha1Hash(infoHash), deleteFiles = false)
        log.info("Stopped seeding catalogue torrent $infoHash")
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private suspend fun removeAndWait(live: SessionManager, hash: Sha1Hash, deleteFiles: Boolean) {
        val handle = runCatching { live.find(hash) }.getOrNull()?.takeIf { it.isValid } ?: return
        runCatching {
            if (deleteFiles) live.remove(handle, SessionHandle.DELETE_FILES) else live.remove(handle)
        }.onFailure { log.warn("Could not remove torrent $hash", it) }
        val deadline = System.nanoTime() + REMOVE_TIMEOUT_MS * NANOS_PER_MS
        pollUntil(deadline) { runCatching { live.find(hash) }.getOrNull()?.takeIf { it.isValid }?.let { null } ?: Unit }
    }

    /** Polls [probe] until it returns non-null or [deadlineNanos] passes. Exceptions it throws propagate. */
    private suspend fun <T : Any> pollUntil(deadlineNanos: Long, probe: () -> T?): T? {
        while (true) {
            probe()?.let { return it }
            if (System.nanoTime() >= deadlineNanos) return null
            delay(POLL_MS)
        }
    }

    private fun elapsedMs(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / NANOS_PER_MS

    private fun PointerLookup.Found.isNewerThan(other: PointerLookup.Found): Boolean =
        pointer.catalogVersion > other.pointer.catalogVersion ||
            (pointer.catalogVersion == other.pointer.catalogVersion && seq > other.seq)

    private companion object {
        const val POLL_MS = 250L
        const val NODE_POLL_MS = 500L
        const val MAX_NODE_WAIT_MS = 30_000L
        const val FLUSH_TIMEOUT_MS = 10_000L
        const val ADD_TIMEOUT_MS = 15_000L
        const val CHECK_TIMEOUT_MS = 15_000L
        const val REMOVE_TIMEOUT_MS = 5_000L
        const val DHT_REANNOUNCE_MS = 15_000L
        const val NANOS_PER_MS = 1_000_000L
        const val MS_PER_S = 1_000L
        const val KEY_PREVIEW = 12
        const val ZERO: Byte = 0
    }
}
