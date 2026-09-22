package com.torfilx.core.player

import com.torfilx.core.model.EndOfEpisode
import com.torfilx.core.model.Episode
import com.torfilx.core.model.Season
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What happens between one episode and the next: the end card, its countdown, "are you still
 * watching?", and never starting anything while the app is off screen.
 *
 * Kept apart from [PlaybackController] so it can be tested with virtual time and no player. The
 * controller owns the state it draws ([currentCard], [setCard]) and does the actual playing
 * ([advance]); this decides when.
 *
 * @param advance plays another episode in place; `startPositionMs` null resumes from what is stored.
 * @param warm a countdown to this episode has started: its torrent may be fetched ahead of time.
 * @param coolDown nothing is counting down any more for a viewer who is there (they left the player,
 *   or the app left the screen): anything fetched ahead of time is let go.
 */
internal class EpisodeAutoplay(
    private val scope: CoroutineScope,
    private val currentCard: () -> EndCard?,
    private val setCard: (EndCard?) -> Unit,
    private val askStillWatching: () -> Unit,
    private val advance: (playableId: String, startPositionMs: Long?) -> Unit,
    private val countdownSeconds: Int = COUNTDOWN_SECONDS,
    private val warm: (Episode) -> Unit = {},
    private val coolDown: () -> Unit = {},
) {

    /** False while the app is in the background: nothing may start by itself then. */
    @Volatile
    var onScreen: Boolean = true
        private set

    /** Episodes autoplayed in a row with no key pressed. */
    @Volatile
    var unattendedAutoplays: Int = 0
        private set

    private var countdownJob: Job? = null

    /** The episode "still watching?" is holding back until someone answers. */
    private var heldBack: Episode? = null

    /** Decides and shows what follows [episode], which just ended. */
    fun onEpisodeEnded(seasons: List<Season>, episode: Episode, autoplay: Boolean, showTitle: String): EndOfEpisode {
        val decision = EndOfEpisode.decide(seasons, episode.id, autoplay, unattendedAutoplays, onScreen)
        when (decision) {
            is EndOfEpisode.Countdown -> startCountdown(decision.next)
            is EndOfEpisode.OfferNext -> setCard(EndCard.Next(decision.next))
            is EndOfEpisode.AskStillWatching -> {
                heldBack = decision.next
                askStillWatching()
            }
            is EndOfEpisode.EndOfShow -> setCard(EndCard.EndOfShow(showTitle, decision.first))
            is EndOfEpisode.NextUnavailable -> setCard(EndCard.NextUnavailable(decision.next))
            EndOfEpisode.BackToShow -> setCard(EndCard.BackToShow)
        }
        return decision
    }

    /** Any key: someone is there, so the run of unattended autoplays is over. */
    fun onUserInput() {
        unattendedAutoplays = 0
    }

    /**
     * "Still watching?" was answered yes: count down to the episode it held back — or, off screen,
     * offer it and wait.
     *
     * @return false when nothing was held back (a film paused by the idle prompt), so the caller
     *   resumes that instead.
     */
    fun resumeHeldBack(): Boolean {
        val next = heldBack ?: return false
        heldBack = null
        unattendedAutoplays = 0
        if (onScreen) startCountdown(next) else setCard(EndCard.Next(next))
        return true
    }

    /** "Play now" or "Play": the card's next episode, at once. */
    fun playNext(): Boolean {
        val next = when (val card = currentCard()) {
            is EndCard.Countdown -> card.next
            is EndCard.Next -> card.next
            else -> null
        } ?: return false
        cancelCountdown()
        advance(next.id, null)
        return true
    }

    /** "Watch again" at the end of a show: its first episode, from the very start. */
    fun watchAgain(): Boolean {
        val first = (currentCard() as? EndCard.EndOfShow)?.first ?: return false
        cancelCountdown()
        advance(first.id, 0L)
        return true
    }

    /** The app lost the screen: a countdown stops and becomes a card that waits for the viewer. */
    fun onBackground() {
        onScreen = false
        coolDown()
        (currentCard() as? EndCard.Countdown)?.let { card ->
            cancelCountdown()
            setCard(EndCard.Next(card.next))
        }
    }

    fun onForeground() {
        onScreen = true
    }

    /** A title is opening: nothing counts down and nothing is held back. The unattended run continues. */
    fun onOpen() {
        cancelCountdown()
        heldBack = null
    }

    /** The viewer left the player: the run of autoplays is over. */
    fun reset() {
        cancelCountdown()
        coolDown()
        heldBack = null
        unattendedAutoplays = 0
    }

    private fun startCountdown(next: Episode) {
        cancelCountdown()
        warm(next)
        countdownJob = scope.launch {
            for (seconds in countdownSeconds downTo 1) {
                setCard(EndCard.Countdown(next, seconds))
                delay(MS_PER_SECOND)
            }
            countdownJob = null
            // The app can leave the screen in the instant between the last tick and here.
            if (!onScreen) {
                setCard(EndCard.Next(next))
                return@launch
            }
            unattendedAutoplays++
            advance(next.id, null)
        }
    }

    private fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
    }

    companion object {
        /** How long the end card counts down before the next episode starts by itself. */
        const val COUNTDOWN_SECONDS = 10
        private const val MS_PER_SECOND = 1_000L
    }
}
