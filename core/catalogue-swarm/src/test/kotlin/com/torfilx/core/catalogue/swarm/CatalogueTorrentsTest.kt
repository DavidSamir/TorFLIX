package com.torfilx.core.catalogue.swarm

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.format.CatalogRelease
import com.torfilx.core.catalogue.testing.TestCatalogues
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class CatalogueTorrentsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun nativeLibrary() = NativeSupport.require()

    private val tracker = "udp://tracker.example.invalid:1337/announce"

    @Test
    fun `a release builds into a torrent of exactly its three files`() {
        val root = TestCatalogues.writeRelease(tmp.newFolder(), 9).releaseRoot

        val info = TorrentInfo(CatalogueTorrents.build(root, listOf(tracker), comment = "TORFILX catalogue 9"))

        assertThat(info.name()).isEqualTo(CatalogRelease.rootDirName(9))
        assertThat(info.numFiles()).isEqualTo(3)
        assertThat(info.pieceLength()).isEqualTo(CatalogueTorrents.PIECE_SIZE)
        assertThat(info.totalSize()).isEqualTo(root.listFiles()!!.sumOf { it.length() })
        assertThat(info.trackers().map { it.url() }).containsExactly(tracker)
        assertThat(info.comment()).isEqualTo("TORFILX catalogue 9")
        assertThat(CatalogueTorrents.layoutProblem(info)).isNull()
    }

    @Test
    fun `building the same release twice gives the same info hash`() {
        val root = TestCatalogues.writeRelease(tmp.newFolder(), 9).releaseRoot
        val first = CatalogueTorrents.infoHash(CatalogueTorrents.build(root, listOf(tracker)))
        val second = CatalogueTorrents.infoHash(CatalogueTorrents.build(root, emptyList()))

        assertThat(first).matches("[0-9a-f]{40}")
        // Trackers live outside the info dictionary, so they do not change the identity of a release.
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `the magnet link names the info hash`() {
        val root = TestCatalogues.writeRelease(tmp.newFolder(), 2).releaseRoot
        val torrent = CatalogueTorrents.build(root, emptyList())

        assertThat(CatalogueTorrents.magnet(torrent).lowercase())
            .contains("urn:btih:${CatalogueTorrents.infoHash(torrent)}")
    }

    @Test
    fun `a directory that is not a release is refused`() {
        val notNamedLikeARelease = tmp.newFolder("catalogue")
        assertThrows(IllegalArgumentException::class.java) { CatalogueTorrents.build(notNamedLikeARelease, emptyList()) }

        val root = TestCatalogues.writeRelease(tmp.newFolder(), 4).releaseRoot
        File(root, "extra.txt").writeText("not part of a release")
        assertThrows(IllegalArgumentException::class.java) { CatalogueTorrents.build(root, emptyList()) }
    }

    @Test
    fun `a torrent with anything besides the release files is not a release`() {
        val root = File(tmp.newFolder(), CatalogRelease.rootDirName(5)).apply { mkdirs() }
        File(root, "manifest.json").writeText("{}")
        File(root, "movie.mkv").writeBytes(ByteArray(40_000))
        val torrent = CatalogueTorrents.buildTorrent(root, emptyList(), pieceSize = 0, creator = null, comment = null)

        assertThat(CatalogueTorrents.layoutProblem(TorrentInfo(torrent))).contains("movie.mkv")
    }

    @Test
    fun `a torrent under any other name is not a release`() {
        val root = tmp.newFolder("films")
        CatalogRelease.RELEASE_FILES.forEach { File(root, it).writeText("x") }
        val torrent = CatalogueTorrents.buildTorrent(root, emptyList(), pieceSize = 0, creator = null, comment = null)

        assertThat(CatalogueTorrents.layoutProblem(TorrentInfo(torrent))).contains("\"films\"")
    }

    @Test
    fun `building and reading torrents survives garbage collection at any moment`() {
        val root = TestCatalogues.writeRelease(tmp.newFolder(), 6, TestCatalogues.entries(60)).releaseRoot
        val expected = CatalogueTorrents.infoHash(CatalogueTorrents.build(root, listOf(tracker)))
        // libtorrent4j frees a native object as soon as its Java wrapper is collected, even while another
        // native object still points into it. Collecting constantly lands a collection inside any such
        // window, which a normal run only hits now and then.
        val stop = AtomicBoolean(false)
        val collector = thread(name = "gc-pressure", isDaemon = true) {
            while (!stop.get()) {
                System.gc()
                // A short breath between collections, or the build thread barely gets to run.
                Thread.sleep(1)
            }
        }
        try {
            repeat(GC_PRESSURE_ROUNDS) {
                val torrent = CatalogueTorrents.build(root, listOf(tracker))
                assertThat(CatalogueTorrents.infoHash(torrent)).isEqualTo(expected)
                assertThat(CatalogueTorrents.layoutProblem(TorrentInfo(torrent))).isNull()
            }
        } finally {
            stop.set(true)
            collector.join()
        }
    }

    private companion object {
        const val GC_PRESSURE_ROUNDS = 150
    }
}
