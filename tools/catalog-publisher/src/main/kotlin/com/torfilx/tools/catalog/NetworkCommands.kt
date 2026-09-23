package com.torfilx.tools.catalog

import com.torfilx.core.catalogue.Hex
import com.torfilx.core.catalogue.crypto.Ed25519Keys
import com.torfilx.core.catalogue.crypto.Ed25519Verifier
import com.torfilx.core.catalogue.format.CatalogRelease
import com.torfilx.core.catalogue.format.CataloguePointer
import com.torfilx.core.catalogue.release.CatalogueFetcher
import com.torfilx.core.catalogue.release.CatalogueReleaseVerifier
import com.torfilx.core.catalogue.swarm.CataloguePublisher
import com.torfilx.core.catalogue.swarm.CatalogueTorrents
import com.torfilx.core.catalogue.swarm.HolePuncher
import com.torfilx.core.catalogue.swarm.LibtorrentNative
import com.torfilx.core.catalogue.swarm.SwarmCatalogueTransport
import com.torfilx.core.catalogue.swarm.SwarmLog
import com.torfilx.core.catalogue.swarm.SessionShutdown
import com.torfilx.core.catalogue.swarm.SwarmSessions
import com.torfilx.core.catalogue.transport.PointerLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.PrintStream
import java.net.InetSocketAddress

/** The commands that start a libtorrent session: publish, fetch, and a plain DHT node. */
class NetworkCommands(
    private val out: PrintStream,
    private val err: PrintStream,
    private val clock: () -> Long,
    private val resolve: (String) -> File,
) {

    private val shutdown = SessionShutdown()

    private val log = SwarmLog { level, message, error ->
        err.println("  ${level.name.lowercase()}: $message")
        error?.let { err.println("    ${it::class.java.simpleName}: ${it.message}") }
    }

    // --- publish ---------------------------------------------------------------------------------

    fun publish(args: Args): Int = runBlocking {
        requireNative()
        val root = resolve(args.required("release"))
        val seed = SeedFiles.read(resolve(args.required("seed")))
        val torrentFile = args.value("torrent")?.let(resolve) ?: File(root.absoluteFile.parentFile, "${root.name}.torrent")
        val hours = args.double("hours") ?: DEFAULT_HOURS
        val reputMs = (args.long("reput-minutes") ?: DEFAULT_REPUT_MINUTES) * MS_PER_MINUTE
        val salt = (args.value("salt") ?: CatalogRelease.DHT_SALT).encodeToByteArray()
        val publicKey = Ed25519Keys.publicKeyOf(seed)

        val verdict = CatalogueReleaseVerifier(Ed25519Verifier(listOf(publicKey))).verify(root, Int.MAX_VALUE)
        val manifest = (verdict as? CatalogueReleaseVerifier.Result.Ok)?.manifest
            ?: throw IllegalStateException("${root.path} does not verify with this seed's key: $verdict")
        require(torrentFile.isFile) { "No torrent at ${torrentFile.path}; run build first" }
        val torrent = torrentFile.readBytes()
        val infoHash = CatalogueTorrents.infoHash(torrent)

        val session = startSession(args)
        val punchScope = punchScope()
        Runtime.getRuntime().addShutdownHook(Thread { stopSession(session) })
        try {
            out.println("Publishing catalogue ${manifest.catalogVersion} (${manifest.titleCount} titles)")
            out.println("  publisher key  ${Hex.encode(publicKey)}")
            out.println("  info hash      $infoHash")
            printPorts(session)
            joinDht(session)

            val transport = SwarmCatalogueTransport({ session }, MutableStateFlow(true), log = log, holePunchScope = punchScope)
            transport.seed(torrent, root.absoluteFile.parentFile)
            val publisher = CataloguePublisher(session, seed, log)
            check(publisher.publicKey.contentEquals(publicKey)) {
                "libtorrent and the pure-Java Ed25519 disagree on this seed's public key"
            }
            val pointer = CataloguePointer(CataloguePointer.FORMAT, infoHash, manifest.catalogVersion)

            val stopAt = clock() + (hours * MS_PER_HOUR).toLong()
            var nextPutAt = 0L
            var selfChecked = false
            while (clock() < stopAt) {
                if (clock() >= nextPutAt) {
                    val put = publisher.putPointer(pointer, salt)
                    if (put == null) {
                        out.println("warning: the DHT did not confirm the pointer; retrying in a minute")
                        nextPutAt = clock() + MS_PER_MINUTE
                    } else {
                        out.println("Pointer published: seq ${put.seq}, stored by ${put.nodesStored} DHT nodes")
                        nextPutAt = clock() + reputMs
                        if (!selfChecked) {
                            selfChecked = true
                            selfCheck(transport, publicKey, salt, pointer)
                        }
                    }
                }
                reportSeeding(session, infoHash)
                delay(STATUS_INTERVAL_MS.coerceAtMost((stopAt - clock()).coerceAtLeast(1)))
            }
            out.println("Stopped publishing after ${"%.2f".format(hours)} hours.")
            CatalogPublisherCli.EXIT_OK
        } finally {
            punchScope.cancel()
            stopSession(session)
        }
    }

    private suspend fun selfCheck(
        transport: SwarmCatalogueTransport,
        publicKey: ByteArray,
        salt: ByteArray,
        expected: CataloguePointer,
    ) {
        when (val lookup = transport.resolvePointer(listOf(publicKey), salt, SELF_CHECK_TIMEOUT_MS)) {
            is PointerLookup.Found ->
                if (lookup.pointer == expected) {
                    out.println("Self-check: the DHT returns catalogue ${lookup.pointer.catalogVersion} (seq ${lookup.seq})")
                } else {
                    out.println("warning: the DHT returns ${lookup.pointer}, not $expected; another publish may be racing this one")
                }
            PointerLookup.NotFound -> out.println("warning: self-check found no pointer yet; nodes may still be storing it")
            is PointerLookup.Unavailable -> out.println("warning: self-check could not run: ${lookup.reason}")
        }
    }

    private fun reportSeeding(session: SessionManager, infoHash: String) {
        val status = runCatching { session.find(Sha1Hash(infoHash))?.status() }.getOrNull()
        out.println(
            "  dht nodes ${session.dhtNodes()} | " +
                if (status == null) {
                    "torrent not in session"
                } else {
                    "peers ${status.numPeers()} | uploaded ${status.totalUpload()} bytes | " +
                        if (status.isSeeding) "seeding" else "checking ${(status.progress() * PERCENT).toInt()}%"
                },
        )
    }

    // --- fetch -----------------------------------------------------------------------------------

    fun fetch(args: Args): Int = runBlocking {
        requireNative()
        val keys = parseKeys(args.required("keys"))
        val outDir = resolve(args.required("out"))
        val installed = args.long("installed") ?: 0L
        val timeoutMs = (args.long("timeout-s") ?: DEFAULT_FETCH_TIMEOUT_S) * MS_PER_SECOND
        val salt = (args.value("salt") ?: CatalogRelease.DHT_SALT).encodeToByteArray()
        val appVersionCode = args.int("app-version-code") ?: Int.MAX_VALUE
        val peers = args.values("peer").map(::parseEndpoint).map { InetSocketAddress(it.hostString, it.port) }

        val session = startSession(args)
        val punchScope = punchScope()
        try {
            printPorts(session)
            joinDht(session)
            val transport = SwarmCatalogueTransport({ session }, MutableStateFlow(true), log = log, holePunchScope = punchScope)
            val fetcher = CatalogueFetcher(transport, CatalogueReleaseVerifier(Ed25519Verifier(keys)), keys, salt)
            var lastPercent = -1
            val outcome = fetcher.fetch(
                CatalogueFetcher.Request(
                    installedVersion = installed,
                    rejectedVersion = null,
                    appVersionCode = appVersionCode,
                    saveDirFor = { version -> File(outDir, version.toString()).also { it.mkdirs() } },
                    lookupTimeoutMs = timeoutMs / 2,
                    downloadTimeoutMs = timeoutMs,
                    peers = peers,
                    onDownloading = { version, progress ->
                        val percent = (progress * PERCENT).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            out.println("Downloading catalogue $version: $percent%")
                        }
                    },
                ),
            )
            report(outcome)
        } finally {
            punchScope.cancel()
            stopSession(session)
        }
    }

    private fun report(outcome: CatalogueFetcher.Outcome): Int = when (outcome) {
        is CatalogueFetcher.Outcome.Fetched -> {
            out.println(
                "FETCHED catalogue ${outcome.manifest.catalogVersion}: ${outcome.manifest.titleCount} titles, " +
                    "verified, in ${outcome.download.rootDir.path}",
            )
            CatalogPublisherCli.EXIT_OK
        }
        is CatalogueFetcher.Outcome.UpToDate -> {
            out.println("UP TO DATE: the published catalogue is ${outcome.remoteVersion}")
            CatalogPublisherCli.EXIT_OK
        }
        CatalogueFetcher.Outcome.NotFound -> {
            out.println("NOT FOUND: nothing is published under these keys")
            CatalogPublisherCli.EXIT_FAILED
        }
        is CatalogueFetcher.Outcome.Unavailable -> {
            out.println("UNAVAILABLE: ${outcome.reason}")
            CatalogPublisherCli.EXIT_FAILED
        }
        is CatalogueFetcher.Outcome.Skipped -> {
            out.println("SKIPPED: catalogue ${outcome.remoteVersion}")
            CatalogPublisherCli.EXIT_FAILED
        }
        is CatalogueFetcher.Outcome.Rejected -> {
            out.println("REJECTED catalogue ${outcome.version} (${outcome.reason}): ${outcome.detail}")
            CatalogPublisherCli.EXIT_FAILED
        }
        is CatalogueFetcher.Outcome.TransportFailed -> {
            out.println("FAILED${outcome.version?.let { " on catalogue $it" }.orEmpty()}: ${outcome.error.message}")
            CatalogPublisherCli.EXIT_FAILED
        }
    }

    // --- seed-titles -----------------------------------------------------------------------------

    /**
     * Seeds title torrents (films, episodes) so televisions can stream them, on the same kind of session
     * as the app: announcing to the DHT every minute and hole punching toward every peer it lists, so a
     * television reaches this machine even when neither side's router accepts incoming connections.
     *
     * Every `<name>.torrent` in `--dir` is seeded from the files in `--data` (default: `--dir`), which
     * must be the exact files the torrent was made from.
     */
    fun seedTitles(args: Args): Int = runBlocking {
        requireNative()
        val torrentDir = resolve(args.required("dir"))
        val dataDir = args.value("data")?.let(resolve) ?: torrentDir
        val hours = args.double("hours") ?: DEFAULT_HOURS
        val torrentFiles = torrentDir.listFiles { file -> file.isFile && file.name.endsWith(".torrent") }
            ?.sortedBy { it.name }.orEmpty()
        require(torrentFiles.isNotEmpty()) { "No .torrent files in ${torrentDir.path}" }

        val session = startSession(args)
        val punchScope = punchScope()
        Runtime.getRuntime().addShutdownHook(Thread { stopSession(session) })
        try {
            out.println("Seeding ${torrentFiles.size} title torrent(s) from ${dataDir.path}")
            printPorts(session)
            joinDht(session)

            val puncher = HolePuncher({ session }, punchScope, log)
            val seeded = torrentFiles.map { file ->
                val info = TorrentInfo(file.readBytes())
                val hash = info.infoHash().toHex()
                val params = AddTorrentParams.createInstance()
                params.torrentInfo(info)
                params.savePath(dataDir.absolutePath)
                params.flags(params.flags().and_(TorrentFlags.PAUSED.inv()).and_(TorrentFlags.AUTO_MANAGED.inv()))
                SessionHandle(session.swig()).asyncAddTorrent(params)
                out.println("  $hash  ${info.name()}")
                hash to info.name()
            }

            // Announce as soon as each file check is done rather than on the DHT's own schedule.
            val checkDeadline = clock() + CHECK_TIMEOUT_MS
            val pending = seeded.map { it.first }.toMutableSet()
            while (pending.isNotEmpty() && clock() < checkDeadline) {
                pending.removeAll { hash ->
                    val handle = session.find(Sha1Hash(hash))?.takeIf { it.isValid } ?: return@removeAll false
                    val status = handle.status()
                    val done = status.isSeeding || status.isFinished
                    if (done) {
                        runCatching { handle.forceDHTAnnounce() }
                        runCatching { handle.forceReannounce() }
                        puncher.track(hash)
                    }
                    done
                }
                delay(CHECK_POLL_MS)
            }
            pending.forEach { hash ->
                out.println("warning: $hash is not complete in ${dataDir.path}; is its file there, unchanged?")
            }

            val stopAt = clock() + (hours * MS_PER_HOUR).toLong()
            while (clock() < stopAt) {
                out.println("  dht nodes ${session.dhtNodes()}")
                seeded.forEach { (hash, name) ->
                    val status = runCatching { session.find(Sha1Hash(hash))?.status() }.getOrNull()
                    out.println(
                        "    ${name.take(NAME_PREVIEW)}: " + if (status == null) {
                            "not in session"
                        } else {
                            "peers ${status.numPeers()} | uploaded ${status.totalUpload() / BYTES_PER_MB} MB | " +
                                if (status.isSeeding) "seeding" else "checking ${(status.progress() * PERCENT).toInt()}%"
                        },
                    )
                }
                delay(STATUS_INTERVAL_MS.coerceAtMost((stopAt - clock()).coerceAtLeast(1)))
            }
            out.println("Stopped seeding after ${"%.2f".format(hours)} hours.")
            CatalogPublisherCli.EXIT_OK
        } finally {
            punchScope.cancel()
            stopSession(session)
        }
    }

    // --- dht-node --------------------------------------------------------------------------------

    fun dhtNode(args: Args): Int = runBlocking {
        requireNative()
        val minutes = args.long("minutes") ?: DEFAULT_NODE_MINUTES
        val session = startSession(args)
        try {
            out.println("DHT node running for $minutes minutes")
            printPorts(session)
            repeat(minutes.toInt()) {
                delay(MS_PER_MINUTE)
                out.println("  dht nodes ${session.dhtNodes()}")
            }
            CatalogPublisherCli.EXIT_OK
        } finally {
            stopSession(session)
        }
    }

    // --- shared ----------------------------------------------------------------------------------

    /**
     * Where [HolePuncher] runs. Not the command's runBlocking scope: runBlocking waits for its children,
     * and the puncher's loops run until they are cancelled.
     */
    private fun punchScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun startSession(args: Args): SessionManager = SwarmSessions.start(
        SwarmSessions.Options(
            listenInterfaces = args.value("listen"),
            dhtBootstrapNodes = args.value("dht-router"),
            dhtNodes = args.values("dht-node").map(::parseEndpoint).map { InetSocketAddress(it.hostString, it.port) },
            privateNetwork = args.flag("private"),
        ),
    )

    /** Both ports, because they can differ: `--peer` wants the TCP one and `--dht-node` the UDP one. */
    private suspend fun printPorts(session: SessionManager) {
        val dhtPort = SwarmSessions.awaitDhtPort(session)
        out.println("  peer port      ${SwarmSessions.listenPort(session)} (TCP, for --peer)")
        out.println("  DHT port       ${dhtPort ?: "not bound"} (UDP, for --dht-node)")
    }

    private suspend fun joinDht(session: SessionManager) {
        out.println("Joining the DHT...")
        if (SwarmSessions.awaitDhtNodes(session, 1, DHT_JOIN_TIMEOUT_MS)) {
            out.println("  ${session.dhtNodes()} DHT nodes")
        } else {
            out.println("warning: no DHT nodes after ${DHT_JOIN_TIMEOUT_MS / MS_PER_SECOND} s; is UDP blocked?")
        }
    }

    /**
     * Stops [session], giving libtorrent a bounded time to shut down.
     *
     * libtorrent 1.2 now and then never returns from its session destructor. A command that has done its
     * work must still exit, so after the wait the stuck shutdown is left to end with the process.
     */
    private fun stopSession(session: SessionManager) {
        val stopped = shutdown.stopWithin(
            SESSION_STOP_WAIT_MS,
            onError = { err.println("warning: stopping the session failed: ${it.message}") },
        ) { session.stop() }
        if (!stopped) {
            err.println("warning: libtorrent did not shut down within ${SESSION_STOP_WAIT_MS / MS_PER_SECOND} s; exiting anyway")
        }
    }

    private fun requireNative() {
        LibtorrentNative.loadError?.let { error ->
            throw IllegalStateException("libtorrent's native library did not load: ${error.message}")
        }
    }

    private companion object {
        const val DEFAULT_HOURS = 24.0
        const val DEFAULT_REPUT_MINUTES = 30L
        const val DEFAULT_FETCH_TIMEOUT_S = 180L
        const val DEFAULT_NODE_MINUTES = 60L
        const val STATUS_INTERVAL_MS = 60_000L
        const val SELF_CHECK_TIMEOUT_MS = 60_000L
        const val DHT_JOIN_TIMEOUT_MS = 60_000L
        const val SESSION_STOP_WAIT_MS = 15_000L
        const val MS_PER_SECOND = 1_000L
        const val MS_PER_MINUTE = 60_000L
        const val MS_PER_HOUR = 3_600_000.0
        const val PERCENT = 100
        const val CHECK_TIMEOUT_MS = 300_000L
        const val CHECK_POLL_MS = 500L
        const val NAME_PREVIEW = 60
        const val BYTES_PER_MB = 1_000_000L
    }
}
