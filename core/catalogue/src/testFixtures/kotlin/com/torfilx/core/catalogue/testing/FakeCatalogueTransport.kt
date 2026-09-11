package com.torfilx.core.catalogue.testing

import com.torfilx.core.catalogue.Hex
import com.torfilx.core.catalogue.format.CataloguePointer
import com.torfilx.core.catalogue.transport.CatalogueDownload
import com.torfilx.core.catalogue.transport.CatalogueDownloadRequest
import com.torfilx.core.catalogue.transport.CatalogueTransport
import com.torfilx.core.catalogue.transport.CatalogueTransportException
import com.torfilx.core.catalogue.transport.PointerLookup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Collections
import java.util.Locale

/**
 * A peer network in memory.
 *
 * [publish] makes the "DHT" point at a release directory and the "swarm" deliver it. Every call is
 * recorded, so a test can assert what was looked up, downloaded, seeded and stopped.
 */
class FakeCatalogueTransport(running: Boolean = true) : CatalogueTransport {

    val running = MutableStateFlow(running)
    override val sessionRunning: StateFlow<Boolean> = this.running

    @Volatile
    var nodes: Long = DEFAULT_NODES

    @Volatile
    var lookupResult: PointerLookup = PointerLookup.NotFound

    @Volatile
    var lookupError: CatalogueTransportException? = null

    @Volatile
    var downloadError: CatalogueTransportException? = null

    private val releases = Collections.synchronizedMap(HashMap<String, File>())

    val lookups: MutableList<List<String>> = Collections.synchronizedList(ArrayList())
    val downloads: MutableList<CatalogueDownloadRequest> = Collections.synchronizedList(ArrayList())
    val seeded: MutableList<Pair<String, File>> = Collections.synchronizedList(ArrayList())
    val stoppedSeeding: MutableList<String> = Collections.synchronizedList(ArrayList())

    /**
     * Publishes [releaseRoot] as release [version]: the next lookup finds a pointer to it, and a download
     * of that info hash copies it into the requested save directory.
     *
     * @return the release's (fake) info hash.
     */
    fun publish(
        releaseRoot: File,
        version: Long,
        infoHash: String = infoHashFor(version),
        publicKey: ByteArray = CatalogueTestKeys.PUBLIC_KEY,
        seq: Long = 1,
        format: Int = CataloguePointer.FORMAT,
    ): String {
        releases[infoHash] = releaseRoot
        lookupResult = PointerLookup.Found(
            pointer = CataloguePointer(format, infoHash, version),
            seq = seq,
            authoritative = true,
            publicKeyHex = Hex.encode(publicKey),
        )
        return infoHash
    }

    override fun dhtNodes(): Long = nodes

    override suspend fun resolvePointer(publicKeys: List<ByteArray>, salt: ByteArray, timeoutMs: Long): PointerLookup {
        lookups += publicKeys.map(Hex::encode)
        lookupError?.let { throw it }
        return lookupResult
    }

    override suspend fun download(request: CatalogueDownloadRequest, onProgress: (Float) -> Unit): CatalogueDownload {
        downloads += request
        downloadError?.let { throw it }
        val source = releases[request.infoHash]
            ?: throw CatalogueTransportException.MetadataTimeout("no fake peer holds ${request.infoHash}")
        val root = File(request.saveDir, source.name)
        source.copyRecursively(root, overwrite = true)
        onProgress(1f)
        return CatalogueDownload(
            infoHash = request.infoHash,
            torrentBytes = torrentBytesFor(request.infoHash),
            rootDir = root,
            totalBytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() },
        )
    }

    override suspend fun seed(torrentBytes: ByteArray, saveDir: File): String {
        val infoHash = torrentBytes.decodeToString().removePrefix(TORRENT_PREFIX)
        seeded += infoHash to saveDir
        return infoHash
    }

    override suspend fun stopSeeding(infoHash: String) {
        stoppedSeeding += infoHash
    }

    companion object {
        private const val DEFAULT_NODES = 100L
        private const val TORRENT_PREFIX = "fake-torrent:"
        private const val HASH_BASE = 0xCA7A_0000L

        fun infoHashFor(version: Long): String = String.format(Locale.ROOT, "%040x", HASH_BASE + version)

        /** The stand-in for a bencoded torrent: seeding it again yields the same info hash. */
        fun torrentBytesFor(infoHash: String): ByteArray = "$TORRENT_PREFIX$infoHash".encodeToByteArray()
    }
}
