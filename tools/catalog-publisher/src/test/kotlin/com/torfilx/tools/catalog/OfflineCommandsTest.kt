package com.torfilx.tools.catalog

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.CatalogueJson
import com.torfilx.core.catalogue.Hex
import com.torfilx.core.catalogue.crypto.Ed25519Keys
import com.torfilx.core.catalogue.format.BundledCatalogManifest
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogIds
import com.torfilx.core.catalogue.format.CatalogMagnetDto
import com.torfilx.core.catalogue.format.CatalogRelease
import com.torfilx.core.catalogue.crypto.Sha256
import com.torfilx.core.catalogue.swarm.CatalogueTorrents
import com.torfilx.core.catalogue.swarm.LibtorrentNative
import com.torfilx.core.catalogue.testing.CatalogueTestKeys
import com.torfilx.core.catalogue.testing.TestCatalogues
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/** The offline commands end to end, through the same entry point the maintainer uses. */
class OfflineCommandsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val output = ByteArrayOutputStream()
    private val errors = ByteArrayOutputStream()
    private lateinit var workingDir: File

    @Before
    fun setUp() {
        workingDir = tmp.newFolder("work")
    }

    private fun cli() = CatalogPublisherCli(
        out = PrintStream(output, true),
        err = PrintStream(errors, true),
        clock = { TestCatalogues.FIXED_PUBLISHED_AT_MS },
        workingDir = workingDir,
    )

    private fun run(vararg argv: String): Int = cli().run(argv.toList())

    private fun seedFile(seed: ByteArray = CatalogueTestKeys.SEED): File =
        File(tmp.root, "keys/test.seed").also { SeedFiles.write(it, seed) }

    /** A hand-maintained catalogue: no ids, one duplicate title and year, one broken magnet. */
    private fun handCatalogue(): File {
        val entries = listOf(
            CatalogEntryDto(title = "The Kid", year = "1921", magnets = listOf(CatalogMagnetDto("720p", TestCatalogues.magnet(1)))),
            CatalogEntryDto(title = "The Kid", year = "1921", magnets = listOf(CatalogMagnetDto("1080p", TestCatalogues.magnet(2)))),
            CatalogEntryDto(title = "Nosferatu", year = "1922", magnets = listOf(CatalogMagnetDto("720p", "magnet:?xt=urn:btih:broken"))),
        )
        return File(workingDir, "catalog.json").also {
            it.writeText(CatalogueJson.catalogWriter.encodeToString(ListSerializer(CatalogEntryDto.serializer()), entries))
        }
    }

    private fun requireNative() {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        val published = ("win" in os || "linux" in os) && (arch == "amd64" || arch == "x86_64")
        if (published) {
            LibtorrentNative.loadError?.let { throw AssertionError("libtorrent native did not load", it) }
        } else {
            assumeTrue(LibtorrentNative.isAvailable)
        }
    }

    @Test
    fun `keygen writes a seed and prints the key that verifies its signatures`() {
        requireNative()
        val target = File(tmp.root, "outside/publisher.seed")

        assertThat(run("keygen", "--out", target.path)).isEqualTo(0)

        val seed = SeedFiles.read(target)
        val printedKey = output.toString().lines().map { it.trim() }.first { it.matches(Regex("[0-9a-f]{64}")) }
        assertThat(printedKey).isEqualTo(Hex.encode(Ed25519Keys.publicKeyOf(seed)))
    }

    @Test
    fun `keygen never replaces a key by accident`() {
        requireNative()
        val target = File(tmp.root, "outside/publisher.seed")
        run("keygen", "--out", target.path)
        val first = target.readText()

        assertThat(run("keygen", "--out", target.path)).isEqualTo(1)
        assertThat(target.readText()).isEqualTo(first)
        assertThat(errors.toString()).contains("already exists")
    }

    @Test
    fun `keygen refuses to put the seed inside a repository`() {
        File(workingDir, ".git").mkdirs()
        val insideRepo = File(workingDir, "keys/publisher.seed")

        assertThat(run("keygen", "--out", insideRepo.path)).isEqualTo(1)

        assertThat(insideRepo.exists()).isFalse()
        assertThat(errors.toString()).contains("inside the repository")
    }

    @Test
    fun `pin gives every title the id the app already derives`() {
        val catalogue = handCatalogue()
        val expected = CatalogIds.pin(
            CatalogueJson.content.decodeFromString(ListSerializer(CatalogEntryDto.serializer()), catalogue.readText()),
        ).map { it.id }

        assertThat(run("pin", "--catalog", catalogue.path)).isEqualTo(0)

        val pinned = CatalogueJson.content.decodeFromString(ListSerializer(CatalogEntryDto.serializer()), catalogue.readText())
        assertThat(pinned.map { it.id }).isEqualTo(expected)
        assertThat(pinned.map { it.id }).containsExactly("catalog-the-kid-1921", "catalog-the-kid-1921-00000000", "catalog-nosferatu-1922").inOrder()
        assertThat(output.toString()).contains("3 ids pinned now")
        assertThat(output.toString()).contains("1 with no playable magnet")

        // Pinning again changes nothing.
        val once = catalogue.readBytes()
        assertThat(run("pin", "--catalog", catalogue.path)).isEqualTo(0)
        assertThat(catalogue.readBytes()).isEqualTo(once)
    }

    @Test
    fun `pin refuses a catalogue with a blank title`() {
        val broken = File(workingDir, "broken.json").apply { writeText("""[{"title":"A"},{"title":"  "}]""") }
        assertThat(run("pin", "--catalog", broken.path)).isEqualTo(1)
        assertThat(errors.toString()).contains("entry 1 has no title")
    }

    @Test
    fun `build writes a verifiable release, its torrent and the bundled asset pair`() {
        requireNative()
        val catalogue = handCatalogue()
        val out = File(workingDir, "dist")
        val assets = File(workingDir, "assets")

        val exit = run(
            "build", "--catalog", catalogue.path, "--version", "3", "--seed", seedFile().path,
            "--out", out.path, "--asset-dir", assets.path, "--tracker", "udp://tracker.example.invalid:1/announce",
        )

        assertThat(exit).isEqualTo(0)
        val root = File(out, CatalogRelease.rootDirName(3))
        assertThat(run("verify", "--dir", root.path, "--keys", CatalogueTestKeys.PUBLIC_KEY_HEX)).isEqualTo(0)
        assertThat(run("verify", "--dir", root.path, "--keys", Hex.encode(CatalogueTestKeys.OTHER_PUBLIC_KEY))).isEqualTo(1)

        val torrent = File(out, "${root.name}.torrent").readBytes()
        assertThat(CatalogueTorrents.layoutProblem(TorrentInfo(torrent))).isNull()
        assertThat(output.toString()).contains(CatalogueTorrents.infoHash(torrent))
        assertThat(TorrentInfo(torrent).trackers().map { it.url() })
            .containsAtLeast(OfflineCommands.DEFAULT_TRACKERS.first(), "udp://tracker.example.invalid:1/announce")

        val bundledJson = File(assets, CatalogRelease.BUNDLED_CATALOG_ASSET).readBytes()
        val manifest = CatalogueJson.manifest.decodeFromString(
            BundledCatalogManifest.serializer(),
            File(assets, CatalogRelease.BUNDLED_MANIFEST_ASSET).readText(),
        )
        assertThat(manifest.catalogVersion).isEqualTo(3)
        assertThat(manifest.titleCount).isEqualTo(3)
        assertThat(manifest.sha256).isEqualTo(Sha256.hex(bundledJson))
        assertThat(manifest.publishedAtMs).isEqualTo(TestCatalogues.FIXED_PUBLISHED_AT_MS)
    }

    @Test
    fun `a renamed title keeps its pinned id in the next build`() {
        requireNative()
        val catalogue = handCatalogue()
        run("pin", "--catalog", catalogue.path)
        val renamed = catalogue.readText().replace("\"Nosferatu\"", "\"Nosferatu (restored)\"")
        catalogue.writeText(renamed)

        assertThat(run("build", "--catalog", catalogue.path, "--version", "4", "--seed", seedFile().path, "--out", File(workingDir, "dist").path)).isEqualTo(0)

        val built = CatalogueJson.content.decodeFromString(ListSerializer(CatalogEntryDto.serializer()), File(workingDir, "dist/torfilx-catalogue-4").let { root ->
            java.util.zip.GZIPInputStream(File(root, CatalogRelease.CATALOG_GZ).inputStream()).use { it.readBytes() }.decodeToString()
        })
        assertThat(built.first { it.title.startsWith("Nosferatu") }.id).isEqualTo("catalog-nosferatu-1922")
    }

    @Test
    fun `a release version is never built twice`() {
        requireNative()
        val catalogue = handCatalogue()
        val out = File(workingDir, "dist")
        assertThat(run("build", "--catalog", catalogue.path, "--version", "5", "--seed", seedFile().path, "--out", out.path)).isEqualTo(0)
        assertThat(run("build", "--catalog", catalogue.path, "--version", "5", "--seed", seedFile().path, "--out", out.path)).isEqualTo(1)
        assertThat(errors.toString()).contains("never changes")
    }

    @Test
    fun `usage mistakes exit with the usage code`() {
        assertThat(run("build", "--catalog")).isEqualTo(2)
        assertThat(run("frobnicate")).isEqualTo(2)
        assertThat(run("verify", "--dir", "x")).isEqualTo(2)
        assertThat(run("help")).isEqualTo(0)
        assertThat(output.toString()).contains("TORFILX catalogue publisher")
    }
}
