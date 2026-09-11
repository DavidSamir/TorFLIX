package com.torfilx.core.torrent

import android.os.Build
import android.os.SystemClock
import com.torfilx.core.common.log.TorfilxLog
import org.libtorrent4j.AlertListener
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType

/**
 * Watches the libtorrent alert stream and keeps a running picture of what the engine is actually
 * doing on the network — so that when a title fails to play, the failure is legible.
 *
 * This exists because peer discovery is invisible from the outside: "no peers responded" can mean
 * the DHT never joined, the listen socket never bound, every tracker rejected us, or peers were
 * found but held no metadata — and each has a different fix. The [summary] this produces is shown
 * on the TV's error screen verbatim, so a photograph of that screen is a full diagnosis.
 *
 * Every callback arrives on libtorrent's own alert thread; all mutable state is guarded by [lock].
 */
internal class TorrentDiagnostics : AlertListener {

    private val lock = Any()
    private var startedAtMs = SystemClock.elapsedRealtime()

    // Latching facts about this session.
    private var listenOk = false
    private var listenDetail = "pending"
    private var dhtBootstrapped = false
    private var trackersAnnounced = 0
    private var trackersReplied = 0
    private var trackersErrored = 0
    private var trackerPeersSeen = 0
    private var peersConnected = 0
    private var metadataReceived = false
    private var lastError: String? = null

    /** A short rolling log of the most recent significant events, each with elapsed time. */
    private val events = ArrayDeque<String>()

    /** Marks the moment a fresh play attempt began, so elapsed times are relative to the tap. */
    fun markAttemptStart() = synchronized(lock) {
        startedAtMs = SystemClock.elapsedRealtime()
        // Latched network facts (listen, DHT) survive across attempts; per-attempt counters reset.
        trackersAnnounced = 0
        trackersReplied = 0
        trackersErrored = 0
        trackerPeersSeen = 0
        peersConnected = 0
        metadataReceived = false
        lastError = null
        events.clear()
        record("attempt started")
    }

    fun metadataWasReceived(): Boolean = synchronized(lock) { metadataReceived }

    override fun types(): IntArray = WATCHED_TYPES

    override fun alert(alert: Alert<*>) {
        // Never let a diagnostic bookkeeping error affect playback.
        runCatching {
            synchronized(lock) {
                when (alert.type()) {
                    AlertType.LISTEN_SUCCEEDED -> {
                        listenOk = true
                        listenDetail = alert.message()
                        record("listen OK: ${alert.message()}")
                    }
                    AlertType.LISTEN_FAILED -> {
                        listenOk = false
                        listenDetail = alert.message()
                        record("listen FAILED: ${alert.message()}")
                    }
                    AlertType.DHT_BOOTSTRAP -> {
                        dhtBootstrapped = true
                        record("DHT bootstrapped")
                    }
                    AlertType.TRACKER_ANNOUNCE -> {
                        trackersAnnounced++
                        record("tracker announce: ${alert.message()}")
                    }
                    AlertType.TRACKER_REPLY -> {
                        trackersReplied++
                        val peers = (alert as? org.libtorrent4j.alerts.TrackerReplyAlert)?.numPeers() ?: 0
                        trackerPeersSeen += peers
                        record("tracker reply ($peers peers): ${alert.message()}")
                    }
                    AlertType.TRACKER_ERROR, AlertType.TRACKER_WARNING -> {
                        trackersErrored++
                        record("tracker error: ${alert.message()}")
                    }
                    AlertType.PEER_CONNECT -> {
                        peersConnected++
                    }
                    AlertType.METADATA_RECEIVED -> {
                        metadataReceived = true
                        record("METADATA received")
                    }
                    AlertType.METADATA_FAILED -> record("metadata failed: ${alert.message()}")
                    AlertType.TORRENT_ERROR, AlertType.DHT_ERROR -> {
                        lastError = alert.message()
                        record("error: ${alert.message()}")
                    }
                    // Catalogue pointer lookups. Only the authoritative answer is recorded: libtorrent
                    // also reports every interim improvement, which would crowd out everything else.
                    AlertType.DHT_MUTABLE_ITEM -> {
                        val item = (alert as? org.libtorrent4j.alerts.DhtMutableItemAlert)?.swig()
                        if (item != null && item.getAuthoritative()) {
                            record("DHT catalogue item: seq=${item.get_seq()} authoritative")
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun record(what: String) {
        val elapsed = (SystemClock.elapsedRealtime() - startedAtMs) / 1000.0
        events.addLast("+%.1fs %s".format(elapsed, what))
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    /**
     * A compact, human-readable snapshot for the on-screen error and the log. Callers pass the live
     * DHT node count and running flag, which are session-level queries this listener does not hold.
     */
    fun summary(dhtNodes: Long, dhtRunning: Boolean): String = synchronized(lock) {
        buildString {
            appendLine("device: Android ${Build.VERSION.SDK_INT} · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
            appendLine("listen: ${if (listenOk) "OK" else "not bound"} ($listenDetail)")
            appendLine("DHT: running=$dhtRunning nodes=$dhtNodes bootstrapped=$dhtBootstrapped")
            appendLine(
                "trackers: announced=$trackersAnnounced replied=$trackersReplied " +
                    "(peers=$trackerPeersSeen) errors=$trackersErrored",
            )
            appendLine("peers connected: $peersConnected")
            appendLine("metadata: ${if (metadataReceived) "received" else "NOT received"}")
            lastError?.let { appendLine("last error: $it") }
            if (events.isNotEmpty()) {
                appendLine("recent:")
                events.takeLast(SUMMARY_EVENTS).forEach { appendLine("  $it") }
            }
        }.trimEnd()
    }

    /** Writes the current snapshot to the log at warning level so it lands in `adb logcat`. */
    fun logSnapshot(reason: String, dhtNodes: Long, dhtRunning: Boolean) {
        TorfilxLog.w(TAG, "Torrent diagnostics ($reason):\n${summary(dhtNodes, dhtRunning)}")
    }

    private companion object {
        const val TAG = "TorrentDiag"
        const val MAX_EVENTS = 40
        const val SUMMARY_EVENTS = 8

        val WATCHED_TYPES = intArrayOf(
            AlertType.LISTEN_SUCCEEDED.swig(),
            AlertType.LISTEN_FAILED.swig(),
            AlertType.DHT_BOOTSTRAP.swig(),
            AlertType.TRACKER_ANNOUNCE.swig(),
            AlertType.TRACKER_REPLY.swig(),
            AlertType.TRACKER_ERROR.swig(),
            AlertType.TRACKER_WARNING.swig(),
            AlertType.PEER_CONNECT.swig(),
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.METADATA_FAILED.swig(),
            AlertType.TORRENT_ERROR.swig(),
            AlertType.DHT_ERROR.swig(),
            // Without this the catalogue branch in alert() never receives anything.
            AlertType.DHT_MUTABLE_ITEM.swig(),
        )
    }
}
