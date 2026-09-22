package com.torfilx.core.player

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.model.EndOfEpisode
import com.torfilx.core.model.Episode
import com.torfilx.core.model.Season
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The run of episodes: the countdown, "still watching?", and nothing starting off screen.
 *
 * Virtual time stands in for the ten-second countdown, so every path is exercised exactly as the
 * player drives it, without a player.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeAutoplayTest {

    private fun episode(season: Int, number: Int, playable: Boolean = true) =
        Episode(id = "s$season-e$number", showId = "show", season = season, number = number, isPlayable = playable)

    private val e1 = episode(1, 1)
    private val e2 = episode(1, 2)
    private val e3 = episode(1, 3)
    private val e4 = episode(1, 4)
    private val e5 = episode(1, 5)
    private val seasons = listOf(Season(1, "Season 1", episodes = listOf(e1, e2, e3, e4, e5)))

    /** Plays the role of the controller: holds the card, records what was played and asked. */
    private class Harness(scope: TestScope) {
        var card: EndCard? = null
        var askedStillWatching = 0
        val played = mutableListOf<Pair<String, Long?>>()
        val warmed = mutableListOf<String>()
        var cooled = 0
        val autoplay = EpisodeAutoplay(
            scope = scope.backgroundScope,
            currentCard = { card },
            setCard = { card = it },
            askStillWatching = { askedStillWatching++ },
            advance = { id, start ->
                card = null
                played += id to start
            },
            warm = { warmed += it.id },
            coolDown = { cooled++ },
        )
    }

    private fun TestScope.harness() = Harness(this)

    /** Lets every countdown run out. The countdown lives in `backgroundScope`, which `advanceUntilIdle` does not wait for. */
    private fun TestScope.idle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    @Test
    fun `the countdown runs ten seconds, then plays the next episode`() = runTest {
        val h = harness()
        h.autoplay.onEpisodeEnded(seasons, e1, autoplay = true, showTitle = "Show")
        runCurrent()
        assertThat(h.card).isEqualTo(EndCard.Countdown(e2, 10))

        advanceTimeBy(5_000)
        runCurrent()
        assertThat(h.card).isEqualTo(EndCard.Countdown(e2, 5))
        assertThat(h.played).isEmpty()

        idle()
        assertThat(h.played).containsExactly(e2.id to null)
        assertThat(h.autoplay.unattendedAutoplays).isEqualTo(1)
    }

    @Test
    fun `Play now skips the rest of the countdown and is not an unattended autoplay`() = runTest {
        val h = harness()
        h.autoplay.onEpisodeEnded(seasons, e1, autoplay = true, showTitle = "Show")
        runCurrent()
        h.autoplay.onUserInput()
        assertThat(h.autoplay.playNext()).isTrue()
        idle()
        assertThat(h.played).containsExactly(e2.id to null)
        assertThat(h.autoplay.unattendedAutoplays).isEqualTo(0)
    }

    @Test
    fun `leaving the app stops the countdown and the card waits for the viewer`() = runTest {
        val h = harness()
        h.autoplay.onEpisodeEnded(seasons, e1, autoplay = true, showTitle = "Show")
        advanceTimeBy(3_000)
        h.autoplay.onBackground()
        assertThat(h.card).isEqualTo(EndCard.Next(e2))

        idle()
        assertThat(h.played).isEmpty()

        h.autoplay.onForeground()
        assertThat(h.autoplay.playNext()).isTrue()
        assertThat(h.played).containsExactly(e2.id to null)
    }

    @Test
    fun `an episode ending off screen only offers the next one`() = runTest {
        val h = harness()
        h.autoplay.onBackground()
        assertThat(h.autoplay.onEpisodeEnded(seasons, e1, autoplay = true, showTitle = "Show"))
            .isEqualTo(EndOfEpisode.OfferNext(e2))
        idle()
        assertThat(h.card).isEqualTo(EndCard.Next(e2))
        assertThat(h.played).isEmpty()
    }

    @Test
    fun `with autoplay off the next episode is offered and nothing starts`() = runTest {
        val h = harness()
        h.autoplay.onEpisodeEnded(seasons, e1, autoplay = false, showTitle = "Show")
        idle()
        assertThat(h.card).isEqualTo(EndCard.Next(e2))
        assertThat(h.played).isEmpty()
    }

    @Test
    fun `three unattended autoplays raise still watching, and yes counts down to the held-back episode`() = runTest {
        val h = harness()
        listOf(e1, e2, e3).forEach { ended ->
            h.autoplay.onEpisodeEnded(seasons, ended, autoplay = true, showTitle = "Show")
            idle()
        }
        assertThat(h.played.map { it.first }).containsExactly(e2.id, e3.id, e4.id).inOrder()
        assertThat(h.autoplay.unattendedAutoplays).isEqualTo(3)

        assertThat(h.autoplay.onEpisodeEnded(seasons, e4, autoplay = true, showTitle = "Show"))
            .isEqualTo(EndOfEpisode.AskStillWatching(e5))
        assertThat(h.askedStillWatching).isEqualTo(1)
        idle()
        assertThat(h.played).hasSize(3)

        assertThat(h.autoplay.resumeHeldBack()).isTrue()
        assertThat(h.autoplay.unattendedAutoplays).isEqualTo(0)
        idle()
        assertThat(h.played.last()).isEqualTo(e5.id to null)
    }

    @Test
    fun `a key press anywhere in the run resets the unattended count`() = runTest {
        val h = harness()
        listOf(e1, e2).forEach { ended ->
            h.autoplay.onEpisodeEnded(seasons, ended, autoplay = true, showTitle = "Show")
            idle()
        }
        h.autoplay.onUserInput()
        h.autoplay.onEpisodeEnded(seasons, e3, autoplay = true, showTitle = "Show")
        idle()
        assertThat(h.askedStillWatching).isEqualTo(0)
        assertThat(h.autoplay.unattendedAutoplays).isEqualTo(1)
    }

    @Test
    fun `nothing held back means still watching resumes the film instead`() = runTest {
        assertThat(harness().autoplay.resumeHeldBack()).isFalse()
    }

    @Test
    fun `the end of the show offers the first episode again, from the start`() = runTest {
        val h = harness()
        h.autoplay.onEpisodeEnded(seasons, e5, autoplay = true, showTitle = "Show")
        assertThat(h.card).isEqualTo(EndCard.EndOfShow("Show", e1))
        assertThat(h.autoplay.playNext()).isFalse()
        assertThat(h.autoplay.watchAgain()).isTrue()
        assertThat(h.played).containsExactly(e1.id to 0L)
    }

    @Test
    fun `a next episode with no source is named and nothing plays`() = runTest {
        val h = harness()
        val gap = listOf(Season(1, "Season 1", episodes = listOf(e1, e2.copy(isPlayable = false))))
        h.autoplay.onEpisodeEnded(gap, e1, autoplay = true, showTitle = "Show")
        idle()
        assertThat(h.card).isEqualTo(EndCard.NextUnavailable(e2.copy(isPlayable = false)))
        assertThat(h.autoplay.playNext()).isFalse()
        assertThat(h.played).isEmpty()
    }

    @Test
    fun `leaving the player ends the run and nothing fires afterwards`() = runTest {
        val h = harness()
        listOf(e1, e2).forEach { ended ->
            h.autoplay.onEpisodeEnded(seasons, ended, autoplay = true, showTitle = "Show")
            idle()
        }
        h.autoplay.onEpisodeEnded(seasons, e3, autoplay = true, showTitle = "Show")
        advanceTimeBy(2_000)
        h.autoplay.reset()
        idle()
        assertThat(h.played.map { it.first }).containsExactly(e2.id, e3.id).inOrder()
        assertThat(h.autoplay.unattendedAutoplays).isEqualTo(0)
    }

    @Test
    fun `a new title opening cancels a countdown but keeps the unattended run`() = runTest {
        val h = harness()
        h.autoplay.onEpisodeEnded(seasons, e1, autoplay = true, showTitle = "Show")
        idle()
        h.autoplay.onEpisodeEnded(seasons, e2, autoplay = true, showTitle = "Show")
        advanceTimeBy(1_000)
        h.autoplay.onOpen()
        idle()
        assertThat(h.played).hasSize(1)
        assertThat(h.autoplay.unattendedAutoplays).isEqualTo(1)
    }

    @Test
    fun `a special never chains`() = runTest {
        val h = harness()
        val withSpecial = seasons + Season(0, "Specials", episodes = listOf(episode(0, 1)))
        h.autoplay.onEpisodeEnded(withSpecial, episode(0, 1), autoplay = true, showTitle = "Show")
        idle()
        assertThat(h.card).isEqualTo(EndCard.BackToShow)
        assertThat(h.played).isEmpty()
    }

    @Test
    fun `a countdown warms the next episode, and the plain card and the end of the show warm nothing`() = runTest {
        val h = harness()
        h.autoplay.onEpisodeEnded(seasons, e1, autoplay = true, showTitle = "Show")
        assertThat(h.warmed).containsExactly(e2.id)

        val off = harness()
        off.autoplay.onEpisodeEnded(seasons, e1, autoplay = false, showTitle = "Show")
        off.autoplay.onEpisodeEnded(seasons, e5, autoplay = true, showTitle = "Show")
        assertThat(off.warmed).isEmpty()
    }

    @Test
    fun `leaving the screen or the player lets the warmed episode go`() = runTest {
        val h = harness()
        h.autoplay.onEpisodeEnded(seasons, e1, autoplay = true, showTitle = "Show")
        runCurrent()
        h.autoplay.onBackground()
        assertThat(h.cooled).isEqualTo(1)
        assertThat(h.card).isEqualTo(EndCard.Next(e2))

        h.autoplay.reset()
        assertThat(h.cooled).isEqualTo(2)
    }

    @Test
    fun `a countdown resumed after still watching warms again`() = runTest {
        val h = harness()
        repeat(3) { h.autoplay.onEpisodeEnded(seasons, e1, autoplay = true, showTitle = "Show"); idle() }
        h.warmed.clear()
        h.autoplay.onEpisodeEnded(seasons, e1, autoplay = true, showTitle = "Show")
        assertThat(h.warmed).isEmpty()

        h.autoplay.resumeHeldBack()
        assertThat(h.warmed).containsExactly(e2.id)
    }
}
