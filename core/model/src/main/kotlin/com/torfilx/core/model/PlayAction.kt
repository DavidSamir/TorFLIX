package com.torfilx.core.model

/**
 * What the primary button on a details screen (or a card) does.
 *
 * Derived from local progress rather than from a stored flag, so the label is correct the instant
 * the viewer returns from the player.
 */
sealed interface PlayAction {
    /** Nothing playable (e.g. a catalogue entry whose magnets were all invalid). */
    data object Unavailable : PlayAction

    /** Start from the beginning; [restart] is true when the film was already watched. */
    data class Play(val itemId: String, val restart: Boolean) : PlayAction

    /** Continue from [positionMs]. */
    data class Resume(val itemId: String, val positionMs: Long) : PlayAction
}

/** Chooses the primary action for a film. */
object PlayActionResolver {

    fun actionFor(item: MediaItem, progress: PlaybackProgress?, hasPlayableSource: Boolean = true): PlayAction =
        actionForPlayable(item.id, progress, hasPlayableSource)

    /**
     * The action for whatever [card] plays: the film, or — for a show's card in Continue Watching —
     * the episode it carries. Acting on `card.item.id` there would try to play the show itself.
     */
    fun actionFor(card: MediaCard, hasPlayableSource: Boolean = true): PlayAction =
        actionForPlayable(card.playableId, card.progress, hasPlayableSource)

    /**
     * The action for a show's primary button: resume, play or play again its next-up episode
     * ([ShowPlayRules.nextUp]). Unavailable when no episode can be played.
     */
    fun actionForShow(seasons: List<Season>, progress: Map<String, PlaybackProgress>): PlayAction {
        val next = ShowPlayRules.nextUp(seasons, progress) ?: return PlayAction.Unavailable
        return actionForPlayable(next.id, progress[next.id])
    }

    /** The action for one playable id (a film or an episode) given its stored progress. */
    fun actionForPlayable(
        playableId: String,
        progress: PlaybackProgress?,
        hasPlayableSource: Boolean = true,
    ): PlayAction {
        if (!hasPlayableSource) return PlayAction.Unavailable
        return when {
            ResumeRules.isInProgress(progress) ->
                PlayAction.Resume(playableId, ResumeRules.resumePositionMs(progress))

            progress != null && ResumeRules.isWatched(progress) ->
                PlayAction.Play(playableId, restart = true)

            else -> PlayAction.Play(playableId, restart = false)
        }
    }
}
