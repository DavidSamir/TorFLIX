package com.torfilx.core.catalogue.swarm

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File

class DhtStateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    @Before
    fun nativeLibrary() = NativeSupport.require()

    @Test
    fun `a node restarted from its saved state rejoins the DHT without any bootstrap node`() = runBlocking {
        LocalSwarm().use { swarm ->
            val anchor = swarm.node()
            val member = swarm.node(bootstrap = anchor)
            assertWithMessage("the member joins the DHT").that(SwarmSessions.awaitDhtNodes(member, 1, JOIN_TIMEOUT_MS)).isTrue()

            val file = File(tmp.root, "torrent-session/dht.state")
            val written = DhtStateStore.save(member, file)

            assertThat(written).isNotNull()
            assertThat(file.length()).isEqualTo(written!!.toLong())
            assertThat(DhtStateStore.nodeCount(file.readBytes())).isAtLeast(1)
            assertThat(File(file.parentFile, "dht.state.tmp").exists()).isFalse()

            // The member goes away; a fresh node knows nobody, except through the saved state.
            swarm.stop(member)
            val restarted = swarm.node(bootstrap = null)
            assertThat(restarted.dhtNodes()).isEqualTo(0)

            assertThat(DhtStateStore.load(restarted, file)).isTrue()
            assertWithMessage("the restarted node rejoins through its saved state")
                .that(SwarmSessions.awaitDhtNodes(restarted, 1, JOIN_TIMEOUT_MS)).isTrue()
        }
    }

    @Test
    fun `saving twice replaces the file rather than failing`() = runBlocking {
        LocalSwarm().use { swarm ->
            val anchor = swarm.node()
            val member = swarm.node(bootstrap = anchor)
            SwarmSessions.awaitDhtNodes(member, 1, JOIN_TIMEOUT_MS)
            val file = File(tmp.root, "dht.state")

            assertThat(DhtStateStore.save(member, file)).isNotNull()
            assertThat(DhtStateStore.save(member, file)).isNotNull()
            assertThat(DhtStateStore.nodeCount(file.readBytes())).isNotNull()
        }
    }

    @Test
    fun `a file that is not a DHT state is deleted instead of loaded`() = runBlocking {
        LocalSwarm().use { swarm ->
            val session = swarm.node()
            val garbage = File(tmp.root, "dht.state").apply { writeText("this is not bencode") }
            val wrongShape = File(tmp.root, "other.state").apply { writeBytes("d3:fooi1ee".encodeToByteArray()) }
            val empty = File(tmp.root, "empty.state").apply { writeBytes(ByteArray(0)) }

            assertThat(DhtStateStore.load(session, garbage)).isFalse()
            assertThat(DhtStateStore.load(session, wrongShape)).isFalse()
            assertThat(DhtStateStore.load(session, empty)).isFalse()
            assertThat(garbage.exists()).isFalse()
            assertThat(wrongShape.exists()).isFalse()
            assertThat(empty.exists()).isFalse()
            assertThat(DhtStateStore.load(session, File(tmp.root, "missing.state"))).isFalse()
        }
    }

    @Test
    fun `node counts are read only from real DHT states`() {
        assertThat(DhtStateStore.nodeCount("not bencode".encodeToByteArray())).isNull()
        assertThat(DhtStateStore.nodeCount("i42e".encodeToByteArray())).isNull()
        assertThat(DhtStateStore.nodeCount("d9:dht stated5:nodesl6:abcdefeee".encodeToByteArray())).isEqualTo(1)
        assertThat(DhtStateStore.nodeCount("d9:dht statedee".encodeToByteArray())).isEqualTo(0)
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 30_000L
    }
}
