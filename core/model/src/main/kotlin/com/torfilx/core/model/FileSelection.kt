package com.torfilx.core.model

/**
 * Which file of a torrent to stream, and how sure the app must be that it is the right one.
 *
 * An episode-specific torrent may contain one unlabelled video. A pack must identify the requested
 * episode. Neither episode mode falls back to an arbitrary largest file in a multi-video torrent.
 */
sealed interface FileSelection {

    /** A film's torrent: its largest video file. */
    data object LargestVideo : FileSelection

    /**
     * An episode's own torrent. Normally a single video; if it turns out to hold several, the
     * episode's file must be identified. A single video with contradictory episode codes is refused.
     */
    data class PreferEpisode(val target: EpisodeFileMatcher.Target) : FileSelection

    /** A season pack: exactly this episode's file, or nothing at all. */
    data class Episode(val target: EpisodeFileMatcher.Target) : FileSelection
}
