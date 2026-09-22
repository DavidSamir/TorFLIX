package com.torfilx.core.model

/** User-visible settings. The app has no server, so nothing here describes one. */
data class AppSettings(
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null,
    val subtitlesEnabledByDefault: Boolean = false,
    val autoplayNextEpisode: Boolean = true,
    val quality: QualityPreference = QualityPreference.AUTO,
    val frameRateMatching: Boolean = true,
    val tunneledPlayback: Boolean = true,
    val skipIntroAutomatically: Boolean = false,
    // --- Streaming engine ------------------------------------------------------------------------
    // Defaults reproduce the built-in behaviour; every one exists so a device or network that the
    // defaults do not suit can be made to work without a new build.
    /** Find peers over the distributed hash table. Some networks block it; trackers still work. */
    val useDht: Boolean = true,
    /** Add well-known public trackers on top of a magnet's own, so discovery never rests on one path. */
    val useExtraTrackers: Boolean = true,
    /** How long to wait for a swarm to deliver a title's details before giving up. */
    val metadataTimeout: MetadataTimeout = MetadataTimeout.STANDARD,
    /** How playback is fed from the download: stream while downloading, or buffer more first. */
    val streamingMode: StreamingMode = StreamingMode.SEQUENTIAL,
    // --- Player ----------------------------------------------------------------------------------
    /** Force software video decoding for devices whose hardware decoder rejects a stream. */
    val forceSoftwareDecoder: Boolean = false,
    /** How far one press of ← or → jumps. The remote's rewind and fast-forward keys jump three times as far. */
    val seekStep: SeekStep = SeekStep.TEN,
    /** How long the end card counts down before the next episode starts by itself. */
    val autoplayCountdown: AutoplayCountdown = AutoplayCountdown.STANDARD,
    /** How a new title is fitted to the screen. Changing it in the player lasts for that sitting only. */
    val defaultAspect: AspectPreference = AspectPreference.FIT,
    /** Keep peers, speeds and download progress on screen while a title plays. */
    val showStreamStats: Boolean = false,
    // --- Subtitles -------------------------------------------------------------------------------
    val subtitleSize: SubtitleSize = SubtitleSize.TV_DEFAULT,
    val subtitleStyle: SubtitleStyle = SubtitleStyle.TV_DEFAULT,
    // --- Sharing ---------------------------------------------------------------------------------
    /** Upload cap while sharing. Never below the built-in cap; see [UploadLimit]. */
    val uploadLimit: UploadLimit = UploadLimit.STANDARD,
    // --- Library and display ---------------------------------------------------------------------
    /** How the Movies and Shows grids are sorted when they open. */
    val librarySort: LibrarySort = LibrarySort.DEFAULT,
    /** Open the Movies and Shows grids on unwatched titles only. */
    val hideWatched: Boolean = false,
    /** No hero auto-advance, no focus zoom, no pulsing placeholders: for the slowest sticks. */
    val reduceMotion: Boolean = false,
)

/** Presets for how long to wait for peers to send a title's metadata. */
enum class MetadataTimeout(val seconds: Int, val label: String) {
    QUICK(60, "1 min"),
    STANDARD(120, "2 min"),
    PATIENT(300, "5 min"),
}

/** How the player is fed from an in-progress torrent. */
enum class StreamingMode(val label: String) {
    /** Prioritise pieces in play order and start as soon as the front of the file is ready. */
    SEQUENTIAL("Stream (start fast)"),

    /** Buffer a larger head of the file before starting; steadier on weak or bursty connections. */
    BUFFERED("Buffer more first"),
}

/** One press of ← or → in the player. */
enum class SeekStep(val seconds: Int, val label: String) {
    TEN(10, "10 s"),
    THIRTY(30, "30 s"),
    SIXTY(60, "60 s"),
    ;

    val ms: Long get() = seconds * MS_PER_SECOND

    private companion object {
        const val MS_PER_SECOND = 1_000L
    }
}

/** The next-episode countdown. [IMMEDIATE] skips the card and plays on at once. */
enum class AutoplayCountdown(val seconds: Int, val label: String) {
    IMMEDIATE(0, "Instantly"),
    SHORT(5, "5 s"),
    STANDARD(10, "10 s"),
    LONG(20, "20 s"),
}

/** How video is fitted to the screen when a title opens. */
enum class AspectPreference(val label: String) {
    FIT("Fit"),
    FILL("Fill"),
    ZOOM("Zoom"),
}

/**
 * Subtitle text size.
 *
 * [TV_DEFAULT] follows the TV's own caption settings, which is what the app did before this setting
 * existed; the others are a multiple of the player's standard size and ignore sizes a subtitle file
 * asks for.
 */
enum class SubtitleSize(val scale: Float, val label: String) {
    TV_DEFAULT(1f, "TV default"),
    SMALL(0.75f, "Small"),
    MEDIUM(1f, "Medium"),
    LARGE(1.35f, "Large"),
    EXTRA_LARGE(1.7f, "Extra large"),
}

/** Subtitle colours. [TV_DEFAULT] follows the TV's own caption settings. */
enum class SubtitleStyle(val label: String) {
    TV_DEFAULT("TV default"),
    OUTLINED("White, outlined"),
    BOXED("White on black"),
    YELLOW("Yellow, outlined"),
}

private const val MIB = 1024 * 1024

/**
 * The upload cap while sharing, in bytes per second; 0 means no cap.
 *
 * The smallest choice is the cap the app has always used. There is deliberately nothing below it:
 * the viewer can give the swarm more, never less than the app's own baseline.
 */
enum class UploadLimit(val bytesPerSecond: Int, val label: String) {
    STANDARD(2 * MIB, "2 MB/s"),
    FIVE(5 * MIB, "5 MB/s"),
    TEN(10 * MIB, "10 MB/s"),
    UNLIMITED(0, "Unlimited"),
    ;

    val isUnlimited: Boolean get() = bytesPerSecond == 0

    companion object {
        /** The floor any configured cap is held to, whatever is stored. */
        val FLOOR_BYTES_PER_SECOND: Int = STANDARD.bytesPerSecond
    }
}
