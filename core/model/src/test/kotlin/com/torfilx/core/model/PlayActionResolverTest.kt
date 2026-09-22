package com.torfilx.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayActionResolverTest {

    private val film = MediaItem(id = "catalog-the-kid-1921", title = "The Kid", runtimeMs = 68 * MINUTE)

    private fun progress(positionMs: Long, watched: Boolean = false) =
        PlaybackProgress(film.id, positionMs, durationMs = 68 * MINUTE, watched = watched)

    @Test
    fun `a film never started plays from the beginning`() {
        assertThat(PlayActionResolver.actionFor(film, null)).isEqualTo(PlayAction.Play(film.id, restart = false))
    }

    @Test
    fun `a film in progress resumes at its position`() {
        assertThat(PlayActionResolver.actionFor(film, progress(20 * MINUTE)))
            .isEqualTo(PlayAction.Resume(film.id, 20 * MINUTE))
    }

    @Test
    fun `a watched film plays again from the start`() {
        assertThat(PlayActionResolver.actionFor(film, progress(68 * MINUTE, watched = true)))
            .isEqualTo(PlayAction.Play(film.id, restart = true))
    }

    @Test
    fun `nothing playable is unavailable whatever the progress`() {
        assertThat(PlayActionResolver.actionFor(film, progress(20 * MINUTE), hasPlayableSource = false))
            .isEqualTo(PlayAction.Unavailable)
    }

    @Test
    fun `a card acts on what it plays, with the progress it carries`() {
        val card = MediaCard(item = film, progress = progress(20 * MINUTE))

        assertThat(PlayActionResolver.actionFor(card)).isEqualTo(PlayAction.Resume(card.playableId, 20 * MINUTE))
        assertThat(card.runtimeMs).isEqualTo(film.runtimeMs)
    }

    @Test
    fun `the playable form keys the action on the id it is given`() {
        assertThat(PlayActionResolver.actionForPlayable("episode-id", null))
            .isEqualTo(PlayAction.Play("episode-id", restart = false))
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
