package com.torfilx.core.catalogue.swarm

import com.torfilx.core.catalogue.format.CatalogRelease
import com.torfilx.core.catalogue.format.CataloguePointer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Ed25519
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.DhtPutAlert

/**
 * Publishes the release pointer into the DHT, signed with the publisher's key.
 *
 * Used by the publisher tool and by tests, never by the app. libtorrent signs the item (BEP 44) and
 * chooses its sequence number by adding one to the highest it finds, which is why the release number
 * travels inside the value.
 *
 * DHT nodes forget items after about two hours, so whoever publishes must put the pointer again
 * regularly. The publisher tool does it every 30 minutes for as long as it runs.
 */
class CataloguePublisher(
    private val session: SessionManager,
    seed: ByteArray,
    private val log: SwarmLog = SwarmLog.NONE,
) {

    private val publicKeyBytes: ByteArray
    private val secretKey: ByteArray

    init {
        require(seed.size == Ed25519.SEED_SIZE) { "An Ed25519 seed is ${Ed25519.SEED_SIZE} bytes, got ${seed.size}" }
        val pair = Ed25519.createKeypair(seed)
        publicKeyBytes = pair.first
        secretKey = pair.second
    }

    /** The key the pointer is published under, which is the key an app must trust. */
    val publicKey: ByteArray get() = publicKeyBytes.copyOf()

    data class PutResult(
        /** The sequence number libtorrent chose for this put. */
        val seq: Long,
        /** How many DHT nodes confirmed storing the item. Zero means nobody holds it. */
        val nodesStored: Int,
    )

    /**
     * Puts [pointer] under this publisher's key and [salt], and waits for the DHT to report the result.
     *
     * @return the outcome, or null when the DHT did not report within [timeoutMs].
     */
    suspend fun putPointer(
        pointer: CataloguePointer,
        salt: ByteArray = CatalogRelease.dhtSalt(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): PutResult? {
        val result = CompletableDeferred<PutResult>()
        val listener = object : AlertListener {
            override fun types(): IntArray = intArrayOf(AlertType.DHT_PUT.swig())

            override fun alert(alert: Alert<*>) {
                val put = (alert as? DhtPutAlert)?.swig() ?: return
                runCatching {
                    val key = Vectors.byte_vector2bytes(put.get_public_key())
                    val putSalt = Vectors.byte_vector2bytes(put.get_salt())
                    if (key.contentEquals(publicKeyBytes) && putSalt.contentEquals(salt)) {
                        result.complete(PutResult(put.get_seq(), put.getNum_success()))
                    }
                }.onFailure { log.warn("Could not read a DHT put result", it) }
            }
        }
        session.addListener(listener)
        return try {
            SessionHandle(session.swig()).dhtPutItem(publicKeyBytes, secretKey, Bencoding.entryOf(pointer.toMap()), salt)
            withTimeoutOrNull(timeoutMs) { result.await() }.also { outcome ->
                if (outcome == null) {
                    log.warn("The DHT did not confirm the put of catalogue ${pointer.catalogVersion} within ${timeoutMs / MS_PER_S} s")
                } else {
                    log.info(
                        "Published catalogue ${pointer.catalogVersion} (${pointer.infoHash}) to the DHT: " +
                            "seq=${outcome.seq}, stored by ${outcome.nodesStored} nodes",
                    )
                }
            }
        } finally {
            session.removeListener(listener)
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MS_PER_S = 1_000L
    }
}
