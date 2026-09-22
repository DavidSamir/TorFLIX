package com.torfilx.core.torrent

import com.torfilx.core.model.EpisodeFileMatcher
import com.torfilx.core.model.FileSelection

/** The file extensions the engine treats as video. Anything else in a torrent is never downloaded. */
internal val VIDEO_EXTENSIONS = listOf(".mp4", ".mkv", ".avi", ".mov", ".m4v", ".webm", ".mpg", ".mpeg")

internal fun String.isVideoFile(): Boolean {
    val lower = lowercase()
    return VIDEO_EXTENSIONS.any { lower.endsWith(it) }
}

/**
 * Which file of a torrent to stream.
 *
 * - [FileSelection.LargestVideo]: a film's torrent holds one video and some extras; the largest video
 *   is the one, which is what this has always done.
 * - [FileSelection.PreferEpisode]: an episode's own torrent. When it turns out to hold several videos
 *   (a catalogue entry pointing at a whole season by mistake) the episode's file is looked for first;
 *   with no match, the largest video is still the answer.
 * - [FileSelection.Episode]: a season pack. Only the episode's own file will do. With no match there is
 *   nothing to play — the largest file of a pack is simply some other episode.
 *
 * Pure, so it is tested without libtorrent.
 *
 * @return the index of the file to stream, or null when there is nothing suitable.
 */
internal fun chooseVideoFile(
    candidates: List<EpisodeFileMatcher.Candidate>,
    selection: FileSelection,
): Int? {
    val videos = candidates.filter { it.path.isVideoFile() }
    if (videos.isEmpty()) return null
    val largest = videos.maxByOrNull { it.sizeBytes }?.index
    return when (selection) {
        FileSelection.LargestVideo -> largest
        is FileSelection.PreferEpisode ->
            if (videos.size > 1) EpisodeFileMatcher.select(videos, selection.target) ?: largest else largest
        is FileSelection.Episode -> EpisodeFileMatcher.select(videos, selection.target)
    }
}
