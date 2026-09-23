package com.torfilx.core.catalogue.swarm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.peer_info
import java.util.concurrent.ConcurrentHashMap

/**
 * Connects peers that both sit behind routers accepting no incoming connections.
 *
 * Televisions on ordinary home networks, and a publisher on an office network, usually accept nothing
 * from outside, and nobody can be asked to change a router. Two such peers can still reach each other:
 * a UDP packet a peer sends out opens a short-lived way back in through its own router, for replies
 * from the address it was sent to. So each side keeps sending uTP (BitTorrent over UDP) connection
 * attempts to the other's public address, which both learn from their once-a-minute DHT announces
 * (see [SwarmSessions.DHT_ANNOUNCE_INTERVAL_S]). Once one side's attempt arrives while the other's way
 * back in is still open, the connection is made, straight through both routers.
 *
 * libtorrent alone tries a peer over uTP once, then falls back to TCP (which cannot pass two such
 * routers) and backs off after a few failures. Every [PUNCH_INTERVAL_MS] this re-adds each address the
 * DHT lists, marked as reachable over uTP and as coming from a tracker: libtorrent then turns uTP back
 * on for that peer and forgives one failure, so it keeps trying over uTP every
 * [RECONNECT_INTERVAL_S] or so (the session's `min_reconnect_time`). A peer already connected is left
 * alone.
 *
 * Works through routers that keep one public port per socket, which most home routers and the
 * development PC's network do. Not through symmetric NATs (some carriers, many mobile networks); peers
 * behind those need a peer that accepts incoming connections, as they always did.
 *
 * One instance per session. [track] the torrents that should find peers (a download in progress, a
 * torrent being seeded); the loop runs on [scope] until it is cancelled and idles while nothing is
 * tracked or there is no session.
 */
class HolePuncher(
    private val session: () -> SessionManager?,
    private val scope: CoroutineScope,
    private val log: SwarmLog = SwarmLog.NONE,
) {
    /** Tracked info hashes (lower-case hex) and the addresses the DHT last listed for each. */
    private val tracked = ConcurrentHashMap<String, List<TcpEndpoint>>()

    @Volatile
    private var loops: Job? = null

    fun track(infoHash: String) {
        val key = infoHash.lowercase()
        if (tracked.putIfAbsent(key, emptyList()) == null) ensureRunning()
    }

    fun untrack(infoHash: String) {
        tracked.remove(infoHash.lowercase())
    }

    fun untrackAll() = tracked.clear()

    /** What is tracked now, for tests and logs. */
    fun trackedHashes(): Set<String> = tracked.keys.toSet()

    @Synchronized
    private fun ensureRunning() {
        if (loops?.isActive == true) return
        loops = scope.launch {
            launch { lookupLoop() }
            launch { punchLoop() }
        }
    }

    /** Asks the DHT who has announced each tracked torrent, one torrent at a time. Runs until cancelled. */
    private suspend fun lookupLoop() {
        while (true) {
            val live = session()
            val hashes = tracked.keys.toList()
            if (live == null || hashes.isEmpty() || !runCatching { live.isDhtRunning }.getOrDefault(false)) {
                delay(IDLE_MS)
                continue
            }
            for (hash in hashes) {
                if (!tracked.containsKey(hash)) continue
                // A tracked torrent that has left the session (a failed download, an evicted title)
                // costs no lookups until it is back.
                if (runCatching { live.find(Sha1Hash(hash))?.isValid }.getOrNull() != true) continue
                val found = try {
                    runInterruptible { live.dhtGetPeers(Sha1Hash(hash), LOOKUP_TIMEOUT_S) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                    log.debug("DHT peer lookup for ${hash.take(HASH_PREVIEW)} failed: ${error.message}")
                    null
                }
                // libtorrent4j's alert thread may still be appending to the list it returned after the
                // wait timed out, so take a copy and never iterate its list directly.
                val peers = found?.let { runCatching { ArrayList(it) }.getOrNull() }.orEmpty().distinctBy { it.toString() }
                val previous = tracked.replace(hash, peers)
                if (previous != null && peers.size != previous.size && peers.isNotEmpty()) {
                    log.debug("Hole punching toward ${peers.size} peer(s) of ${hash.take(HASH_PREVIEW)}")
                }
            }
            delay(LOOKUP_INTERVAL_MS)
        }
    }

    /** Re-adds every listed address as a uTP peer, so libtorrent keeps trying it over uTP. Runs until cancelled. */
    private suspend fun punchLoop() {
        while (true) {
            val live = session()
            if (live != null) {
                for ((hash, peers) in tracked) {
                    if (peers.isEmpty()) continue
                    val handle = runCatching { live.find(Sha1Hash(hash)) }.getOrNull()?.takeIf { it.isValid } ?: continue
                    val native = handle.swig()
                    for (peer in peers.take(MAX_PEERS_PER_TORRENT)) {
                        runCatching { native.connect_peer(peer.swig(), peer_info.tracker, libtorrent.getPex_utp()) }
                    }
                }
            }
            delay(PUNCH_INTERVAL_MS)
        }
    }

    companion object {
        /**
         * The session's `min_reconnect_time`, in seconds: how soon libtorrent tries a peer again after
         * a failed attempt. libtorrent's default is 60. A router typically keeps the way back in open for
         * 30 seconds or more after a packet goes out, so with both sides trying every 15 seconds one
         * side's attempt always arrives while the other's is still open.
         */
        const val RECONNECT_INTERVAL_S = 15

        private const val PUNCH_INTERVAL_MS = 5_000L
        private const val LOOKUP_INTERVAL_MS = 20_000L
        private const val LOOKUP_TIMEOUT_S = 10
        private const val IDLE_MS = 2_000L
        private const val MAX_PEERS_PER_TORRENT = 50
        private const val HASH_PREVIEW = 8
    }
}
