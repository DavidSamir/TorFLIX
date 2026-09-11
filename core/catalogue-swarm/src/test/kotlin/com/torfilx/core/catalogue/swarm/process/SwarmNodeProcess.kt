package com.torfilx.core.catalogue.swarm.process

import com.torfilx.core.catalogue.Hex
import java.io.File
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The test's side of a [SwarmNodeMain] process: starts it, sends it commands and reads the answers.
 *
 * A node that dies, answers ERR, or stops answering fails the test with the end of its log instead of
 * hanging it.
 */
internal class SwarmNodeProcess private constructor(
    val label: String,
    private val process: Process,
    private val logFile: File,
) : AutoCloseable {

    private val answers = LinkedBlockingQueue<String>()
    private val commands = process.outputStream.bufferedWriter()

    /** The port other nodes connect to as a peer (TCP). */
    var peerPort: Int = 0
        private set

    /** The port other nodes join the DHT through (UDP). */
    var dhtPort: Int = 0
        private set

    val peerAddress: String get() = "$LOOPBACK:$peerPort"

    init {
        thread(name = "swarm-node-$label", isDaemon = true) {
            runCatching { process.inputStream.bufferedReader().forEachLine { answers.put(it) } }
            answers.put(END_OF_OUTPUT)
        }
    }

    /** Starts the node's session, joining the DHT through [anchor]; a node without one is an anchor. */
    fun startSession(anchor: SwarmNodeProcess?) {
        val words = send(if (anchor == null) "START" else "START JOIN $LOOPBACK ${anchor.dhtPort}")
        peerPort = words[0].toInt()
        dhtPort = words[1].toInt()
    }

    fun awaitDhtNodes(timeoutMs: Long = JOIN_TIMEOUT_MS) {
        send("AWAIT_NODES $timeoutMs", timeoutMs + MARGIN_MS)
    }

    data class Seeded(val infoHash: String, val torrentFile: File)

    /** Builds the torrent of the release in [releaseRoot] and seeds it from where it is. */
    fun seed(releaseRoot: File): Seeded {
        val words = send("SEED ${Wire.encode(releaseRoot.path)}")
        return Seeded(words[0], File(Wire.decode(words[1])))
    }

    /** Seeds a torrent whose files are already in [saveDir]; answers its info hash. */
    fun seedTorrent(torrentFile: File, saveDir: File): String =
        send("SEED_TORRENT ${Wire.encode(torrentFile.path)} ${Wire.encode(saveDir.path)}")[0]

    data class Put(val seq: Long, val nodesStored: Int)

    fun put(seed: ByteArray, infoHash: String, version: Long, salt: ByteArray): Put {
        val words = send("PUT ${Hex.encode(seed)} $infoHash $version ${Hex.encode(salt)}", PUT_TIMEOUT_MS)
        return Put(words[0].toLong(), words[1].toInt())
    }

    /** What a catalogue check ended with, as the node's CatalogueFetcher reported it. */
    sealed interface Fetch {
        data class Fetched(
            val version: Long,
            val infoHash: String,
            val titles: Int,
            val rootDir: File,
            val torrentFile: File,
        ) : Fetch

        data class UpToDate(val remoteVersion: Long, val installedVersion: Long) : Fetch
        data class Skipped(val remoteVersion: Long) : Fetch
        data object NotFound : Fetch
        data class Unavailable(val reason: String) : Fetch
        data class Rejected(val version: Long, val reason: String, val detail: String) : Fetch
        data class Failed(val message: String) : Fetch
    }

    /** One catalogue check, the way the app's updater runs it. Releases land in saveBase/releases/version. */
    @Suppress("LongParameterList")
    fun fetch(
        installed: Long,
        keys: List<ByteArray>,
        salt: ByteArray,
        saveBase: File,
        peers: List<String> = emptyList(),
        rejected: Long? = null,
        lookupTimeoutMs: Long = LOOKUP_TIMEOUT_MS,
        downloadTimeoutMs: Long = DOWNLOAD_TIMEOUT_MS,
    ): Fetch {
        val command = listOf(
            "FETCH",
            installed.toString(),
            rejected?.toString() ?: NONE,
            keys.joinToString(",") { Hex.encode(it) },
            Hex.encode(salt),
            Wire.encode(saveBase.path),
            lookupTimeoutMs.toString(),
            downloadTimeoutMs.toString(),
            peers.ifEmpty { listOf(NONE) }.joinToString(","),
        ).joinToString(" ")
        val words = send(command, lookupTimeoutMs + downloadTimeoutMs + MARGIN_MS)
        return when (words[0]) {
            "fetched" -> Fetch.Fetched(
                version = words[1].toLong(),
                infoHash = words[2],
                titles = words[3].toInt(),
                rootDir = File(Wire.decode(words[4])),
                torrentFile = File(Wire.decode(words[5])),
            )
            "uptodate" -> Fetch.UpToDate(words[1].toLong(), words[2].toLong())
            "skipped" -> Fetch.Skipped(words[1].toLong())
            "notfound" -> Fetch.NotFound
            "unavailable" -> Fetch.Unavailable(Wire.decode(words[1]))
            "rejected" -> Fetch.Rejected(words[1].toLong(), words[2], Wire.decode(words[3]))
            "failed" -> Fetch.Failed(Wire.decode(words[1]))
            else -> fail("FETCH answered something unexpected: ${words.joinToString(" ")}")
        }
    }

    /** Downloads a release torrent into [saveDir]; answers the release root. */
    fun download(
        infoHash: String,
        saveDir: File,
        timeoutMs: Long = DOWNLOAD_TIMEOUT_MS,
        peers: List<String> = emptyList(),
    ): File {
        val peerWord = peers.ifEmpty { listOf(NONE) }.joinToString(",")
        val words = send("DOWNLOAD $infoHash ${Wire.encode(saveDir.path)} $timeoutMs $peerWord", timeoutMs + MARGIN_MS)
        return File(Wire.decode(words[0]))
    }

    fun stopSeeding(infoHash: String) {
        send("STOP_SEEDING $infoHash")
    }

    /** null when the torrent is not in the session; otherwise whether it is seeding. */
    fun seeding(infoHash: String): Boolean? {
        val words = send("HAS $infoHash")
        return if (words[0] == "absent") null else words[1].toBoolean()
    }

    private fun send(command: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): List<String> {
        val name = command.substringBefore(' ')
        if (!process.isAlive) fail("the process had already ended (exit code ${process.exitValue()}) before $name")
        try {
            commands.write(command)
            commands.newLine()
            commands.flush()
        } catch (error: IOException) {
            fail("could not send $name: ${error.message}")
        }
        val answer = answers.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: fail("no answer to $name within ${timeoutMs / MS_PER_S} s")
        if (answer == END_OF_OUTPUT) fail("the process ended during $name${exitDescription()}")
        val words = answer.split(' ')
        if (words[0] != "OK") fail("$name answered: ${answer.take(ANSWER_PREVIEW)}")
        return words.drop(1)
    }

    private fun exitDescription(): String =
        if (process.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)) " (exit code ${process.exitValue()})" else ""

    private fun fail(message: String): Nothing =
        throw AssertionError("[$label] $message\n--- end of ${logFile.path} ---\n${logTail()}")

    private fun logTail(): String =
        runCatching { logFile.readLines().takeLast(TAIL_LINES).joinToString("\n") }.getOrDefault("(no log)")

    override fun close() {
        if (!process.isAlive) return
        runCatching {
            commands.write("QUIT")
            commands.newLine()
            commands.flush()
        }
        if (!process.waitFor(QUIT_WAIT_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val END_OF_OUTPUT = "<end of output>"
        private const val NONE = "-"
        private const val NATIVE_PATH_PROPERTY = "libtorrent4j.jni.path"
        private const val COMMAND_TIMEOUT_MS = 60_000L
        private const val JOIN_TIMEOUT_MS = 30_000L
        private const val PUT_TIMEOUT_MS = 90_000L
        private const val LOOKUP_TIMEOUT_MS = 45_000L
        private const val DOWNLOAD_TIMEOUT_MS = 90_000L
        private const val MARGIN_MS = 30_000L
        private const val QUIT_WAIT_MS = 30_000L
        private const val EXIT_WAIT_MS = 2_000L
        private const val TAIL_LINES = 40
        private const val ANSWER_PREVIEW = 400
        private const val MS_PER_S = 1_000L

        /** Starts a node with this JVM's classpath and native library; its log goes to logDir/label.log. */
        fun start(label: String, logDir: File): SwarmNodeProcess {
            logDir.mkdirs()
            val logFile = File(logDir, "$label.log").apply { delete() }
            val classpath = System.getProperty("java.class.path").orEmpty()
            check("libtorrent4j" in classpath) { "the test JVM's classpath does not name libtorrent4j: $classpath" }
            val command = buildList {
                add(File(System.getProperty("java.home"), "bin/java").path)
                System.getProperty(NATIVE_PATH_PROPERTY)?.let { add("-D$NATIVE_PATH_PROPERTY=$it") }
                add("-cp")
                add(classpath)
                add(SwarmNodeMain::class.java.name)
            }
            val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.appendTo(logFile)).start()
            return SwarmNodeProcess(label, process, logFile)
        }
    }
}

/** The nodes of one test. Closing it asks them all to quit at once. */
internal class SwarmNetwork(private val logDir: File) : AutoCloseable {

    private val nodes = mutableListOf<SwarmNodeProcess>()

    /** Starts a node; it joins the DHT through [anchor], or is the anchor when there is none. */
    fun node(label: String, anchor: SwarmNodeProcess? = null): SwarmNodeProcess {
        val node = SwarmNodeProcess.start(label, logDir)
        nodes += node
        node.startSession(anchor)
        return node
    }

    override fun close() {
        nodes.map { node -> thread(name = "close-${node.label}") { node.close() } }.forEach { it.join() }
        nodes.clear()
    }
}
