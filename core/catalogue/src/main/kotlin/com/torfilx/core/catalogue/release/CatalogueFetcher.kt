package com.torfilx.core.catalogue.release

import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogManifest
import com.torfilx.core.catalogue.format.CatalogRelease
import com.torfilx.core.catalogue.format.CataloguePointer
import com.torfilx.core.catalogue.transport.CatalogueDownload
import com.torfilx.core.catalogue.transport.CatalogueDownloadRequest
import com.torfilx.core.catalogue.transport.CatalogueTransport
import com.torfilx.core.catalogue.transport.CatalogueTransportException
import com.torfilx.core.catalogue.transport.PointerLookup
import java.io.File
import java.net.InetSocketAddress

/**
 * One catalogue check: find the published pointer, and if it names a newer release, download it and
 * verify it.
 *
 * It installs nothing. What to do with a verified release (swap it in, keep seeding it, remember a
 * rejection) belongs to the caller: the app's updater on a television, or the publisher tool's `fetch`
 * command on a desktop. Both run exactly this sequence, so the tool can prove a release is reachable
 * and acceptable the same way the app will find it.
 */
class CatalogueFetcher(
    private val transport: CatalogueTransport,
    private val verifier: CatalogueReleaseVerifier,
    private val publicKeys: List<ByteArray>,
    private val salt: ByteArray = CatalogRelease.dhtSalt(),
) {

    data class Request(
        /** The catalogue version in use now; only a strictly newer release is downloaded. */
        val installedVersion: Long,
        /** A version already found to be bad, which is not downloaded again. */
        val rejectedVersion: Long?,
        val appVersionCode: Int,
        /** Where a release of the given version should be downloaded to. */
        val saveDirFor: (Long) -> File,
        val lookupTimeoutMs: Long = DEFAULT_LOOKUP_TIMEOUT_MS,
        val downloadTimeoutMs: Long = DEFAULT_DOWNLOAD_TIMEOUT_MS,
        val peers: List<InetSocketAddress> = emptyList(),
        val onDownloading: (version: Long, progress: Float) -> Unit = { _, _ -> },
    )

    sealed interface Outcome {
        /** The published release is not newer than what is installed. */
        data class UpToDate(val remoteVersion: Long, val installedVersion: Long) : Outcome

        /** The published release was rejected before, so it was not downloaded again. */
        data class Skipped(val remoteVersion: Long) : Outcome

        /** A newer release downloaded and verified. The torrent is still seeding from [download]. */
        data class Fetched(
            val pointer: PointerLookup.Found,
            val download: CatalogueDownload,
            val manifest: CatalogManifest,
            val entries: List<CatalogEntryDto>,
        ) : Outcome

        /** Nobody publishes a pointer under the trusted keys. */
        data object NotFound : Outcome

        /** The lookup could not run: no session, no DHT, no key. */
        data class Unavailable(val reason: String) : Outcome

        /** A release was found and downloaded but failed verification. Its files may still be on disk. */
        data class Rejected(
            val version: Long,
            val infoHash: String,
            val reason: CatalogueRejectReason,
            val detail: String,
        ) : Outcome

        /** The peer network failed part-way. [version] and [infoHash] are set when a pointer was found. */
        data class TransportFailed(
            val version: Long?,
            val infoHash: String?,
            val error: CatalogueTransportException,
        ) : Outcome
    }

    suspend fun fetch(request: Request): Outcome {
        if (publicKeys.isEmpty()) return Outcome.Unavailable("no catalogue publisher key is configured")

        val lookup = try {
            transport.resolvePointer(publicKeys, salt, request.lookupTimeoutMs)
        } catch (error: CatalogueTransportException) {
            return Outcome.TransportFailed(null, null, error)
        }
        val found = when (lookup) {
            PointerLookup.NotFound -> return Outcome.NotFound
            is PointerLookup.Unavailable -> return Outcome.Unavailable(lookup.reason)
            is PointerLookup.Found -> lookup
        }

        val pointer = found.pointer
        val version = pointer.catalogVersion
        if (pointer.format != CataloguePointer.FORMAT) {
            return Outcome.Rejected(
                version,
                pointer.infoHash,
                CatalogueRejectReason.UNSUPPORTED_POINTER,
                "pointer format ${pointer.format}; this build reads format ${CataloguePointer.FORMAT}",
            )
        }
        if (!CatalogueVersionRules.isNewer(version, request.installedVersion)) {
            return Outcome.UpToDate(version, request.installedVersion)
        }
        if (version == request.rejectedVersion) return Outcome.Skipped(version)

        request.onDownloading(version, 0f)
        val download = try {
            transport.download(
                CatalogueDownloadRequest(
                    infoHash = pointer.infoHash,
                    saveDir = request.saveDirFor(version),
                    maxBytes = CatalogRelease.MAX_TORRENT_BYTES,
                    timeoutMs = request.downloadTimeoutMs,
                    peers = request.peers,
                ),
            ) { progress -> request.onDownloading(version, progress) }
        } catch (error: CatalogueTransportException) {
            return Outcome.TransportFailed(version, pointer.infoHash, error)
        }

        return when (val verdict = verifier.verify(download.rootDir, request.appVersionCode)) {
            is CatalogueReleaseVerifier.Result.Rejected ->
                Outcome.Rejected(version, pointer.infoHash, verdict.reason, verdict.detail)

            is CatalogueReleaseVerifier.Result.Ok ->
                if (verdict.manifest.catalogVersion != version) {
                    Outcome.Rejected(
                        version,
                        pointer.infoHash,
                        CatalogueRejectReason.POINTER_MISMATCH,
                        "the pointer says version $version but the release is version ${verdict.manifest.catalogVersion}",
                    )
                } else {
                    Outcome.Fetched(found, download, verdict.manifest, verdict.entries)
                }
        }
    }

    companion object {
        /** Long enough for a DHT lookup to reach the nodes closest to the key from a cold start. */
        const val DEFAULT_LOOKUP_TIMEOUT_MS = 45_000L

        /** A release is a few hundred kilobytes; three minutes covers a slow swarm with one seeder. */
        const val DEFAULT_DOWNLOAD_TIMEOUT_MS = 180_000L
    }
}
