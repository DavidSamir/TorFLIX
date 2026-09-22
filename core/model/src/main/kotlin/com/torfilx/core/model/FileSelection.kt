package com.torfilx.core.model

/**
 * Which file of a torrent to stream, and how sure the app must be that it is the right one.
 *
 * The difference between the last two is the difference between a torrent that *is* the episode and
 * one that *contains* it. Falling back to the largest file is right for the first — the torrent was
 * meant to be that episode — and wrong for the second, where the largest file is simply some other
 * episode, and playing it would be silently showing the viewer the wrong story.
 */
sealed interface FileSelection {

    /** A film's torrent: its largest video file. */
    data object LargestVideo : FileSelection

    /**
     * An episode's own torrent. Normally a single video; if it turns out to hold several, the
     * episode's file is looked for, and the largest video is still the answer when none matches.
     */
    data class PreferEpisode(val target: EpisodeFileMatcher.Target) : FileSelection

    /** A season pack: exactly this episode's file, or nothing at all. */
    data class Episode(val target: EpisodeFileMatcher.Target) : FileSelection
}
