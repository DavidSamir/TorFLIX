package com.torfilx.tools.catalog

import java.io.File
import java.io.PrintStream
import java.net.InetSocketAddress
import kotlin.system.exitProcess

fun main(argv: Array<String>) {
    DesktopNatives.prepare()
    exitProcess(CatalogPublisherCli(System.out, System.err).run(argv.toList()))
}

/** A mistake in how the tool was invoked. Reported with the usage text. */
class UsageException(message: String) : IllegalArgumentException(message)

/**
 * The command line of the catalogue publisher.
 *
 * Offline commands (keygen, pubkey, pin, build, verify) touch no network. The network commands
 * (publish, fetch, dht-node) start a libtorrent session: on the public DHT by default, or on a private
 * network with `--private`, which is how a release can be rehearsed end to end without publishing it.
 */
class CatalogPublisherCli(
    private val out: PrintStream,
    private val err: PrintStream,
    private val clock: () -> Long = System::currentTimeMillis,
    private val workingDir: File = File("").absoluteFile,
) {

    fun run(argv: List<String>): Int {
        val args = try {
            Args.parse(argv)
        } catch (error: UsageException) {
            err.println("error: ${error.message}")
            err.println(USAGE)
            return EXIT_USAGE
        }
        return try {
            val offline = OfflineCommands(out, clock, ::resolve)
            val network = NetworkCommands(out, err, clock, ::resolve)
            when (args.command) {
                "keygen" -> offline.keygen(args, workingDir)
                "pubkey" -> offline.pubkey(args)
                "pin" -> offline.pin(args)
                "build" -> offline.build(args)
                "verify" -> offline.verify(args)
                "publish" -> network.publish(args)
                "fetch" -> network.fetch(args)
                "dht-node" -> network.dhtNode(args)
                null, "help" -> {
                    out.println(USAGE)
                    EXIT_OK
                }
                else -> {
                    err.println("error: unknown command \"${args.command}\"")
                    err.println(USAGE)
                    EXIT_USAGE
                }
            }
        } catch (error: UsageException) {
            err.println("error: ${error.message}")
            EXIT_USAGE
        } catch (error: IllegalArgumentException) {
            err.println("error: ${error.message}")
            EXIT_FAILED
        } catch (error: IllegalStateException) {
            err.println("error: ${error.message}")
            EXIT_FAILED
        }
    }

    /** Resolves a path argument against the directory the tool was started from. */
    private fun resolve(path: String): File = File(path).let { if (it.isAbsolute) it else File(workingDir, path) }

    companion object {
        const val EXIT_OK = 0
        const val EXIT_FAILED = 1
        const val EXIT_USAGE = 2

        val USAGE = """
            |TORFILX catalogue publisher
            |
            |Offline:
            |  keygen   --out <seed file> [--force] [--allow-inside-repo]
            |           Creates the publisher's private seed and prints its public key. Keep the seed
            |           outside the repository and back it up: it signs every catalogue release.
            |  pubkey   --seed <seed file>
            |  pin      --catalog <catalog.json> [--out <file>]
            |           Gives every title a permanent id (the id the app already derives), in place.
            |  build    --catalog <catalog.json> --version <n> --seed <seed file> --out <dir>
            |           [--asset-dir <dir>] [--min-version-code <n>] [--published-at <epoch ms>]
            |           [--tracker <url>]... [--no-default-trackers]
            |           Pins ids, writes and signs release <n>, builds its torrent, and with --asset-dir
            |           also writes the bundled catalog.json and catalog-manifest.json for the APK.
            |  verify   --dir <release directory> (--keys <hex,...> | --seed <seed file>) [--app-version-code <n>]
            |
            |Network (public DHT unless --private):
            |  publish  --release <release directory> --seed <seed file> [--torrent <file>]
            |           [--hours <h>] [--reput-minutes <m>] [--salt <text>]
            |           Seeds the release and keeps its signed pointer in the DHT for as long as it runs.
            |  fetch    --keys <hex,...> --out <dir> [--installed <n>] [--timeout-s <s>] [--salt <text>]
            |           [--peer <host:port>]... [--app-version-code <n>]
            |           Finds, downloads and verifies the published release exactly as the app does.
            |  dht-node [--minutes <m>]
            |           Runs a plain DHT node, to anchor a private rehearsal network.
            |
            |Session options for publish, fetch and dht-node:
            |  --listen <interfaces>      libtorrent listen_interfaces, e.g. 0.0.0.0:6881
            |  --dht-node <host:port>     a DHT node to contact directly (repeatable)
            |  --dht-router <host:port,...> bootstrap routers instead of the public ones
            |  --private                  a closed network: no UPnP, NAT-PMP, local discovery or public routers
            |
            |Run through Gradle:  ./gradlew :tools:catalog-publisher:run --args="<command> <options>"
        """.trimMargin()
    }
}

/** Parsed `<command> --option value --flag` arguments. Repeated options keep every value. */
class Args private constructor(
    val command: String?,
    private val options: Map<String, List<String>>,
    private val flags: Set<String>,
) {
    fun value(name: String): String? = options[name]?.lastOrNull()

    fun required(name: String): String = value(name) ?: throw UsageException("--$name is required")

    fun values(name: String): List<String> = options[name].orEmpty()

    fun flag(name: String): Boolean = name in flags

    fun long(name: String): Long? = value(name)?.let {
        it.toLongOrNull() ?: throw UsageException("--$name must be a whole number, got \"$it\"")
    }

    fun int(name: String): Int? = value(name)?.let {
        it.toIntOrNull() ?: throw UsageException("--$name must be a whole number, got \"$it\"")
    }

    fun double(name: String): Double? = value(name)?.let {
        it.toDoubleOrNull() ?: throw UsageException("--$name must be a number, got \"$it\"")
    }

    companion object {
        /** Options that take no value. */
        private val FLAGS = setOf("force", "private", "no-default-trackers", "allow-inside-repo", "help")

        fun parse(argv: List<String>): Args {
            val command = argv.firstOrNull()?.takeIf { !it.startsWith("--") }
            val rest = if (command != null) argv.drop(1) else argv
            val options = LinkedHashMap<String, MutableList<String>>()
            val flags = HashSet<String>()
            var index = 0
            while (index < rest.size) {
                val token = rest[index]
                if (!token.startsWith("--") || token.length <= 2) throw UsageException("unexpected argument \"$token\"")
                val body = token.removePrefix("--")
                val equals = body.indexOf('=')
                when {
                    equals > 0 -> options.getOrPut(body.substring(0, equals)) { mutableListOf() } += body.substring(equals + 1)
                    body in FLAGS -> flags += body
                    index + 1 < rest.size && !rest[index + 1].startsWith("--") -> {
                        options.getOrPut(body) { mutableListOf() } += rest[index + 1]
                        index++
                    }
                    else -> throw UsageException("--$body needs a value")
                }
                index++
            }
            return Args(if (flags.contains("help")) "help" else command, options, flags)
        }
    }
}

/** Parses `host:port`, or `[ipv6]:port`. */
fun parseEndpoint(text: String): InetSocketAddress {
    val trimmed = text.trim()
    val separator = trimmed.lastIndexOf(':')
    if (separator <= 0 || separator == trimmed.length - 1) throw UsageException("\"$text\" is not host:port")
    val host = trimmed.substring(0, separator).removePrefix("[").removeSuffix("]")
    val port = trimmed.substring(separator + 1).toIntOrNull()?.takeIf { it in 1..MAX_PORT }
        ?: throw UsageException("\"$text\" does not end in a port number")
    return InetSocketAddress.createUnresolved(host, port)
}

private const val MAX_PORT = 65_535
