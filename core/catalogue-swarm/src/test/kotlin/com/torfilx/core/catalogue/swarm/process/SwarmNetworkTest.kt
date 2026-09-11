package com.torfilx.core.catalogue.swarm.process

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogRelease
import com.torfilx.core.catalogue.release.CatalogueReleaseVerifier
import com.torfilx.core.catalogue.swarm.CatalogueTorrents
import com.torfilx.core.catalogue.swarm.NativeSupport
import com.torfilx.core.catalogue.testing.CatalogueTestKeys
import com.torfilx.core.catalogue.testing.TestCatalogues
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestName
import org.junit.rules.Timeout
import java.io.File
import java.util.UUID

/**
 * The catalogue's whole peer-to-peer path, across processes on a private loopback network.
 *
 * Every libtorrent session runs in a [SwarmNodeMain] process of its own, as it does in the app and in the
 * publisher tool. Nothing leaves the loopback interface: no public routers, trackers, UPnP or local
 * discovery. Each node's log is kept under build/swarm-nodes.
 */
class SwarmNetworkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(900)

    @get:Rule
    val testName = TestName()

    @Before
    fun nativeLibrary() = NativeSupport.require()

    private val keys = listOf(CatalogueTestKeys.PUBLIC_KEY)

    /** A salt no other run has used, so nothing a previous run left in a DHT can interfere. */
    private val salt = "torfilx-test-${UUID.randomUUID()}".encodeToByteArray()

    private fun network() = SwarmNetwork(File("build/swarm-nodes/${testName.methodName.replace(' ', '-')}"))

    private fun entriesOf(root: File): List<CatalogEntryDto> =
        (CatalogueTestKeys.verifier().verify(root, 1) as? CatalogueReleaseVerifier.Result.Ok)?.entries
            ?: throw AssertionError("${root.path} does not verify")

    @Test
    fun `a release travels from publisher to client across processes`() {
        network().use { network ->
            val anchor = network.node("anchor")
            val publisher = network.node("publisher", anchor)
            val client = network.node("client", anchor)
            publisher.awaitDhtNodes()
            client.awaitDhtNodes()
            val publisherDir = File(tmp.root, "publisher")
            val clientDir = File(tmp.root, "client")

            // 1. Nothing published yet: the lookup completes and finds nothing.
            assertThat(client.fetch(installed = 0, keys, salt, clientDir)).isEqualTo(SwarmNodeProcess.Fetch.NotFound)

            // 2. Release 3 is seeded and announced.
            val entries3 = TestCatalogues.entries(40)
            val seeded3 = publisher.seed(TestCatalogues.writeRelease(publisherDir, 3, entries3).releaseRoot)
            assertThat(seeded3.infoHash).isEqualTo(CatalogueTorrents.infoHash(seeded3.torrentFile.readBytes()))
            val put3 = publisher.put(CatalogueTestKeys.SEED, seeded3.infoHash, 3, salt)
            assertWithMessage("DHT nodes that stored the pointer").that(put3.nodesStored).isAtLeast(1)

            // 3. The client finds release 3, downloads it, verifies it, and keeps seeding it.
            val fetched3 = client.fetch(installed = 0, keys, salt, clientDir, peers = listOf(publisher.peerAddress))
            fetched3 as? SwarmNodeProcess.Fetch.Fetched ?: throw AssertionError("expected release 3, got $fetched3")
            assertThat(fetched3.version).isEqualTo(3)
            assertThat(fetched3.infoHash).isEqualTo(seeded3.infoHash)
            assertThat(fetched3.titles).isEqualTo(entries3.size)
            assertThat(fetched3.rootDir).isEqualTo(File(clientDir, "releases/3/${CatalogRelease.rootDirName(3)}"))
            assertThat(entriesOf(fetched3.rootDir)).isEqualTo(entries3)
            assertThat(CatalogueTorrents.infoHash(fetched3.torrentFile.readBytes())).isEqualTo(seeded3.infoHash)
            assertThat(client.seeding(seeded3.infoHash)).isTrue()

            // 4. A client already on release 3 downloads nothing.
            assertThat(client.fetch(installed = 3, keys, salt, clientDir)).isEqualTo(SwarmNodeProcess.Fetch.UpToDate(3, 3))

            // 5. An impersonator publishes under its own key. A client that trusts only the real key
            //    never sees it.
            publisher.put(CatalogueTestKeys.OTHER_SEED, seeded3.infoHash, 99, salt)
            assertThat(client.fetch(installed = 3, keys, salt, clientDir)).isEqualTo(SwarmNodeProcess.Fetch.UpToDate(3, 3))

            // 6. Release 4 replaces the pointer. The client gets no peer hint and finds the publisher
            //    through the DHT, as a television on the real network does.
            val entries4 = TestCatalogues.entries(41)
            val seeded4 = publisher.seed(TestCatalogues.writeRelease(publisherDir, 4, entries4).releaseRoot)
            val put4 = publisher.put(CatalogueTestKeys.SEED, seeded4.infoHash, 4, salt)
            assertThat(put4.seq).isGreaterThan(put3.seq)
            assertThat(put4.nodesStored).isAtLeast(1)
            val fetched4 = client.fetch(installed = 3, keys, salt, clientDir)
            fetched4 as? SwarmNodeProcess.Fetch.Fetched ?: throw AssertionError("expected release 4, got $fetched4")
            assertThat(fetched4.version).isEqualTo(4)
            assertThat(fetched4.infoHash).isEqualTo(seeded4.infoHash)
            assertThat(entriesOf(fetched4.rootDir)).isEqualTo(entries4)

            // 7. The trusted key points at a release signed by someone else: it is downloaded, then refused.
            val forged = TestCatalogues.writeRelease(
                publisherDir,
                5,
                TestCatalogues.entries(2),
                seed = CatalogueTestKeys.OTHER_SEED,
            ).releaseRoot
            val seededForged = publisher.seed(forged)
            publisher.put(CatalogueTestKeys.SEED, seededForged.infoHash, 5, salt)
            val refused = client.fetch(installed = 4, keys, salt, clientDir, peers = listOf(publisher.peerAddress))
            assertThat((refused as? SwarmNodeProcess.Fetch.Rejected)?.reason).isEqualTo("BAD_SIGNATURE")

            // 8. Stopping seeding takes the torrent out of the session and leaves its files alone.
            client.stopSeeding(seeded3.infoHash)
            assertThat(client.seeding(seeded3.infoHash)).isNull()
            assertThat(File(fetched3.rootDir, CatalogRelease.MANIFEST).isFile).isTrue()

            // 9. Seeding a verified release again from where it sits works, and is idempotent.
            repeat(2) {
                assertThat(client.seedTorrent(fetched3.torrentFile, fetched3.rootDir.parentFile)).isEqualTo(seeded3.infoHash)
            }
            assertThat(client.seeding(seeded3.infoHash)).isNotNull()
        }
    }

    @Test
    fun `a publisher adds each new release as the client finishes the previous one`() {
        network().use { network ->
            val publisher = network.node("publisher")
            val client = network.node("client", publisher)
            val publisherDir = File(tmp.root, "publisher")

            var installed: String? = null
            for (version in 1L..HINTED_ROUNDS) {
                val entries = TestCatalogues.entries(2 + version.toInt())
                val seeded = publisher.seed(TestCatalogues.writeRelease(publisherDir, version, entries).releaseRoot)
                val root = client.download(seeded.infoHash, File(tmp.root, "client/$version"), peers = listOf(publisher.peerAddress))
                assertWithMessage("release $version").that(entriesOf(root)).isEqualTo(entries)

                // The app stops seeding the release a new one replaces.
                installed?.let { previous ->
                    client.stopSeeding(previous)
                    assertWithMessage("release ${version - 1} after it was replaced").that(client.seeding(previous)).isNull()
                }
                installed = seeded.infoHash
            }
        }
    }

    @Test
    fun `a client finds each new release through the DHT alone`() {
        network().use { network ->
            val anchor = network.node("anchor")
            val publisher = network.node("publisher", anchor)
            val client = network.node("client", anchor)
            publisher.awaitDhtNodes()
            client.awaitDhtNodes()
            val publisherDir = File(tmp.root, "publisher")

            for (version in 1L..DHT_ROUNDS) {
                val entries = TestCatalogues.entries(2 + version.toInt())
                val seeded = publisher.seed(TestCatalogues.writeRelease(publisherDir, version, entries).releaseRoot)
                val root = client.download(seeded.infoHash, File(tmp.root, "client/$version"))
                assertWithMessage("release $version").that(entriesOf(root)).isEqualTo(entries)
            }
        }
    }

    private companion object {
        const val HINTED_ROUNDS = 12L
        const val DHT_ROUNDS = 10L
    }
}
