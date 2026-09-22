package com.torfilx.core.model

/** How a source is delivered. Direct = progressive HTTP with range requests; HLS = server transcode. */
enum class SourceKind {
    /** Progressive HTTP from the media server. */
    DIRECT,

    /** Server-side transcode. */
    HLS,

    /**
     * A magnet link played through the app.s own BitTorrent engine.
     *
     * Never chosen automatically: the user picks it explicitly, because it uploads as well as
     * downloads and that is their decision to make.
     */
    TORRENT,
}

enum class HdrType { NONE, HDR10, HDR10_PLUS, DOLBY_VISION, HLG }

/**
 * One playable representation of an item. The server offers several; the app chooses using its own
 * decoder capability report (plan.md §7.2) rather than guessing from the file extension.
 */
data class MediaSource(
    val id: String,
    val kind: SourceKind,
    val url: String,
    val container: String? = null,
    val videoCodec: String? = null,
    val audioCodecs: List<String> = emptyList(),
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    val hdr: HdrType = HdrType.NONE,
    val bitrate: Long? = null,
    /** Dolby Vision profile (5, 7, 8…) when [hdr] is DOLBY_VISION; profile 7 is undecodable on Fire TV. */
    val dolbyVisionProfile: Int? = null,
    /** Set for [SourceKind.TORRENT]: the magnet the engine resolves before playback. */
    val magnetUri: String? = null,
    /** Human label for the source picker, e.g. "Torrent · 1080p". */
    val label: String? = null,
    /**
     * Set for a season pack: the one file of the torrent this source plays. Null for a film's or an
     * episode's own torrent, whose file the player chooses (see [FileSelection]).
     */
    val fileSelection: FileSelection? = null,
) {
    /** True for a whole-season torrent that this source plays one episode out of. */
    val isSeasonPack: Boolean get() = fileSelection is FileSelection.Episode
}

enum class SubtitleFormat { VTT, SRT, ASS, PGS, DVD_SUB, UNKNOWN }

data class SubtitleTrack(
    val id: String,
    val language: String?,
    val label: String?,
    /** Sidecar URL. Null for tracks embedded in the container. */
    val url: String? = null,
    val format: SubtitleFormat = SubtitleFormat.VTT,
    val isForced: Boolean = false,
    val isDefault: Boolean = false,
    val isEmbedded: Boolean = false,
) {
    /**
     * Bitmap subtitles can be rendered from a Matroska container but never from an HLS stream, and
     * sidecar delivery is text-only — the picker greys these out with a reason (plan.md §7.2).
     */
    val isBitmap: Boolean get() = format == SubtitleFormat.PGS || format == SubtitleFormat.DVD_SUB
}

data class AudioTrackInfo(
    val id: String,
    val index: Int,
    val language: String?,
    val label: String?,
    val channels: Int? = null,
    val codec: String? = null,
    val isDefault: Boolean = false,
)

data class TimeRange(val startMs: Long, val endMs: Long) {
    fun contains(positionMs: Long): Boolean = positionMs in startMs until endMs
}

/** Chapter markers used by Skip Intro and the next-episode countdown (plan.md §7.4). */
data class Markers(
    val intro: TimeRange? = null,
    val recap: TimeRange? = null,
    val creditsStartMs: Long? = null,
)

/** Seek-preview sprite sheet: one grid image per [intervalMs] of video. */
data class SpriteSheet(
    val url: String,
    val intervalMs: Long,
    val columns: Int,
    val rows: Int,
    val thumbWidth: Int,
    val thumbHeight: Int,
) {
    fun tileIndexFor(positionMs: Long): Int =
        if (intervalMs <= 0) 0 else (positionMs / intervalMs).toInt().coerceAtLeast(0)
}

/** Everything the player needs to start a specific item. */
data class PlaybackInfo(
    val itemId: String,
    val sources: List<MediaSource>,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val audioTracks: List<AudioTrackInfo> = emptyList(),
    val markers: Markers = Markers(),
    val spriteSheet: SpriteSheet? = null,
    val resume: PlaybackProgress? = null,
    val durationMs: Long? = null,
)
