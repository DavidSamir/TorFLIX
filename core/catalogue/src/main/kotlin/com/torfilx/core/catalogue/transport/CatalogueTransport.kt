package com.torfilx.core.catalogue.transport

import com.torfilx.core.catalogue.format.CataloguePointer
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.InetSocketAddress

/**
 * How a catalogue release travels: a pointer read from the DHT, and a small torrent downloaded and
 * then seeded over the same peer network the films use.
 *
 * An interface because the thing behind it is a native BitTorrent session. The update logic is tested
 * against a fake, the real implementation is tested against a private libtorrent network, and the app
 * and the publisher tool share both.
 */
interface CatalogueTransport {

    /** Whether the peer-network session is up. Nothing below works while this is false. */
    val sessionRunning: StateFlow<Boolean>

    /** Nodes in the DHT routing table right now; zero means the DHT has not bootstrapped. */
    fun dhtNodes(): Long

    /**
     * Looks up the release pointer published under each of [publicKeys] with [salt].
     *
     * Returns the pointer carrying the highest catalogue version among those found. Every value
     * returned has already had its signature checked by the DHT layer against the key it was looked up
     * under, so a pointer cannot be forged, only withheld.
     */
    suspend fun resolvePointer(publicKeys: List<ByteArray>, salt: ByteArray, timeoutMs: Long): PointerLookup

    /**
     * Downloads the release torrent [CatalogueDownloadRequest.infoHash] into its save directory, and keeps
     * seeding it afterwards.
     *
     * Returns only once every file is complete and flushed to disk, so the caller can verify it at
     * once. On failure the partial download is removed from the session and deleted.
     *
     * @throws CatalogueTransportException for every expected failure.
     */
    suspend fun download(
        request: CatalogueDownloadRequest,
        onProgress: (Float) -> Unit = {},
    ): CatalogueDownload

    /**
     * Seeds an already-verified release from [saveDir], where its files already sit.
     *
     * @return the torrent's info hash.
     */
    suspend fun seed(torrentBytes: ByteArray, saveDir: File): String

    /** Stops seeding [infoHash]. Its files stay on disk; deleting them is the caller's decision. */
    suspend fun stopSeeding(infoHash: String)
}

data class CatalogueDownloadRequest(
    val infoHash: String,
    /** The torrent's save path; the release lands in `saveDir/<torrent name>/`. */
    val saveDir: File,
    val maxBytes: Long,
    val timeoutMs: Long,
    /** Peers to try directly, in addition to the DHT and trackers. Empty in normal use. */
    val peers: List<InetSocketAddress> = emptyList(),
)

/** A completed download. [torrentBytes] is the bencoded torrent, kept so the release can be re-seeded. */
class CatalogueDownload(
    val infoHash: String,
    val torrentBytes: ByteArray,
    val rootDir: File,
    val totalBytes: Long,
)

sealed interface PointerLookup {
    data class Found(
        val pointer: CataloguePointer,
        /** The DHT sequence number. Informational: releases are ordered by catalogue version. */
        val seq: Long,
        /** True once the lookup heard from every node it asked, not just the first to answer. */
        val authoritative: Boolean,
        val publicKeyHex: String,
    ) : PointerLookup

    /** The lookup completed and nobody holds a pointer for these keys. */
    data object NotFound : PointerLookup

    /** The lookup could not run at all. [reason] is written for a log line. */
    data class Unavailable(val reason: String) : PointerLookup
}

/** Every expected way a transport operation fails. Anything else is a bug. */
sealed class CatalogueTransportException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoSession : CatalogueTransportException("The peer network session is not running")

    class MetadataTimeout(detail: String) :
        CatalogueTransportException("Nobody sent the catalogue torrent's details in time ($detail)")

    class DownloadTimeout(val progress: Float) :
        CatalogueTransportException("The catalogue download did not finish in time (${(progress * PERCENT).toInt()}%)")

    class TooLarge(val sizeBytes: Long, val capBytes: Long) :
        CatalogueTransportException("The catalogue torrent is $sizeBytes bytes, over the $capBytes-byte limit")

    class UnexpectedContents(detail: String) :
        CatalogueTransportException("The torrent is not a catalogue release: $detail")

    class Engine(detail: String, cause: Throwable? = null) :
        CatalogueTransportException("The peer network reported an error: $detail", cause)

    private companion object {
        const val PERCENT = 100
    }
}
