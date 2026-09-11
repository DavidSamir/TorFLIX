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
        out.println(
            "${pinned.entries.size} titles in ${output.path}: ${pinned.newlyPinned} ids pinned now, " +
                "${pinned.entries.size - pinned.newlyPinned} already pinned, ${pinned.unplayable} with no playable magnet",
        )
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

        val pinned = CatalogPinning.pin(input.readBytes())
        out.println("Catalogue: ${pinned.entries.size} titles (${pinned.newlyPinned} ids pinned now, ${pinned.unplayable} unplayable)")

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
        /** Entries with no magnet the app would accept. Allowed, but worth knowing. */
        val unplayable: Int,
    )

    fun pin(input: ByteArray): Pinned {
        val entries = CatalogueJson.content.decodeFromString(ListSerializer(CatalogEntryDto.serializer()), input.decodeToString())
        val problems = CatalogContentRules.problems(entries, countDeclaredTitles(input), requireExplicitIds = false)
        require(problems.isEmpty()) {
            "Fix these before the catalogue can be published:\n  " + problems.joinToString("\n  ")
        }
        val pinned = CatalogIds.pin(entries)
        val json = CatalogueJson.catalogWriter
            .encodeToString(ListSerializer(CatalogEntryDto.serializer()), pinned)
            .encodeToByteArray()
        val stillValid = CatalogContentRules.problems(pinned, countDeclaredTitles(json), requireExplicitIds = true)
        check(stillValid.isEmpty()) { "Pinning produced an unpublishable catalogue:\n  " + stillValid.joinToString("\n  ") }
        return Pinned(
            json = json,
            entries = pinned,
            newlyPinned = entries.count { it.id == null },
            unplayable = pinned.count { entry -> entry.magnets.none { MagnetLink.isValid(it.magnet) } },
        )
    }
}

/** The publisher's private seed: 32 bytes as hex, readable by its owner only. */
object SeedFiles {

    fun read(file: File): ByteArray {
        require(file.isFile) { "No seed file at ${file.path}" }
        val seed = Hex.decodeOrNull(file.readText())
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
