package com.torfilx.core.ui.component

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.model.Episode
import com.torfilx.core.model.MediaCard
import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.MediaKind
import com.torfilx.core.model.PlaybackProgress
import org.junit.Test

/** The words under a focused card, and what VoiceView reads for cards and episode rows. */
class CardTextTest {

    private val minute = 60_000L
    private val film = MediaItem(id = "f", title = "The Kid", year = 1921, runtimeMs = 68 * minute)
    private val show = MediaItem(id = "s", title = "Twilight", year = 1959, kind = MediaKind.SHOW, seasonCount = 5, episodeCount = 156)
    private val episode = Episode(id = "s-s01e03", showId = "s", season = 1, number = 3, name = "The Lonely", runtimeMs = 25 * minute)

    @Test
    fun `a film's card gives its year and runtime, or what is left`() {
        assertThat(MediaCard(film).subtitle()).isEqualTo("1921 · 1h 8m")
        val progress = PlaybackProgress("f", 20 * minute, 68 * minute)
        assertThat(MediaCard(film, progress = progress).subtitle()).isEqualTo("48m left")
    }

    @Test
    fun `a show's poster gives its seasons`() {
        assertThat(MediaCard(show).subtitle()).isEqualTo("1959 · 5 seasons")
    }

    @Test
    fun `a show with no episodes counted still says it is a series`() {
        assertThat(MediaCard(show.copy(seasonCount = 0, episodeCount = 0)).subtitle()).isEqualTo("1959 · Series")
        assertThat(MediaCard(film.copy(runtimeMs = null)).subtitle()).isEqualTo("1921")
    }

    @Test
    fun `an episode card leads with the episode, then what is left or its name`() {
        val progress = PlaybackProgress(episode.id, 13 * minute, 25 * minute)
        assertThat(MediaCard(show, episode = episode, progress = progress).subtitle()).isEqualTo("S1 E3 · 12m left")
        assertThat(MediaCard(show, episode = episode).subtitle()).isEqualTo("S1 E3 · The Lonely")
    }

    @Test
    fun `VoiceView hears the kind, the episode and how far along`() {
        val progress = PlaybackProgress(episode.id, 5 * minute, 25 * minute)
        assertThat(MediaCard(show, episode = episode, progress = progress, inMyList = true).accessibilityDescription())
            .isEqualTo("Twilight, series, season 1 episode 3, The Lonely, 1959, 20 percent watched, in My List")
        assertThat(MediaCard(show, isWatched = true).accessibilityDescription()).isEqualTo("Twilight, series, 1959, watched")
        assertThat(MediaCard(film).accessibilityDescription()).isEqualTo("The Kid, 1921")
    }

    @Test
    fun `an episode row says whether it can be played and how far along it is`() {
        assertThat(episode.accessibilityDescription(null)).isEqualTo("Episode 3, The Lonely, 25m")
        assertThat(episode.copy(isPlayable = false).accessibilityDescription(null)).isEqualTo("Episode 3, The Lonely, 25m, not available")
        val done = PlaybackProgress(episode.id, 25 * minute, 25 * minute, watched = true)
        assertThat(episode.accessibilityDescription(done)).endsWith(", watched")
        val half = PlaybackProgress(episode.id, 10 * minute, 20 * minute)
        assertThat(episode.copy(name = null, runtimeMs = null).accessibilityDescription(half))
            .isEqualTo("Episode 3, 50 percent watched")
    }
}
