package com.torfilx.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which episode a show plays next, which follows which, and when a show counts as watched.
 *
 * A two-season show with specials is enough to exercise every rule: season boundaries, specials that
 * must never be chained, and an episode with no source in the middle of a season.
 */
class ShowRulesTest {

    private val showId = "show-test-1959"

    private fun episode(season: Int, number: Int, playable: Boolean = true) = Episode(
        id = "$showId-s${season.toString().padStart(2, '0')}e${number.toString().padStart(2, '0')}",
        showId = showId,
        season = season,
        number = number,
        runtimeMs = 25 * MINUTE,
        isPlayable = playable,
    )

    // Deliberately out of order: the rules must sort, not trust the input.
    private val s1 = Season(1, "Season 1", episodes = listOf(episode(1, 3), episode(1, 1), episode(1, 2)))
    private val s2 = Season(2, "Season 2", episodes = listOf(episode(2, 1), episode(2, 2)))
    private val specials = Season(0, "Specials", episodes = listOf(episode(0, 1)))
    private val seasons = listOf(s2, specials, s1)

    private val s1e1 = episode(1, 1)
    private val s1e2 = episode(1, 2)
    private val s1e3 = episode(1, 3)
    private val s2e1 = episode(2, 1)
    private val s2e2 = episode(2, 2)
    private val special = episode(0, 1)

    private fun watched(episode: Episode, at: Long) =
        episode.id to PlaybackProgress(episode.id, 25 * MINUTE, 25 * MINUTE, watched = true, updatedAtMs = at)

    private fun inProgress(episode: Episode, at: Long, position: Long = 10 * MINUTE) =
        episode.id to PlaybackProgress(episode.id, position, 25 * MINUTE, watched = false, updatedAtMs = at)

    // --- Ordering --------------------------------------------------------------------------------

    @Test
    fun `viewing order is seasons then episodes ascending, without specials`() {
        assertThat(ShowPlayRules.viewingOrder(seasons)).containsExactly(s1e1, s1e2, s1e3, s2e1, s2e2).inOrder()
    }

    @Test
    fun `the full list puts specials last`() {
        assertThat(ShowPlayRules.allEpisodes(seasons).last()).isEqualTo(special)
        assertThat(ShowPlayRules.allEpisodes(seasons)).hasSize(6)
    }

    // --- nextUp ----------------------------------------------------------------------------------

    @Test
    fun `a show never watched starts at the first episode`() {
        assertThat(ShowPlayRules.nextUp(seasons, emptyMap())).isEqualTo(s1e1)
    }

    @Test
    fun `an episode left part-way is resumed`() {
        assertThat(ShowPlayRules.nextUp(seasons, mapOf(watched(s1e1, 1), inProgress(s1e2, 2)))).isEqualTo(s1e2)
    }

    @Test
    fun `after finishing an episode the next one is up, across a season boundary`() {
        assertThat(ShowPlayRules.nextUp(seasons, mapOf(watched(s1e1, 1)))).isEqualTo(s1e2)
        assertThat(ShowPlayRules.nextUp(seasons, mapOf(watched(s1e3, 1)))).isEqualTo(s2e1)
    }

    @Test
    fun `the most recent activity wins over an older half-watched episode`() {
        // Left E1 part-way a week ago, then watched E2 through today: carry on from E2.
        val progress = mapOf(inProgress(s1e1, at = 1), watched(s1e2, at = 2))
        assertThat(ShowPlayRules.nextUp(seasons, progress)).isEqualTo(s1e3)
    }

    @Test
    fun `a half-watched episode newer than the last finished one is resumed`() {
        val progress = mapOf(watched(s1e2, at = 1), inProgress(s1e1, at = 2))
        assertThat(ShowPlayRules.nextUp(seasons, progress)).isEqualTo(s1e1)
    }

    @Test
    fun `an unplayable episode is skipped by next-up`() {
        val gap = listOf(
            Season(1, "Season 1", episodes = listOf(s1e1, s1e2.copy(isPlayable = false), s1e3)),
        )
        assertThat(ShowPlayRules.nextUp(gap, mapOf(watched(s1e1, 1)))).isEqualTo(s1e3)
    }

    @Test
    fun `after the last episode, anything skipped earlier comes next`() {
        val progress = mapOf(watched(s1e1, 1), watched(s2e2, 2))
        assertThat(ShowPlayRules.nextUp(seasons, progress)).isEqualTo(s1e2)
    }

    @Test
    fun `when everything is watched the show starts again`() {
        val progress = listOf(s1e1, s1e2, s1e3, s2e1, s2e2).mapIndexed { i, e -> watched(e, i.toLong()) }.toMap()
        assertThat(ShowPlayRules.nextUp(seasons, progress)).isEqualTo(s1e1)
        assertThat(PlayActionResolver.actionForShow(seasons, progress)).isEqualTo(PlayAction.Play(s1e1.id, restart = true))
    }

    @Test
    fun `a season marked watched in one go moves on to the next season`() {
        // Marking a season writes every episode with the same timestamp.
        val progress = listOf(s1e1, s1e2, s1e3).associate { watched(it, at = 7) }
        assertThat(ShowPlayRules.nextUp(seasons, progress)).isEqualTo(s2e1)
    }

    @Test
    fun `specials are never next-up after a regular episode`() {
        val progress = listOf(s1e1, s1e2, s1e3, s2e1).mapIndexed { i, e -> watched(e, i.toLong()) }.toMap()
        assertThat(ShowPlayRules.nextUp(seasons, progress)).isEqualTo(s2e2)
    }

    @Test
    fun `a special left part-way is resumed, because the viewer chose it`() {
        assertThat(ShowPlayRules.nextUp(seasons, mapOf(watched(s1e1, 1), inProgress(special, 2)))).isEqualTo(special)
    }

    @Test
    fun `a show of specials only starts with its first special`() {
        assertThat(ShowPlayRules.nextUp(listOf(specials), emptyMap())).isEqualTo(special)
    }

    @Test
    fun `a show with nothing playable has no next-up and an unavailable action`() {
        val none = listOf(Season(1, "Season 1", episodes = listOf(s1e1.copy(isPlayable = false))))
        assertThat(ShowPlayRules.nextUp(none, emptyMap())).isNull()
        assertThat(PlayActionResolver.actionForShow(none, emptyMap())).isEqualTo(PlayAction.Unavailable)
    }

    @Test
    fun `progress stored under ids that are not this show's episodes is ignored`() {
        val stray = mapOf(
            showId to PlaybackProgress(showId, 10 * MINUTE, 25 * MINUTE, updatedAtMs = 9),
            "catalog-some-film-1921" to PlaybackProgress("catalog-some-film-1921", 1, 2, watched = true),
        )
        assertThat(ShowPlayRules.nextUp(seasons, stray)).isEqualTo(s1e1)
    }

    @Test
    fun `a just-opened episode is not in progress, so the show still starts at its first episode`() {
        val progress = mapOf(inProgress(s1e2, at = 1, position = 5_000))
        assertThat(ShowPlayRules.nextUp(seasons, progress)).isEqualTo(s1e1)
    }

    @Test
    fun `the show's action resumes the next-up episode at its position`() {
        val progress = mapOf(watched(s1e1, 1), inProgress(s1e2, 2, position = 12 * MINUTE))
        assertThat(PlayActionResolver.actionForShow(seasons, progress)).isEqualTo(PlayAction.Resume(s1e2.id, 12 * MINUTE))
    }

    // --- nextAfter -------------------------------------------------------------------------------

    @Test
    fun `the episode after crosses into the next season and ends with the show`() {
        assertThat(ShowPlayRules.nextAfter(seasons, s1e2.id)).isEqualTo(s1e3)
        assertThat(ShowPlayRules.nextAfter(seasons, s1e3.id)).isEqualTo(s2e1)
        assertThat(ShowPlayRules.nextAfter(seasons, s2e2.id)).isNull()
    }

    @Test
    fun `nothing follows a special or an id the show does not have`() {
        assertThat(ShowPlayRules.nextAfter(seasons, special.id)).isNull()
        assertThat(ShowPlayRules.nextAfter(seasons, "nope")).isNull()
    }

    @Test
    fun `the episode after is returned even when it cannot be played, so the player can say so`() {
        val gap = listOf(Season(1, "Season 1", episodes = listOf(s1e1, s1e2.copy(isPlayable = false))))
        assertThat(ShowPlayRules.nextAfter(gap, s1e1.id)?.isPlayable).isFalse()
    }

    @Test
    fun `episodes are found wherever they sit`() {
        assertThat(ShowPlayRules.find(seasons, special.id)).isEqualTo(special)
        assertThat(ShowPlayRules.find(seasons, "nope")).isNull()
    }

    // --- Continue Watching -----------------------------------------------------------------------

    @Test
    fun `a show never touched has no Continue Watching card`() {
        assertThat(ShowPlayRules.continueWatching(seasons, emptyMap())).isNull()
    }

    @Test
    fun `an episode left part-way is the card, with the time it was left`() {
        val progress = mapOf(watched(s1e1, 1), inProgress(s1e2, 5))
        assertThat(ShowPlayRules.continueWatching(seasons, progress))
            .isEqualTo(ContinueWatching.InProgress(s1e2, sortKey = 5))
    }

    @Test
    fun `a finished episode puts the next one up, dated by the finish`() {
        val progress = mapOf(watched(s1e3, 9))
        assertThat(ShowPlayRules.continueWatching(seasons, progress))
            .isEqualTo(ContinueWatching.UpNext(finished = s1e3, next = s2e1, sortKey = 9))
    }

    @Test
    fun `finishing an episode after leaving another part-way puts the next one up`() {
        val progress = mapOf(inProgress(s1e1, 1), watched(s1e2, 2))
        val entry = ShowPlayRules.continueWatching(seasons, progress) as ContinueWatching.UpNext
        assertThat(entry.next).isEqualTo(s1e3)
        assertThat(entry.episode).isEqualTo(s1e3)
    }

    @Test
    fun `the last episode finished leaves no card, even with earlier ones skipped`() {
        assertThat(ShowPlayRules.continueWatching(seasons, mapOf(watched(s1e1, 1), watched(s2e2, 2)))).isNull()
    }

    @Test
    fun `an unplayable next episode is passed over for the one after it`() {
        val gap = listOf(Season(1, "Season 1", episodes = listOf(s1e1, s1e2.copy(isPlayable = false), s1e3)))
        assertThat((ShowPlayRules.continueWatching(gap, mapOf(watched(s1e1, 1))) as ContinueWatching.UpNext).next)
            .isEqualTo(s1e3)
    }

    // --- Watched ---------------------------------------------------------------------------------

    @Test
    fun `a show is watched only when every regular episode is, specials aside`() {
        val allRegular = listOf(s1e1, s1e2, s1e3, s2e1, s2e2).mapIndexed { i, e -> watched(e, i.toLong()) }.toMap()
        assertThat(ShowWatchedRules.isWatched(seasons, allRegular)).isTrue()
        assertThat(ShowWatchedRules.isWatched(seasons, allRegular - s2e2.id)).isFalse()
        assertThat(ShowWatchedRules.isWatched(seasons, emptyMap())).isFalse()
    }

    @Test
    fun `a show with no regular episodes is never watched`() {
        assertThat(ShowWatchedRules.isWatched(listOf(specials), mapOf(watched(special, 1)))).isFalse()
    }

    @Test
    fun `the watched filter treats a half-watched show as unwatched`() {
        val half = mapOf(watched(s1e1, 1))
        assertThat(ShowWatchedRules.matches(WatchedFilter.UNWATCHED, seasons, half)).isTrue()
        assertThat(ShowWatchedRules.matches(WatchedFilter.WATCHED, seasons, half)).isFalse()
        assertThat(ShowWatchedRules.matches(WatchedFilter.ALL, seasons, half)).isTrue()
    }

    // --- Episodes and cards ----------------------------------------------------------------------

    @Test
    fun `an episode without a name is called by its number`() {
        assertThat(s1e3.displayName).isEqualTo("Episode 3")
        assertThat(s1e3.copy(name = "  ").displayName).isEqualTo("Episode 3")
        assertThat(s1e3.copy(name = "The Lonely").displayName).isEqualTo("The Lonely")
        assertThat(s1e3.code).isEqualTo("S1 E3")
        assertThat(Season.defaultName(0)).isEqualTo("Specials")
        assertThat(Season.defaultName(4)).isEqualTo("Season 4")
    }

    @Test
    fun `a show's card plays the episode it carries, with that episode's runtime`() {
        val show = MediaItem(id = showId, title = "Test", kind = MediaKind.SHOW)
        val poster = MediaCard(item = show)
        val continuing = MediaCard(item = show, episode = s1e2.copy(runtimeMs = 22 * MINUTE))

        assertThat(poster.playableId).isEqualTo(showId)
        assertThat(continuing.playableId).isEqualTo(s1e2.id)
        assertThat(continuing.runtimeMs).isEqualTo(22 * MINUTE)
        assertThat(show.isShow).isTrue()
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
