package com.torfilx.core.torrent

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.model.EpisodeFileMatcher.Candidate
import com.torfilx.core.model.EpisodeFileMatcher.Target
import com.torfilx.core.model.FileSelection
import org.junit.Test

/** Which file of a torrent is streamed, for a film, an episode's own torrent, and a season pack. */
class FileChoiceTest {

    private val gb = 1_000_000_000L

    private fun files(vararg entries: Pair<String, Long>) =
        entries.mapIndexed { index, (path, size) -> Candidate(index, path, size) }

    private val pack = files(
        "Show.S01/Show.S01E01.mkv" to 2 * gb,
        "Show.S01/Show.S01E02.mkv" to gb,
        "Show.S01/Show.S01E03.mkv" to gb,
    )

    @Test
    fun `a film's torrent streams its largest video and ignores everything else`() {
        val torrent = files(
            "Film/Film.srt" to 50_000,
            "Film/Film.1080p.mkv" to 2 * gb,
            "Film/Sample.mkv" to 30_000_000,
            "Film/poster.jpg" to 200_000,
        )
        assertThat(chooseVideoFile(torrent, FileSelection.LargestVideo)).isEqualTo(1)
    }

    @Test
    fun `a torrent with no video has nothing to stream, whatever was asked`() {
        val noVideo = files("a.srt" to 1, "b.nfo" to 2)
        assertThat(chooseVideoFile(noVideo, FileSelection.LargestVideo)).isNull()
        assertThat(chooseVideoFile(emptyList(), FileSelection.Episode(Target(1, 1, 0, 1)))).isNull()
    }

    @Test
    fun `an episode that turns out to be a season pack plays the episode asked for, not the largest`() {
        assertThat(chooseVideoFile(pack, FileSelection.PreferEpisode(Target(1, 2, 1, 3)))).isEqualTo(1)
    }

    @Test
    fun `an episode's own torrent that matches nothing still plays its largest video, as before`() {
        val torrent = files("A.mkv" to gb, "B.mkv" to 2 * gb)
        assertThat(chooseVideoFile(torrent, FileSelection.PreferEpisode(Target(4, 9, 8, 20)))).isEqualTo(1)
    }

    @Test
    fun `an episode's own single-video torrent streams that video whatever its name says`() {
        val single = files("Show.S01E07.mkv" to gb, "Show.S01E07.srt" to 1_000)
        assertThat(chooseVideoFile(single, FileSelection.PreferEpisode(Target(1, 3, 2, 10)))).isEqualTo(0)
    }

    @Test
    fun `a season pack plays exactly the episode's file`() {
        assertThat(chooseVideoFile(pack, FileSelection.Episode(Target(1, 3, 2, 3)))).isEqualTo(2)
    }

    @Test
    fun `a season pack without the episode plays nothing, never the largest file`() {
        assertThat(chooseVideoFile(pack, FileSelection.Episode(Target(2, 1, 0, 3)))).isNull()
        assertThat(chooseVideoFile(pack, FileSelection.Episode(Target(1, 9, 8, 10)))).isNull()
    }

    @Test
    fun `video extensions are recognised whatever their case`() {
        assertThat("FILM.MKV".isVideoFile()).isTrue()
        assertThat("clip.m4v".isVideoFile()).isTrue()
        assertThat("notes.txt".isVideoFile()).isFalse()
    }
}
