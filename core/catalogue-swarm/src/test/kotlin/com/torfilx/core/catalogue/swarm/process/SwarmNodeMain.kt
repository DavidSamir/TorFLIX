package com.torfilx.core.catalogue.swarm.process

import com.torfilx.core.catalogue.Hex
import com.torfilx.core.catalogue.crypto.Ed25519Verifier
import com.torfilx.core.catalogue.format.CatalogRelease
import com.torfilx.core.catalogue.format.CataloguePointer
import com.torfilx.core.catalogue.release.CatalogueFetcher
import com.torfilx.core.catalogue.release.CatalogueReleaseVerifier
import com.torfilx.core.catalogue.swarm.CataloguePublisher
import com.torfilx.core.catalogue.swarm.CatalogueTorrents
import com.torfilx.core.catalogue.swarm.SessionShutdown
import com.torfilx.core.catalogue.swarm.SwarmCatalogueTransport
import com.torfilx.core.catalogue.swarm.SwarmLog
import com.torfilx.core.catalogue.swarm.SwarmSessions
import com.torfilx.core.catalogue.transport.CatalogueDownloadRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Sha1Hash
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.system.exitProcess

/**
 * One libtorrent session in a process of its own, driven one line at a time over standard input.
 *
 * Several libtorrent 1.2 sessions that are busy on the DHT inside one process corrupt each other's
 * native memory now and then, and the whole process dies. The app and the publisher tool each run a
 * single session, so the network tests do the same: every node is a process, driven by the test through
 * [SwarmNodeProcess].
 *
 * A command is one line of words separated by spaces, and each gets exactly one answer line starting with
 * OK or ERR. Paths and free text travel through [Wire], so spaces cannot split them. Logs go to standard
 * error. The node stops when told to QUIT or when its standard input closes, so it never outlives a test.
 */
object SwarmNodeMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val node = SwarmNode()
        val input = System.`in`.bufferedReader()
        runBlocking {
            while (true) {
                val line = input.readLine() ?: break
                val words = line.split(' ').filter { it.isNotEmpty() }
                if (words.isEmpty()) continue
                val answer = try {
                    node.handle(words)
                } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                    System.err.println("command failed: ${words.first()}")
                    error.printStackTrace(System.err)
                    "ERR ${error::class.java.simpleName} ${Wire.encode(error.message.orEmpty())}"
                }
                println(answer)
                System.out.flush()
                if (words.first() == "QUIT") break
            }
        }
        node.stop()
        exitProcess(0)
    }
}

private class SwarmNode {

    private val running = MutableStateFlow(true)
    private val log = SwarmLog { level, message, error ->
        System.err.println("${level.name} $message")
        error?.printStackTrace(System.err)
    }
    private var session: SessionManager? = null
    private val transport = SwarmCatalogueTransport(session = { session }, sessionRunning = running, log = log)
    private var stopped = false

    private fun live(): SessionManager = checkNotNull(session) { "START first" }

    suspend fun handle(words: List<String>): String = when (words[0]) {
        "START" -> start(words)
        "AWAIT_NODES" ->
            if (SwarmSessions.awaitDhtNodes(live(), 1, words[1].toLong())) "OK ${live().dhtNodes()}" else "ERR no-dht-nodes"
        "SEED" -> seed(File(Wire.decode(words[1])))
        "SEED_TORRENT" -> "OK " + transport.seed(File(Wire.decode(words[1])).readBytes(), File(Wire.decode(words[2])))
        "PUT" -> put(words)
        "FETCH" -> fetch(words)
        "DOWNLOAD" -> download(words)
        "STOP_SEEDING" -> {
            transport.stopSeeding(words[1])
            "OK"
        }
        "HAS" -> has(words[1])
        "QUIT" -> if (stop()) "OK stopped" else "OK stop-overran"
        else -> "ERR unknown-command ${Wire.encode(words[0])}"
    }

    /** START, or START JOIN host dhtPort to join the DHT through another node. Answers the TCP and UDP ports. */
    private suspend fun start(words: List<String>): String {
        check(session == null) { "the session is already running" }
        val known = if (words.getOrNull(1) == "JOIN") listOf(InetSocketAddress(words[2], words[3].toInt())) else emptyList()
        val live = SwarmSessions.start(
            SwarmSessions.Options(listenInterfaces = LOOPBACK_ANY_PORT, dhtNodes = known, privateNetwork = true),
        )
        session = live
        val udp = SwarmSessions.awaitDhtPort(live) ?: return "ERR no-dht-port"
        repeat(PORT_ATTEMPTS) {
            val tcp = runCatching { SwarmSessions.listenPort(live) }.getOrDefault(0)
            if (tcp > 0) return "OK $tcp $udp"
            delay(PORT_POLL_MS)
        }
        return "ERR no-listen-port"
    }

    /** SEED releaseRoot: builds the release torrent, saves it beside the release, and seeds it. */
    private suspend fun seed(root: File): String {
        val torrent = CatalogueTorrents.build(root, trackers = emptyList())
        val torrentFile = File(root.parentFile, root.name + ".torrent")
        torrentFile.writeBytes(torrent)
        val hash = transport.seed(torrent, root.parentFile)
        return "OK $hash ${Wire.encode(torrentFile.path)}"
    }

    /** PUT seedHex infoHash version saltHex: answers the sequence number and how many nodes stored it. */
    private suspend fun put(words: List<String>): String {
        val publisher = CataloguePublisher(live(), Hex.decode(words[1]), log)
        val pointer = CataloguePointer(CataloguePointer.FORMAT, words[2], words[3].toLong())
        val result = publisher.putPointer(pointer, Hex.decode(words[4])) ?: return "ERR put-timeout"
        return "OK ${result.seq} ${result.nodesStored}"
    }

    /** FETCH installed rejected keys saltHex saveBase lookupMs downloadMs peers, through CatalogueFetcher. */
    private suspend fun fetch(words: List<String>): String {
        val keys = words[3].split(',').map { Hex.decode(it) }
        val saveBase = File(Wire.decode(words[5]))
        val fetcher = CatalogueFetcher(transport, CatalogueReleaseVerifier(Ed25519Verifier(keys)), keys, Hex.decode(words[4]))
        val outcome = fetcher.fetch(
            CatalogueFetcher.Request(
                installedVersion = words[1].toLong(),
                rejectedVersion = words[2].takeIf { it != NONE }?.toLong(),
                appVersionCode = 1,
                saveDirFor = { version -> File(saveBase, "releases/$version") },
                lookupTimeoutMs = words[6].toLong(),
                downloadTimeoutMs = words[7].toLong(),
                peers = peers(words[8]),
            ),
        )
        return when (outcome) {
            is CatalogueFetcher.Outcome.Fetched -> {
                val torrentFile = File(outcome.download.rootDir.parentFile, TORRENT_FILE_NAME)
                torrentFile.writeBytes(outcome.download.torrentBytes)
                "OK fetched ${outcome.manifest.catalogVersion} ${outcome.download.infoHash} ${outcome.entries.size} " +
                    "${Wire.encode(outcome.download.rootDir.path)} ${Wire.encode(torrentFile.path)}"
            }
            is CatalogueFetcher.Outcome.UpToDate -> "OK uptodate ${outcome.remoteVersion} ${outcome.installedVersion}"
            is CatalogueFetcher.Outcome.Skipped -> "OK skipped ${outcome.remoteVersion}"
            CatalogueFetcher.Outcome.NotFound -> "OK notfound"
            is CatalogueFetcher.Outcome.Unavailable -> "OK unavailable ${Wire.encode(outcome.reason)}"
            is CatalogueFetcher.Outcome.Rejected ->
                "OK rejected ${outcome.version} ${outcome.reason.name} ${Wire.encode(outcome.detail)}"
            is CatalogueFetcher.Outcome.TransportFailed -> "OK failed ${Wire.encode(outcome.error.toString())}"
        }
    }

    /** DOWNLOAD infoHash saveDir timeoutMs peers: answers the release root. A transport error answers ERR. */
    private suspend fun download(words: List<String>): String {
        val download = transport.download(
            CatalogueDownloadRequest(
                infoHash = words[1],
                saveDir = File(Wire.decode(words[2])),
                maxBytes = CatalogRelease.MAX_TORRENT_BYTES,
                timeoutMs = words[3].toLong(),
                peers = peers(words[4]),
            ),
        )
        return "OK ${Wire.encode(download.rootDir.path)}"
    }

    /** HAS infoHash: answers "absent", or "present" and whether it is seeding. */
    private fun has(hash: String): String {
        val handle = runCatching { live().find(Sha1Hash(hash)) }.getOrNull()?.takeIf { it.isValid } ?: return "OK absent"
        return "OK present ${runCatching { handle.status().isSeeding }.getOrDefault(false)}"
    }

    /** Stops the session with a bounded wait. @return false when libtorrent overran it. */
    fun stop(): Boolean {
        if (stopped) return true
        stopped = true
        val live = session ?: return true
        return SessionShutdown().stopWithin(STOP_WAIT_MS) { live.stop() }
    }

    private fun peers(csv: String): List<InetSocketAddress> =
        if (csv == NONE) {
            emptyList()
        } else {
            csv.split(',').map { InetSocketAddress(it.substringBefore(':'), it.substringAfter(':').toInt()) }
        }

    private companion object {
        const val LOOPBACK_ANY_PORT = "127.0.0.1:0"
        const val PORT_ATTEMPTS = 50
        const val PORT_POLL_MS = 100L
        const val STOP_WAIT_MS = 20_000L
        const val NONE = "-"
        const val TORRENT_FILE_NAME = "catalogue.torrent"
    }
}

/** Words that may contain spaces travel as unpadded URL-safe base64; a tilde stands for the empty string. */
internal object Wire {

    private const val EMPTY = "~"

    fun encode(text: String): String =
        if (text.isEmpty()) EMPTY else Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

    fun decode(word: String): String =
        if (word == EMPTY) "" else String(Base64.getUrlDecoder().decode(word), Charsets.UTF_8)
}
