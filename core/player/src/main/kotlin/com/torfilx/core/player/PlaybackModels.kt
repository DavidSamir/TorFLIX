package com.torfilx.core.player

import com.torfilx.core.model.AudioTrackInfo
import com.torfilx.core.model.Episode
import com.torfilx.core.model.Markers
import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.SpriteSheet
import com.torfilx.core.model.SubtitleTrack

/** What the UI asks the player to open. */
data class PlaybackRequest(
    /** Catalogue item id. */
    val playableId: String,
    /**
     * Explicit source choice from the details screen.
     *
     * When null the app picks the best server source itself; when set to a torrent source the user
     * has deliberately chosen the swarm over the server.
     */
    val sourceId: String? = null,
    /** Overrides the stored resume position when non-null (e.g. "Play from beginning"). */
    val startPositionMs: Long? = null,
)

/** A selectable track in the audio/subtitle picker. */
data class TrackOption(
    val id: String,
    val label: String,
    val language: String?,
    val isSelected: Boolean,
    val isSelectable: Boolean = true,
    /** Why a track cannot be selected (e.g. bitmap subtitles in an HLS stream). */
    val unavailableReason: String? = null,
)

enum class AspectMode(val label: String) {
    FIT("Fit"),
    FILL("Fill"),
    ZOOM("Zoom"),
}

/** Distinct failure kinds so each gets its own copy and recovery action (plan.md §7.4). */
sealed interface PlaybackError {
    val message: String

    data class Network(override val message: String, val retryable: Boolean = true) : PlaybackError
    data class Unsupported(override val message: String) : PlaybackError
    data class NotFound(override val message: String) : PlaybackError
    data class Unauthorized(override val message: String) : PlaybackError

    /** The user has not agreed to share while streaming over BitTorrent. */
    data class SharingNotEnabled(override val message: String) : PlaybackError

    /** Not enough free space to stream this title. */
    data class OutOfSpace(override val message: String) : PlaybackError

    data class Unknown(override val message: String) : PlaybackError
}

/** Everything the player screen renders. */
data class PlayerUiState(
    val isLoading: Boolean = true,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val title: String = "",
    val subtitle: String? = null,
    val item: MediaItem? = null,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val subtitlesEnabled: Boolean = false,
    val markers: Markers = Markers(),
    val spriteSheet: SpriteSheet? = null,
    val aspectMode: AspectMode = AspectMode.FIT,
    val playbackSpeed: Float = 1f,
    val error: PlaybackError? = null,
    val showSkipIntro: Boolean = false,
    val showStillWatching: Boolean = false,
    val isTranscoding: Boolean = false,
    /** Set while a display mode switch settles, so the UI can stay black instead of flashing. */
    val isSwitchingDisplayMode: Boolean = false,
    /** Live streaming stats, non-null while the source is a torrent, so buffering can explain itself. */
    val stream: StreamStats? = null,
    /**
     * Extra line shown while loading — e.g. that a swarm is being retried.
     *
     * Without it a retry is invisible: the screen looks identical to a hang, which is what made a
     * slow first attempt feel like a dead app.
     */
    val loadingDetail: String? = null,
    /**
     * Set when the file has video the device can play but no audio it can decode.
     *
     * Silent playback is otherwise indistinguishable from a muted TV, so it is surfaced rather than
     * left for the viewer to work out.
     */
    val audioUnavailableReason: String? = null,
    /** The episode playing, when it is one; its show is [item]. */
    val episode: Episode? = null,
    /** What the player offers after an episode ends; null while playing and for films. */
    val endCard: EndCard? = null,
) {
    val canSeek: Boolean get() = durationMs > 0 && error == null
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)

    /** Seconds of video already downloaded ahead of the play head. */
    val bufferedAheadMs: Long get() = (bufferedPositionMs - positionMs).coerceAtLeast(0)
}

/** The card over the final frame of an episode. See [com.torfilx.core.model.EndOfEpisode]. */
sealed interface EndCard {
    /** Autoplay: [next] starts when [secondsLeft] reaches zero, or at once on "Play now". */
    data class Countdown(val next: Episode, val secondsLeft: Int) : EndCard

    /**
     * [next] waits for the viewer: autoplay is off, or the countdown was cut short by leaving the app.
     *
     * [midEpisode] when the viewer asked for it with the remote's next key before this episode ended:
     * the episode is paused behind the card, and dismissing the card goes back to it rather than
     * leaving the player.
     */
    data class Next(val next: Episode, val midEpisode: Boolean = false) : EndCard

    /** The show is over. [first] is where "Watch again" starts; null when nothing can play. */
    data class EndOfShow(val showTitle: String, val first: Episode?) : EndCard

    /** The next episode has no source. */
    data class NextUnavailable(val next: Episode) : EndCard

    /** A special ended, or the show left the catalogue while it played. */
    data object BackToShow : EndCard
}

/**
 * A snapshot of the torrent feeding the player, shown while buffering so the wait is legible: a
 * viewer can see whether peers are being found and data is flowing, or whether it is stalled.
 */
data class StreamStats(
    val peers: Int = 0,
    val seeds: Int = 0,
    val downloadBytesPerSecond: Int = 0,
    val progress: Float = 0f,
    val hasMetadata: Boolean = false,
)

/** Source data resolved for the current item, kept for retries and track mapping. */
internal data class ResolvedPlayback(
    val playableId: String,
    val item: MediaItem?,
    /** Set when [playableId] is an episode; [item] is then its show. */
    val episode: Episode? = null,
    val subtitles: List<SubtitleTrack>,
    val audio: List<AudioTrackInfo>,
    val markers: Markers,
    val spriteSheet: SpriteSheet?,
    val durationMs: Long,
    val sourceId: String,
    val isTranscode: Boolean,
    val frameRate: Float?,
)
