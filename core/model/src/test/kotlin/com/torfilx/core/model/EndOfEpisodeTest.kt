package com.torfilx.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Every card the player can show when an episode ends. */
class EndOfEpisodeTest {

    private fun episode(season: Int, number: Int, playable: Boolean = true) =
        Episode(id = "s$season-e$number", showId = "show", season = season, number = number, isPlayable = playable)

    private val seasons = listOf(
        Season(1, "Season 1", episodes = listOf(episode(1, 1), episode(1, 2), episode(1, 3, playable = false), episode(1, 4))),
        Season(2, "Season 2", episodes = listOf(episode(2, 1))),
        Season(0, "Specials", episodes = listOf(episode(0, 1))),
    )

    private fun decide(id: String, autoplay: Boolean = true, unattended: Int = 0, onScreen: Boolean = true) =
        EndOfEpisode.decide(seasons, id, autoplay, unattended, onScreen)

    @Test
    fun `with autoplay on the next episode counts down`() {
        assertThat(decide("s1-e1")).isEqualTo(EndOfEpisode.Countdown(episode(1, 2)))
    }

    @Test
    fun `the countdown crosses into the next season`() {
        assertThat(decide("s1-e4")).isEqualTo(EndOfEpisode.Countdown(episode(2, 1)))
    }

    @Test
    fun `with autoplay off the next episode is offered and waits`() {
        assertThat(decide("s1-e1", autoplay = false)).isEqualTo(EndOfEpisode.OfferNext(episode(1, 2)))
    }

    @Test
    fun `nothing starts by itself while the app is off screen`() {
        assertThat(decide("s1-e1", onScreen = false)).isEqualTo(EndOfEpisode.OfferNext(episode(1, 2)))
    }

    @Test
    fun `after three unattended autoplays the player asks whether anyone is watching`() {
        assertThat(decide("s1-e1", unattended = 2)).isEqualTo(EndOfEpisode.Countdown(episode(1, 2)))
        assertThat(decide("s1-e1", unattended = 3)).isEqualTo(EndOfEpisode.AskStillWatching(episode(1, 2)))
    }

    @Test
    fun `a next episode with no source is named, not skipped`() {
        assertThat(decide("s1-e2")).isEqualTo(EndOfEpisode.NextUnavailable(episode(1, 3, playable = false)))
    }

    @Test
    fun `the last episode ends the show and offers the first to watch again`() {
        assertThat(decide("s2-e1")).isEqualTo(EndOfEpisode.EndOfShow(episode(1, 1)))
    }

    @Test
    fun `a special never chains, and neither does a show that has left the catalogue`() {
        assertThat(decide("s0-e1")).isEqualTo(EndOfEpisode.BackToShow)
        assertThat(EndOfEpisode.decide(emptyList(), "s1-e1", true, 0, true)).isEqualTo(EndOfEpisode.BackToShow)
    }
}
