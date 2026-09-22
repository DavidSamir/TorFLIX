package com.torfilx.core.model

/** User preference for how playback should be delivered (Settings, plan.md §6.7). */
enum class QualityPreference {
    /** The best source the display can show; anything taller only when nothing else exists. */
    AUTO,

    /** As [AUTO], but never a transcode. Torrents are the file itself, so they always qualify. */
    DIRECT_ONLY,

    /** Prefer a source at or below 1080p — for slow Wi-Fi or the 1.5 GB sticks. */
    CAP_1080P,
}

/**
 * Picks which [MediaSource] to hand to the player.
 *
 * Rules (plan.md §7.2):
 * - a source is only playable if the device reports a decoder for its codec at that resolution,
 *   its HDR type is displayable, and at least one of its audio codecs is supported;
 * - direct play is preferred over transcoding because it costs the server nothing;
 * - sources that already failed to play for this item are excluded, which is what makes the
 *   "direct play failed → retry once as HLS" fallback work;
 * - a torrent is the file itself, played directly: it counts as direct play, never as a transcode;
 * - sources taller than the display are demoted (not excluded), and `CAP_1080P` lowers that ceiling
 *   to 1080p, so playback still works when a 4K file is the only thing on offer.
 *
 * The display ceiling matters because catalogue torrents carry no codec metadata: every one passes
 * [canPlay], and ordering purely by height picked a 2160p file for a 1080p Fire TV Stick — the
 * largest possible download over a swarm, for a picture the screen cannot show.
 */
object SourceSelector {

    data class Result(
        val source: MediaSource?,
        val reason: Reason,
    )

    enum class Reason {
        DIRECT_PLAY,
        TRANSCODE,
        NO_COMPATIBLE_SOURCE,
        ALL_SOURCES_FAILED,
        NO_SOURCES,
    }

    fun select(
        sources: List<MediaSource>,
        capabilities: DeviceCapabilities,
        preference: QualityPreference = QualityPreference.AUTO,
        failedSourceIds: Set<String> = emptySet(),
    ): Result {
        if (sources.isEmpty()) return Result(null, Reason.NO_SOURCES)

        // Torrent is the only delivery mechanism the app has, so it is selectable automatically.
        // Consent is enforced one layer down, in the engine: selecting a source never starts an
        // upload by itself.
        val remaining = sources.filterNot { it.id in failedSourceIds }
        if (remaining.isEmpty()) return Result(null, Reason.ALL_SOURCES_FAILED)

        val playable = remaining.filter { canPlay(it, capabilities) }
        val candidates = when (preference) {
            // "Never transcode" rules out exactly the transcode. It used to keep only DIRECT, which
            // excluded every torrent — the only kind of source the catalogue has — so automatic
            // playback failed outright whenever this preference was set.
            QualityPreference.DIRECT_ONLY -> playable.filter { !it.isTranscode }
            else -> playable
        }
        if (candidates.isEmpty()) return Result(null, Reason.NO_COMPATIBLE_SOURCE)

        val best = candidates.maxWithOrNull(comparator(preference, capabilities))
            ?: return Result(null, Reason.NO_COMPATIBLE_SOURCE)
        val reason = if (best.isTranscode) Reason.TRANSCODE else Reason.DIRECT_PLAY
        return Result(best, reason)
    }

    /**
     * The tallest picture worth downloading: the display's own height, or 1080 under `CAP_1080P`.
     *
     * The display is measured on its short side so an unusual rotation cannot inflate it, and an
     * unknown display (0) imposes no ceiling rather than an absurd one.
     */
    fun heightCeiling(preference: QualityPreference, capabilities: DeviceCapabilities): Int {
        val shortSide = minOf(capabilities.maxDisplayWidth, capabilities.maxDisplayHeight)
        val display = if (shortSide > 0) shortSide else Int.MAX_VALUE
        return when (preference) {
            QualityPreference.CAP_1080P -> minOf(CAP_1080P_HEIGHT, display)
            QualityPreference.AUTO, QualityPreference.DIRECT_ONLY -> display
        }
    }

    private val MediaSource.isTranscode: Boolean get() = kind == SourceKind.HLS

    private const val CAP_1080P_HEIGHT = 1080

    /** True when this device can actually decode and display the source. */
    fun canPlay(source: MediaSource, capabilities: DeviceCapabilities): Boolean {
        // Dolby Vision profile 7 (dual-layer Blu-ray remux) is not decodable on any Fire TV.
        if (source.hdr == HdrType.DOLBY_VISION && source.dolbyVisionProfile == 7) return false

        // HLS is remuxed/transcoded by the server to a device-compatible stream; the codec listed on
        // the source is what the server promises to deliver, so it is still checked.
        val mime = VideoMimeTypes.fromCodecName(source.videoCodec)
        val videoOk = when {
            source.videoCodec == null -> true // server did not say; trust it and fall back on error
            mime == null -> false
            else -> capabilities.supportsVideo(mime, source.width, source.height)
        }
        if (!videoOk) return false

        if (!capabilities.supportsHdr(source.hdr)) return false

        val audioOk = source.audioCodecs.isEmpty() ||
            source.audioCodecs.any { capabilities.supportsAudio(it) } ||
            source.kind == SourceKind.HLS // the server transcodes audio for HLS
        return audioOk
    }

    private fun comparator(preference: QualityPreference, capabilities: DeviceCapabilities): Comparator<MediaSource> {
        val ceiling = heightCeiling(preference, capabilities)
        return compareBy<MediaSource> { source -> if ((source.height ?: 0) > ceiling) 0 else 1 }
            .thenBy { if (it.isTranscode) 0 else 1 }
            .thenBy { it.height ?: 0 }
            // At the same quality an episode's own torrent beats a season pack: a smaller download,
            // a smaller unit to evict, and no chance of the wrong file being picked from it.
            .thenBy { if (it.isSeasonPack) 0 else 1 }
            .thenBy { it.bitrate ?: 0L }
    }
}
