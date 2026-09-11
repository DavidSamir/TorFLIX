package com.torfilx.core.torrent

/**
 * How much disk the app may use for torrent data, derived from what is actually free.
 *
 * A Fire TV Stick has ~5 GB of usable storage, and filling it would break the whole device — so the
 * budget is always a *fraction of free space* with a hard floor below which the app stops
 * downloading and starts evicting.
 */
data class StorageBudget(
    /** Fraction of currently-free space the app may claim (0.0–1.0). */
    val fractionOfFree: Float = DEFAULT_FRACTION,
    /** Never use more than this, whatever the free space. */
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    /** Stop downloading entirely when free space drops below this. */
    val reserveBytes: Long = DEFAULT_RESERVE_BYTES,
) {
    /** The cap for a device currently reporting [freeBytes] free. */
    fun capBytes(freeBytes: Long): Long {
        val usable = (freeBytes - reserveBytes).coerceAtLeast(0L)
        return minOf((usable * fractionOfFree).toLong(), maxBytes)
    }

    companion object {
        const val DEFAULT_FRACTION = 0.5f
        const val DEFAULT_MAX_BYTES = 8L * 1024 * 1024 * 1024
        const val DEFAULT_RESERVE_BYTES = 500L * 1024 * 1024

        val DEFAULT = StorageBudget()
    }
}

/** Live state of one torrent. */
data class TorrentStatus(
    val infoHash: String,
    val name: String,
    val progress: Float,
    val downloadRateBytesPerSecond: Int,
    val uploadRateBytesPerSecond: Int,
    val peers: Int,
    val seeds: Int,
    val totalDownloadedBytes: Long,
    val totalUploadedBytes: Long,
    val isSeeding: Boolean,
    val isPaused: Boolean,
    val hasMetadata: Boolean,
    val sizeBytes: Long,
)

/** Aggregate sharing statistics shown in Settings. */
data class SharingStats(
    val activeTorrents: Int = 0,
    val downloadRateBytesPerSecond: Int = 0,
    val uploadRateBytesPerSecond: Int = 0,
    val totalUploadedBytes: Long = 0,
    val totalDownloadedBytes: Long = 0,
    val diskUsedBytes: Long = 0,
    val diskCapBytes: Long = 0,
    val freeSpaceBytes: Long = 0,
    val isSeeding: Boolean = false,
) {
    val ratio: Float
        get() = if (totalDownloadedBytes <= 0) 0f else totalUploadedBytes.toFloat() / totalDownloadedBytes
}

/** A stream ready for the player: a local URL plus the handle that keeps it fed. */
data class TorrentStream(
    val infoHash: String,
    /** `http://127.0.0.1:<port>/<infoHash>` — served by the app's own loopback bridge. */
    val url: String,
    val fileName: String,
    val fileSizeBytes: Long,
)

/** Why a torrent stream could not be started. */
sealed class TorrentError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConsented : TorrentError("Sharing has not been enabled")
    class InvalidMagnet(magnet: String) : TorrentError("Not a valid magnet link: ${magnet.take(60)}")
    class NoSpace(needed: Long, available: Long) :
        TorrentError("Not enough free space (needs ${needed / 1_000_000} MB, ${available / 1_000_000} MB free)")

    class MetadataTimeout(diagnostics: String? = null) : TorrentError(
        buildString {
            append("No peers responded with torrent details")
            if (!diagnostics.isNullOrBlank()) append("\n\n").append(diagnostics)
        },
    )
    class NoPlayableFile : TorrentError("The torrent contains no playable video file")
    class EngineUnavailable(cause: Throwable?) :
        TorrentError("The torrent engine could not start on this device", cause)
}

/** Converts a hex info hash to libtorrent's hash type. */
internal fun String.toSha1Hash(): org.libtorrent4j.Sha1Hash = org.libtorrent4j.Sha1Hash(this)
