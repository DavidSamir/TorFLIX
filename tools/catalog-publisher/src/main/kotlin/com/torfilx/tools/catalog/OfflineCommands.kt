package com.torfilx.tools.catalog

import com.torfilx.core.catalogue.CatalogueJson
import com.torfilx.core.catalogue.Hex
import com.torfilx.core.catalogue.crypto.Ed25519Keys
import com.torfilx.core.catalogue.crypto.Ed25519Verifier
import com.torfilx.core.catalogue.format.CatalogContentRules
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogIds
import com.torfilx.core.catalogue.format.CatalogRelease
import com.torfilx.core.catalogue.format.countDeclaredTitles
import com.torfilx.core.catalogue.release.CatalogueReleaseVerifier
import com.torfilx.core.catalogue.release.CatalogueReleaseWriter
import com.torfilx.core.catalogue.swarm.CatalogueTorrents
import com.torfilx.core.catalogue.swarm.LibtorrentNative
import com.torfilx.core.model.MagnetLink
import com.torfilx.tools.catalog.merge.TextDecoding
import kotlinx.serialization.builtins.ListSerializer
import org.libtorrent4j.Ed25519
import java.io.File
import java.io.PrintStream
import java.security.SecureRandom

/** The commands that never touch a network: keys, pinning, building and verifying releases. */
class OfflineCommands(
    private val out: PrintStream,
    private val clock: () -> Long,
    private val resolve: (String) -> File,
) {

    // --- keygen / pubkey -------------------------------------------------------------------------

    fun keygen(args: Args, workingDir: File): Int {
        val target = resolve(args.required("out"))
        if (target.exists() && !args.flag("force")) {
            throw IllegalStateException("${target.path} already exists. Refusing to replace a publisher key (use --force).")
        }
        if (!args.flag("allow-inside-repo") && isInsideRepository(target, workingDir)) {
            throw IllegalStateException(
                "${target.path} is inside the repository. The seed signs every release and must never be " +
                    "committed; put it somewhere else (or pass --allow-inside-repo for a throwaway test key).",
            )
        }
        val seed = ByteArray(Ed25519Keys.SEED_BYTES).also { SecureRandom().nextBytes(it) }
        SeedFiles.write(target, seed)
        val publicKey = Ed25519Keys.publicKeyOf(seed)
        crossCheckWithLibtorrent(seed, publicKey)
        out.println("Publisher seed written to ${target.absolutePath}")
        out.println("Back it up. Anyone holding it can publish a catalogue every TORFILX build trusts.")
        out.println()
        out.println("Public key (put this in productionCataloguePublisherKeys in core/data/build.gradle.kts):")
        out.println(Hex.encode(publicKey))
        return CatalogPublisherCli.EXIT_OK
    }

    fun pubkey(args: Args): Int {
        val seed = SeedFiles.read(resolve(args.required("seed")))
        val publicKey = Ed25519Keys.publicKeyOf(seed)
        crossCheckWithLibtorrent(seed, publicKey)
        out.println(Hex.encode(publicKey))
        return CatalogPublisherCli.EXIT_OK
    }

    /** The DHT pointer is signed by libtorrent and the manifest by pure Java: they must agree on the key. */
    private fun crossCheckWithLibtorrent(seed: ByteArray, publicKey: ByteArray) {
        if (!LibtorrentNative.isAvailable) return
        val native = Ed25519.createKeypair(seed).first
        check(native.contentEquals(publicKey)) {
            "libtorrent derives a different public key from this seed; releases signed with it would not verify"
        }
    }

    private fun isInsideRepository(target: File, workingDir: File): Boolean {
        var directory: File? = workingDir.canonicalFile
        while (directory != null) {
            if (File(directory, ".git").exists()) {
                return target.canonicalFile.startsWith(directory)
            }
            directory = directory.parentFile
        }
        return false
    }

    // --- pin -------------------------------------------------------------------------------------

    fun pin(args: Args): Int {
        val input = resolve(args.required("catalog"))
        val output = args.value("out")?.let(resolve) ?: input
        val pinned = CatalogPinning.pin(input.readBytes())
        output.absoluteFile.parentFile?.mkdirs()
        output.writeBytes(pinned.json)
        out.println("${output.path}: ${CatalogPinning.describe(pinned)}")
        return CatalogPublisherCli.EXIT_OK
    }

    // --- build -----------------------------------------------------------------------------------

    fun build(args: Args): Int {
        val input = resolve(args.required("catalog"))
        val version = args.long("version") ?: throw UsageException("--version is required")
        if (version <= 0) throw UsageException("--version must be 1 or more")
        val seed = SeedFiles.read(resolve(args.required("seed")))
        val outDir = resolve(args.required("out"))
        val assetDir = args.value("asset-dir")?.let(resolve)
        val minVersionCode = args.int("min-version-code")
        val publishedAt = args.long("published-at") ?: clock()
        val trackers = (if (args.flag("no-default-trackers")) emptyList() else DEFAULT_TRACKERS) + args.values("tracker")

        val pinned = CatalogPinning.pin(input.readBytes(), stripTrackers = args.flag("strip-trackers"))
        out.println("Catalogue: ${CatalogPinning.describe(pinned)}")
        printSize(pinned.json.size.toLong())

        val written = CatalogueReleaseWriter.write(pinned.json, version, publishedAt, seed, outDir, minVersionCode)
        val publicKey = Ed25519Keys.publicKeyOf(seed)
        val verdict = CatalogueReleaseVerifier(Ed25519Verifier(listOf(publicKey))).verify(written.releaseRoot, Int.MAX_VALUE)
        check(verdict is CatalogueReleaseVerifier.Result.Ok) {
            "the release just written does not verify: $verdict"
        }

        val torrent = CatalogueTorrents.build(written.releaseRoot, trackers, comment = "TORFILX catalogue $version")
        val torrentFile = File(outDir, "${written.releaseRoot.name}.torrent")
        torrentFile.writeBytes(torrent)

        if (assetDir != null) {
            check(assetDir.isDirectory || assetDir.mkdirs()) { "Could not create ${assetDir.path}" }
            File(assetDir, CatalogRelease.BUNDLED_CATALOG_ASSET).writeBytes(pinned.json)
            File(assetDir, CatalogRelease.BUNDLED_MANIFEST_ASSET).writeText(
                CatalogueReleaseWriter.encodeBundledManifest(
                    CatalogueReleaseWriter.bundledManifest(pinned.json, version, publishedAt),
                ),
            )
            out.println("Bundled catalogue and manifest written to ${assetDir.path}")
        }

        val manifest = written.manifest
        out.println()
        out.println("Release ${manifest.catalogVersion} written to ${written.releaseRoot.path}")
        out.println("  titles        ${manifest.titleCount}")
        manifest.episodeCount?.let { out.println("  episodes      $it") }
        out.println("  catalog.json  ${manifest.jsonBytes} bytes, ${manifest.gzBytes} bytes compressed")
        out.println("  sha256        ${manifest.sha256}")
        manifest.minVersionCode?.let { out.println("  needs app     build $it or later") }
        out.println("  torrent       ${torrentFile.path}")
        out.println("  info hash     ${CatalogueTorrents.infoHash(torrent)}")
        out.println("  signed by     ${Hex.encode(publicKey)}")
        out.println()
        out.println("Next: publish --release ${written.releaseRoot.path} --seed <seed file>")
        return CatalogPublisherCli.EXIT_OK
    }

    /**
     * How close the catalogue is to the size a television will load, on every build, so the limit is
     * seen long before it is hit. Episodes add up quickly; the cheapest fix is `--strip-trackers`.
     */
    private fun printSize(jsonBytes: Long) {
        val percent = jsonBytes * PERCENT / CatalogRelease.MAX_JSON_BYTES
        out.println("  catalog.json is $jsonBytes bytes, $percent% of the ${CatalogRelease.MAX_JSON_BYTES}-byte limit")
        if (jsonBytes >= CatalogRelease.MAX_JSON_BYTES * SIZE_WARNING_PERCENT / PERCENT) {
            out.println(
                "  warning: over $SIZE_WARNING_PERCENT% of the limit. Most of a catalogue is repeated tracker URLs; " +
                    "--strip-trackers keeps ${MagnetTrackers.DEFAULT_KEEP} per magnet.",
            )
        }
    }

    // --- verify ----------------------------------------------------------------------------------

    fun verify(args: Args): Int {
        val root = resolve(args.required("dir"))
        val keys = when {
            args.value("keys") != null -> parseKeys(args.required("keys"))
            args.value("seed") != null -> listOf(Ed25519Keys.publicKeyOf(SeedFiles.read(resolve(args.required("seed")))))
            else -> throw UsageException("--keys or --seed is required")
        }
        val appVersionCode = args.int("app-version-code") ?: Int.MAX_VALUE
        return when (val verdict = CatalogueReleaseVerifier(Ed25519Verifier(keys)).verify(root, appVersionCode)) {
            is CatalogueReleaseVerifier.Result.Ok -> {
                val manifest = verdict.manifest
                out.println("OK: catalogue ${manifest.catalogVersion}, ${manifest.titleCount} titles, published ${manifest.publishedAtMs}")
                CatalogPublisherCli.EXIT_OK
            }
            is CatalogueReleaseVerifier.Result.Rejected -> {
                out.println("REJECTED (${verdict.reason}): ${verdict.detail}")
                CatalogPublisherCli.EXIT_FAILED
            }
        }
    }

    companion object {
        private const val PERCENT = 100L

        /** Past this share of the size limit, `build` suggests trimming trackers. */
        const val SIZE_WARNING_PERCENT = 67L

        /** The same public trackers the app adds to every torrent. */
        val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.tracker.cl:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "https://tracker.tamersunion.org:443/announce",
        )
    }
}

/** Pins ids into a hand-maintained catalogue, after checking it can be published at all. */
object CatalogPinning {

    class Pinned(
        /** The pinned catalogue, laid out like the hand-maintained file. */
        val json: ByteArray,
        val entries: List<CatalogEntryDto>,
        /** Entries that had no explicit id until now. */
        val newlyPinned: Int,
        /** Titles nothing can be played of: a film with no usable magnet, a show with no usable episode. */
        val unplayable: Int,
        val shows: Int = 0,
        val episodes: Int = 0,
        /** Episodes that had no explicit id until now. */
        val episodesPinned: Int = 0,
        /** Episodes with no magnet the app would accept. Listed greyed in the app, but worth knowing. */
        val unplayableEpisodes: Int = 0,
    ) {
        val films: Int get() = entries.size - shows
    }

    /**
     * @param stripTrackers trim every magnet to [MagnetTrackers.DEFAULT_KEEP] trackers, preferring the
     *   app's own, before anything is written.
     */
    fun pin(input: ByteArray, stripTrackers: Boolean = false): Pinned {
        val decoded = CatalogueJson.content.decodeFromString(ListSerializer(CatalogEntryDto.serializer()), input.decodeToString())
        val problems = CatalogContentRules.problems(decoded, countDeclaredTitles(input), requireExplicitIds = false)
        require(problems.isEmpty()) {
            "Fix these before the catalogue can be published:\n  " + problems.joinToString("\n  ")
        }
        val entries = if (stripTrackers) {
            MagnetTrackers.stripAll(decoded, MagnetTrackers.DEFAULT_KEEP, OfflineCommands.DEFAULT_TRACKERS)
        } else {
            decoded
        }
        val pinned = CatalogIds.pin(entries)
        val json = CatalogueJson.catalogWriter
            .encodeToString(ListSerializer(CatalogEntryDto.serializer()), pinned)
            .encodeToByteArray()
        val stillValid = CatalogContentRules.problems(pinned, countDeclaredTitles(json), requireExplicitIds = true)
        check(stillValid.isEmpty()) { "Pinning produced an unpublishable catalogue:\n  " + stillValid.joinToString("\n  ") }

        val playable = { magnets: List<com.torfilx.core.catalogue.format.CatalogMagnetDto> -> magnets.any { MagnetLink.isValid(it.magnet) } }
        val episodesBefore = entries.filter { it.isShow }.flatMap { show -> show.seasons.flatMap { it.episodes } }
        val episodesAfter = pinned.filter { it.isShow }.flatMap { show -> show.seasons.flatMap { it.episodes } }
        return Pinned(
            json = json,
            entries = pinned,
            newlyPinned = entries.count { it.id == null },
            unplayable = pinned.count { entry ->
                if (entry.isShow) {
                    entry.seasons.none { season -> season.packs.let(playable) || season.episodes.any { playable(it.magnets) } }
                } else {
                    !playable(entry.magnets)
                }
            },
            shows = pinned.count { it.isShow },
            episodes = episodesAfter.size,
            episodesPinned = episodesBefore.count { it.id == null },
            unplayableEpisodes = pinned.filter { it.isShow }.sumOf { show ->
                show.seasons.sumOf { season -> if (playable(season.packs)) 0 else season.episodes.count { !playable(it.magnets) } }
            },
        )
    }

    /** One line saying what the catalogue holds, for `pin` and `build`. */
    fun describe(pinned: Pinned): String = buildString {
        append("${pinned.entries.size} titles")
        if (pinned.shows > 0) append(" (${pinned.films} films, ${pinned.shows} shows with ${pinned.episodes} episodes)")
        append(": ${pinned.newlyPinned} ids pinned now, ${pinned.entries.size - pinned.newlyPinned} already pinned")
        if (pinned.shows > 0) append(", ${pinned.episodesPinned} episode ids pinned now")
        append(", ${pinned.unplayable} with no playable magnet")
        if (pinned.unplayableEpisodes > 0) append(", ${pinned.unplayableEpisodes} episodes with no playable magnet")
    }
}

/** The publisher's private seed: 32 bytes as hex, readable by its owner only. */
object SeedFiles {

    /**
     * Reads the seed in whichever encoding the file was saved: Windows PowerShell 5.1 writes UTF-16 with
     * a byte order mark by default, and Notepad may add a UTF-8 one.
     */
    fun read(file: File): ByteArray {
        require(file.isFile) { "No seed file at ${file.path}" }
        val text = runCatching { TextDecoding.decode(file.readBytes()) }.getOrDefault("")
        val seed = Hex.decodeOrNull(text)
        require(seed != null && seed.size == Ed25519Keys.SEED_BYTES) {
            "${file.path} is not a publisher seed (expected ${Ed25519Keys.SEED_BYTES * 2} hex characters)"
        }
        return seed
    }

    fun write(file: File, seed: ByteArray) {
        val directory = file.absoluteFile.parentFile
        check(directory.isDirectory || directory.mkdirs()) { "Could not create ${directory.path}" }
        file.writeText(Hex.encode(seed) + "\n")
        // Owner only, where the file system has a notion of it.
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        file.setExecutable(false, false)
    }
}

/** Parses comma-separated hex public keys. */
fun parseKeys(text: String): List<ByteArray> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { hex ->
    Hex.decodeOrNull(hex)?.takeIf { it.size == Ed25519Keys.PUBLIC_KEY_BYTES }
        ?: throw UsageException("\"$hex\" is not a ${Ed25519Keys.PUBLIC_KEY_BYTES * 2}-character hex public key")
}.also { if (it.isEmpty()) throw UsageException("no public keys given") }
