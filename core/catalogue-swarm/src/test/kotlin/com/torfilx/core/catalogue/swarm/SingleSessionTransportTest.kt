package com.torfilx.core.catalogue.swarm

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.format.CatalogRelease
import com.torfilx.core.catalogue.testing.CatalogueTestKeys
import com.torfilx.core.catalogue.transport.CatalogueDownloadRequest
import com.torfilx.core.catalogue.transport.CatalogueTransportException
import com.torfilx.core.catalogue.transport.PointerLookup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import org.libtorrent4j.Sha1Hash
import java.io.File

/**
 * The transport against one real libtorrent session: a download nobody can serve, and lookups that cannot
 * run at all.
 *
 * Everything that needs a publisher and a client lives in process.SwarmNetworkTest, where each session
 * has a process of its own.
 */
class SingleSessionTransportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    @Before
    fun nativeLibrary() = NativeSupport.require()

    private val running = MutableStateFlow(true)

    @Test
    fun `a download nobody can serve times out and leaves nothing in the session`() = runBlocking {
        LocalSwarm().use { swarm ->
            val lonely = swarm.node()
            val transport = SwarmCatalogueTransport(session = { lonely }, sessionRunning = running, log = testLog("lonely"))
            val hash = "ab".repeat(20)

            val error = runCatching {
                transport.download(
                    CatalogueDownloadRequest(
                        infoHash = hash,
                        saveDir = File(tmp.root, "lonely"),
                        maxBytes = CatalogRelease.MAX_TORRENT_BYTES,
                        timeoutMs = 3_000,
                    ),
                )
            }.exceptionOrNull()

            assertThat(error).isInstanceOf(CatalogueTransportException.MetadataTimeout::class.java)
            assertThat(lonely.find(Sha1Hash(hash))).isNull()
        }
    }

    @Test
    fun `lookups say plainly why they cannot run`() = runBlocking {
        LocalSwarm().use { swarm ->
            val lonely = swarm.node()
            val keys = listOf(CatalogueTestKeys.PUBLIC_KEY)
            val salt = CatalogRelease.dhtSalt()

            val noSession = SwarmCatalogueTransport(session = { null }, sessionRunning = running)
            assertThat(noSession.resolvePointer(keys, salt, 1_000)).isInstanceOf(PointerLookup.Unavailable::class.java)

            val dhtOff = SwarmCatalogueTransport(session = { lonely }, sessionRunning = running, dhtEnabled = { false })
            assertThat(dhtOff.resolvePointer(keys, salt, 1_000)).isEqualTo(PointerLookup.Unavailable("the DHT is turned off"))

            val transport = SwarmCatalogueTransport(session = { lonely }, sessionRunning = running, log = testLog("lonely"))
            assertThat(transport.resolvePointer(emptyList(), salt, 1_000)).isInstanceOf(PointerLookup.Unavailable::class.java)
            // A node with nobody to ask gives up on nodes rather than hanging for the whole timeout.
            val noNodes = transport.resolvePointer(keys, salt, 2_000)
            assertThat((noNodes as PointerLookup.Unavailable).reason).contains("no nodes")

            val download = runCatching { noSession.download(CatalogueDownloadRequest("ab".repeat(20), tmp.root, 1, 1)) }
            assertThat(download.exceptionOrNull()).isInstanceOf(CatalogueTransportException.NoSession::class.java)
        }
    }
}
