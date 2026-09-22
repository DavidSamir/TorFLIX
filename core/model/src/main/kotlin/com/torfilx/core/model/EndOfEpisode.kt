package com.torfilx.core.model

/**
 * What the player offers when an episode ends.
 *
 * Pure, and decided in one place, because each outcome is a different card with different buttons,
 * and getting one wrong is either an app that plays on through the night or one that strands the
 * viewer on a black frame.
 */
sealed interface EndOfEpisode {
    /** Autoplay: count down, then play [next]. */
    data class Countdown(val next: Episode) : EndOfEpisode

    /** Offer [next] and wait: autoplay is off, or the app is not on screen. */
    data class OfferNext(val next: Episode) : EndOfEpisode

    /** Several episodes have autoplayed with nobody touching the remote: ask before playing [next]. */
    data class AskStillWatching(val next: Episode) : EndOfEpisode

    /** That was the last episode. [first] is where "Watch again" starts, when anything can play. */
    data class EndOfShow(val first: Episode?) : EndOfEpisode

    /** The episode after this one exists but has no source; say so rather than skip past it. */
    data class NextUnavailable(val next: Episode) : EndOfEpisode

    /** A special (never chained), or a show no longer in the catalogue: back to the show. */
    data object BackToShow : EndOfEpisode

    companion object {
        /** Autoplays in a row, without a key press, before the player asks whether anyone is watching. */
        const val MAX_UNATTENDED_AUTOPLAYS = 3

        /**
         * @param seasons the show's seasons now; empty when the show has left the catalogue.
         * @param episodeId the episode that just ended.
         * @param unattendedAutoplays episodes autoplayed in a row with no key pressed.
         * @param onScreen false while the app is in the background: nothing may start by itself then.
         */
        fun decide(
            seasons: List<Season>,
            episodeId: String,
            autoplay: Boolean,
            unattendedAutoplays: Int,
            onScreen: Boolean,
        ): EndOfEpisode {
            val current = ShowPlayRules.find(seasons, episodeId)
            if (current == null || current.isSpecial) return BackToShow
            val next = ShowPlayRules.nextAfter(seasons, episodeId)
                ?: return EndOfShow(ShowPlayRules.viewingOrder(seasons).firstOrNull { it.isPlayable })
            return when {
                !next.isPlayable -> NextUnavailable(next)
                !autoplay || !onScreen -> OfferNext(next)
                unattendedAutoplays >= MAX_UNATTENDED_AUTOPLAYS -> AskStillWatching(next)
                else -> Countdown(next)
            }
        }
    }
}
