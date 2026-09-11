package com.torfilx.core.catalogue.swarm

import kotlinx.coroutines.delay
import org.junit.Assume.assumeTrue
import org.libtorrent4j.SessionManager
import java.io.File
import java.io.FileWriter
import java.net.InetSocketAddress
import java.time.LocalTime

/** Makes a missing native library a loud failure where one exists, and a skip where none is published. */
internal object NativeSupport {

    fun require() {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        // libtorrent4j 1.2.3.0 publishes 64-bit x86 natives for Windows, Linux and Intel macOS. On the
        // first two (developer machines and CI) a load failure means these tests would silently prove
        // nothing, so it fails the build instead of skipping.
        val published = ("win" in os || "linux" in os) && (arch == "amd64" || arch == "x86_64")
        val error = LibtorrentNative.loadError
        if (published) {
            if (error != null) {
                throw AssertionError(
                    "libtorrent's native library did not load on $os/$arch from " +
                        System.getProperty("libtorrent4j.jni.path"),
                    error,
                )
            }
        } else {
            assumeTrue("libtorrent publishes no native library for $os/$arch", error == null)
        }
    }
}

/**
 * libtorrent sessions on the loopback interface, for tests that run them one at a time.
 *
 * Nothing here reaches the internet: no public bootstrap routers, no trackers, no UPnP or local
 * discovery. Every session is stopped on close, with a bounded wait.
 *
 * Tests that need several sessions working at once do not use this. They start each session in a process
 * of its own (see process.SwarmNodeMain), because several libtorrent 1.2 sessions busy on the DHT inside
 * one JVM corrupt each other's native memory now and then, on Windows and on Linux alike.
 */
internal class LocalSwarm : AutoCloseable {

    private val sessions = mutableListOf<SessionManager>()

    /** Starts a node that joins the DHT through [bootstrap], or a first node when it is null. */
    suspend fun node(bootstrap: SessionManager? = null): SessionManager {
        val knownNodes = bootstrap?.let { anchor ->
            val dhtPort = SwarmSessions.awaitDhtPort(anchor) ?: throw AssertionError("the anchor node never bound its DHT port")
            listOf(InetSocketAddress("127.0.0.1", dhtPort))
        }.orEmpty()
        val session = SwarmSessions.start(
            SwarmSessions.Options(
                listenInterfaces = "127.0.0.1:0",
                dhtNodes = knownNodes,
                privateNetwork = true,
            ),
        )
        sessions += session
        trace("started session ${sessions.size}")
        return session
    }

    /** The session's listen port, waiting briefly for the socket to be bound. */
    suspend fun port(session: SessionManager): Int {
        repeat(PORT_ATTEMPTS) {
            val port = runCatching { SwarmSessions.listenPort(session) }.getOrDefault(0)
            if (port > 0) return port
            delay(PORT_POLL_MS)
        }
        throw AssertionError("the session never bound a listen port")
    }

    fun stop(session: SessionManager) {
        sessions.remove(session)
        stopBounded(session)
    }

    override fun close() {
        val all = sessions.asReversed().toList()
        sessions.clear()
        all.forEach(::stopBounded)
    }

    /** Stops [session] with a bounded wait: libtorrent's session destructor now and then never returns. */
    private fun stopBounded(session: SessionManager) {
        if (!SessionShutdown().stopWithin(SESSION_STOP_WAIT_MS) { session.stop() }) {
            trace("a session did not finish stopping within ${SESSION_STOP_WAIT_MS / MS_PER_S} s; it ends with the test JVM")
        }
    }

    private companion object {
        const val PORT_ATTEMPTS = 50
        const val PORT_POLL_MS = 100L
        const val SESSION_STOP_WAIT_MS = 20_000L
        const val MS_PER_S = 1_000L
    }
}

/** A log that prints with a label, so a failing integration test shows which side said what. */
internal fun testLog(label: String): SwarmLog = SwarmLog { level, message, error ->
    val line = "[$label] ${level.name} $message"
    println(line)
    error?.printStackTrace(System.out)
    trace(if (error == null) line else "$line\n${error.stackTraceToString()}")
}

/**
 * Appends [line] to `build/swarm-test-trace.log`, closing the file straight away.
 *
 * libtorrent is native code. When it takes the test JVM down, console output Gradle has not collected
 * yet is lost, but a line already handed to the operating system is not, so this file shows how far a
 * crashed run got.
 */
internal fun trace(line: String) {
    runCatching {
        synchronized(TRACE_FILE) {
            TRACE_FILE.parentFile?.mkdirs()
            FileWriter(TRACE_FILE, true).use { it.write("${LocalTime.now()} [${Thread.currentThread().name}] $line\n") }
        }
    }
}

private val TRACE_FILE = File("build/swarm-test-trace.log")
