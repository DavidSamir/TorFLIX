package com.torfilx.core.catalogue.swarm

import kotlinx.coroutines.delay
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.ListenSucceededAlert
import org.libtorrent4j.alerts.SocketType
import org.libtorrent4j.swig.dht_settings
import org.libtorrent4j.swig.settings_pack
import java.net.InetSocketAddress
import java.util.Collections
import java.util.WeakHashMap

/**
 * Starts libtorrent sessions for the publisher tool and for tests.
 *
 * The app never uses this: its engine owns its own session. A public session here is configured the
 * way the engine's is, so the tool behaves on the real network the way the television does.
 */
object SwarmSessions {

    data class Options(
        /** libtorrent `listen_interfaces`, for example `0.0.0.0:6881`. Null keeps libtorrent's default. */
        val listenInterfaces: String? = null,
        /**
         * Comma-separated `host:port` DHT bootstrap routers. Null keeps libtorrent4j's public routers.
         *
         * libtorrent only asks routers for other nodes and never keeps them in its routing table, so on
         * a network with no other nodes to hand out, a router alone gets you nowhere; use [dhtNodes].
         */
        val dhtBootstrapNodes: String? = null,
        /**
         * Ordinary DHT nodes to contact directly, addressed on their DHT (UDP) port. Unlike routers,
         * they join the routing table once they answer, which is what a private network needs.
         */
        val dhtNodes: List<InetSocketAddress> = emptyList(),
        /**
         * A closed network for tests and local rehearsals: no UPnP, NAT-PMP or local service discovery,
         * no public bootstrap routers, and the DHT's anti-abuse rules relaxed so that several nodes on
         * one address (the loopback interface) can find each other.
         */
        val privateNetwork: Boolean = false,
    )

    private const val LOOPBACK_ANY_PORT = "127.0.0.1:0"
    private const val POLL_MS = 200L
    private const val PORT_POLL_MS = 50L
    private const val NANOS_PER_MS = 1_000_000L

    /** Each session's DHT port, as libtorrent reported it. Weak, so a stopped session is forgotten. */
    private val dhtPorts: MutableMap<SessionManager, Int> = Collections.synchronizedMap(WeakHashMap())

    fun start(options: Options = Options()): SessionManager {
        val session = SessionManager(false)
        // Attached before the session starts: the listen alerts are among the very first it posts.
        session.addListener(
            object : AlertListener {
                override fun types(): IntArray = intArrayOf(AlertType.LISTEN_SUCCEEDED.swig())

                override fun alert(alert: Alert<*>) {
                    val listen = alert as? ListenSucceededAlert ?: return
                    if (runCatching { listen.socketType() }.getOrNull() == SocketType.UDP) {
                        dhtPorts[session] = listen.port()
                    }
                }
            },
        )

        if (options.privateNetwork) {
            val pack = SettingsPack().apply {
                setString(settings_pack.string_types.listen_interfaces.swigValue(), options.listenInterfaces ?: LOOPBACK_ANY_PORT)
                setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), options.dhtBootstrapNodes.orEmpty())
                // The DHT is started only after its settings are in place, below.
                setBoolean(settings_pack.bool_types.enable_dht.swigValue(), false)
                setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), false)
                setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
                setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
                setBoolean(settings_pack.bool_types.allow_multiple_connections_per_ip.swigValue(), true)
                setBoolean(settings_pack.bool_types.dht_prefer_verified_node_ids.swigValue(), false)
            }
            session.start(SessionParams(pack))
            session.swig().set_dht_settings(
                dht_settings().apply {
                    setRestrict_routing_ips(false)
                    setRestrict_search_ips(false)
                    setEnforce_node_id(false)
                    setIgnore_dark_internet(false)
                    // Every node of a loopback network shares one IP address, so libtorrent's per-IP
                    // flood protection (5 packets a second, then blocked for five minutes) would
                    // silence the whole network after a few lookups. On the public DHT each node has its
                    // own address and the default stays in force.
                    setBlock_ratelimit(PRIVATE_NETWORK_PACKETS_PER_SECOND)
                },
            )
            session.applySettings(SettingsPack().apply { setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true) })
        } else {
            // libtorrent4j's own defaults first (they bind the listen sockets and set the bootstrap
            // routers), then only what was asked for on top. This is how the app's engine starts too.
            session.start()
            val tweaks = SettingsPack().apply {
                setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
                options.listenInterfaces?.let { setString(settings_pack.string_types.listen_interfaces.swigValue(), it) }
                options.dhtBootstrapNodes?.let { setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), it) }
            }
            session.applySettings(tweaks)
        }
        if (!session.isDhtRunning) session.startDht()
        // libtorrent queues these until its DHT is up, so the order against startDht does not matter.
        val handle = SessionHandle(session.swig())
        options.dhtNodes.forEach { node -> handle.addDhtNode(org.libtorrent4j.Pair(node.hostString, node.port)) }
        return session
    }

    /** The port the session accepts peer connections on (TCP). */
    fun listenPort(session: SessionManager): Int = SessionHandle(session.swig()).listenPort

    /**
     * The port the session's DHT listens on (UDP), once libtorrent has bound it.
     *
     * Not necessarily [listenPort]. Asked for port 0, libtorrent 1.2 binds TCP and UDP separately, and
     * on Windows the two come out different; a DHT node addressed on its TCP port never answers.
     *
     * @return the port, or null when no UDP socket was reported within [timeoutMs].
     */
    suspend fun awaitDhtPort(session: SessionManager, timeoutMs: Long = DEFAULT_PORT_WAIT_MS): Int? {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MS
        while (true) {
            dhtPorts[session]?.let { return it }
            if (System.nanoTime() >= deadline) return null
            delay(PORT_POLL_MS)
        }
    }

    /** Waits until the DHT routing table holds at least [minNodes] nodes. */
    suspend fun awaitDhtNodes(session: SessionManager, minNodes: Long = 1, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MS
        while (true) {
            if (runCatching { session.dhtNodes() }.getOrDefault(0L) >= minNodes) return true
            if (System.nanoTime() >= deadline) return false
            delay(POLL_MS)
        }
    }

    private const val DEFAULT_PORT_WAIT_MS = 5_000L
    private const val PRIVATE_NETWORK_PACKETS_PER_SECOND = 10_000
}
