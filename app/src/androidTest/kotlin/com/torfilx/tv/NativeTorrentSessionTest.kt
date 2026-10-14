package com.torfilx.tv

import com.torfilx.core.catalogue.swarm.CatalogueTorrents
import com.torfilx.core.catalogue.swarm.SessionShutdown
import com.torfilx.core.catalogue.swarm.SwarmCatalogueTransport
import com.torfilx.core.catalogue.swarm.SwarmSessions
import com.torfilx.core.catalogue.transport.CatalogueDownloadRequest
import com.torfilx.core.torrent.LibTorrentEngine
import com.torfilx.core.data.catalog.Catalog
import com.torfilx.core.data.catalog.CatalogueOrigin
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import org.libtorrent4j.Pair
import org.libtorrent4j.SessionHandle
import javax.inject.Inject
import androidx.test.core.app.ApplicationProvider
import android.content.Context

/** Exercises the actual APK's selected native engine, sockets, metadata and downloaded bytes. */
@HiltAndroidTest
class NativeTorrentSessionTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    @Inject lateinit var engine: LibTorrentEngine
    @Inject lateinit var catalog: Catalog

    @Test fun bundledCatalogueNineLoadsFully() {
        hilt.inject()
        val snapshot = catalog.snapshot()
        assertEquals(CatalogueOrigin.BUNDLED, snapshot.info.origin)
        assertEquals(9L, snapshot.info.version)
        assertEquals(2016, snapshot.items.size)
        assertEquals(2271, snapshot.items.sumOf { entry -> entry.seasons.sumOf { it.episodes.size } })
        assertFalse(snapshot.isIncomplete)
        val ted = snapshot.items.single { it.item.id == "show-ted-2024" }
        assertEquals(listOf(7, 8), ted.seasons.map { it.episodes.size })
        assertTrue(snapshot.playable("show-ted-2024-s01e01")!!.sources.isNotEmpty())
        assertTrue(snapshot.playable("show-ted-2024-s02e08")!!.sources.isNotEmpty())
    }

    @Test fun catalogueTransfersBetweenTwoNativeSessions() = runBlocking {
        hilt.inject()
        assertTrue("The device's selected native library must load", engine.isAvailable())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "native-session-test").apply { mkdirs() }
        val seedDir = File(directory, "seed").apply { mkdirs() }
        val release = File(seedDir, "torfilx-catalogue-7").apply { mkdirs() }
        val payload = ByteArray(128 * 1024) { (it % 251).toByte() }
        File(release, "catalog.json.gz").writeBytes(payload)
        File(release, "manifest.json").writeText("{}")
        File(release, "manifest.sig").writeBytes(ByteArray(64))
        val options = SwarmSessions.Options(privateNetwork = true, listenInterfaces = "127.0.0.1:0")
        val seeder = SwarmSessions.start(options)
        val downloader = SwarmSessions.start(options)
        try {
            val seedDhtPort = SwarmSessions.awaitDhtPort(seeder)
            val downloadDhtPort = SwarmSessions.awaitDhtPort(downloader)
            assertTrue("Seeder must bind its DHT UDP socket", seedDhtPort != null)
            assertTrue("Downloader must bind its DHT UDP socket", downloadDhtPort != null)
            SessionHandle(seeder.swig()).addDhtNode(Pair("127.0.0.1", downloadDhtPort!!))
            SessionHandle(downloader.swig()).addDhtNode(Pair("127.0.0.1", seedDhtPort!!))
            assertTrue("DHT must receive responses", SwarmSessions.awaitDhtNodes(downloader, timeoutMs = 15_000L))
            val running = MutableStateFlow(true)
            val seed = SwarmCatalogueTransport({ seeder }, running)
            val fetch = SwarmCatalogueTransport({ downloader }, running)
            val torrent = CatalogueTorrents.build(release, emptyList())
            val hash = seed.seed(torrent, seedDir)
            val port = SwarmSessions.listenPort(seeder)
            assertTrue("Native session must bind a listening socket", port > 0)
            val result = fetch.download(
                CatalogueDownloadRequest(
                    infoHash = hash,
                    saveDir = File(directory, "download"),
                    timeoutMs = 30_000L,
                    maxBytes = 1024 * 1024L,
                    peers = listOf(InetSocketAddress("127.0.0.1", port)),
                ),
            ) {}
            assertArrayEquals(payload, File(result.rootDir, "catalog.json.gz").readBytes())
        } finally {
            SessionShutdown("test-downloader-stop").stopWithin(3_000L) { downloader.stop() }
            SessionShutdown("test-seeder-stop").stopWithin(3_000L) { seeder.stop() }
            directory.deleteRecursively()
        }
    }
}
