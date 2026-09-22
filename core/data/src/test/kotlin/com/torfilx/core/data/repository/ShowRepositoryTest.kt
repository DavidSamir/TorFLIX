package com.torfilx.core.data.repository

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogIds
import com.torfilx.core.catalogue.format.CatalogMagnetDto
import com.torfilx.core.catalogue.testing.TestCatalogues
import com.torfilx.core.data.catalog.FakeCatalogAssetSource
import com.torfilx.core.data.catalog.FetchedCatalogStore
import com.torfilx.core.data.catalog.LayeredCatalog
import com.torfilx.core.data.catalog.layeredCatalog
import com.torfilx.core.data.database.ProgressEntity
import com.torfilx.core.model.HomeRowKind
import com.torfilx.core.model.LibraryQuery
import com.torfilx.core.model.MediaCard
import com.torfilx.core.model.MediaKind
import com.torfilx.core.model.PlayAction
import com.torfilx.core.model.WatchedFilter
import com.torfilx.core.testing.FakeMyListDao
import com.torfilx.core.testing.FakeProgressDao
import com.torfilx.core.testing.FakeShowStateDao
import com.torfilx.core.testing.FakeSearchHistoryDao
import com.torfilx.core.testing.FakeTimeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Shows in the library, on Home, in Continue Watching, and on a card's Play button.
 *
 * The catalogue is two films and one show — two seasons of three episodes plus one special — which
 * is the smallest library that exercises every place films and shows differ.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShowRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val entries: List<CatalogEntryDto> = CatalogIds.pin(
        TestCatalogues.entries(2).map { it.copy(id = null, genres = listOf("Drama")) } +
            TestCatalogues.show(title = "Twilight", seasons = 2, episodesPerSeason = 3, withSpecials = true, genres = listOf("Drama")) +
            // An unplayable film: its only magnet is malformed.
            CatalogEntryDto(title = "Broken", year = "1930", magnets = listOf(CatalogMagnetDto(magnet = "magnet:?xt=urn:btih:nope"))),
    )
    private val film = entries[0].id!!
    private val brokenFilm = entries[3].id!!
    private val show = entries[2]
    private val showId = show.id!!
    private val s1 = show.seasons.first { it.number == 1 }.episodes.map { it.id!! }
    private val s2 = show.seasons.first { it.number == 2 }.episodes.map { it.id!! }
    private val special = show.seasons.first { it.number == 0 }.episodes.single().id!!

    private class Fixture(
        val catalog: LayeredCatalog,
        val progressDao: FakeProgressDao,
        val progress: ProgressRepository,
        val myList: MyListRepository,
        val media: MediaRepository,
        val time: FakeTimeProvider,
        val states: FakeShowStateDao,
    )

    private fun TestScope.fixture(catalogue: List<CatalogEntryDto> = entries): Fixture {
        val catalog = layeredCatalog(FakeCatalogAssetSource.bundled(5, catalogue), FetchedCatalogStore { File(tmp.root, "c") })
        catalog.preload()
        val time = FakeTimeProvider()
        val dao = FakeProgressDao()
        val states = FakeShowStateDao()
        val progress = ProgressRepository(dao, catalog, time, states)
        val myList = MyListRepository(FakeMyListDao(), time)
        val media = MediaRepository(catalog, FakeSearchHistoryDao(), progress, myList, time, UnconfinedTestDispatcher(testScheduler))
        return Fixture(catalog, dao, progress, myList, media, time, states)
    }

    private fun row(id: String, position: Long, duration: Long = 25 * MINUTE, watched: Boolean = false, at: Long) =
        ProgressEntity(id, positionMs = position, durationMs = duration, watched = watched, updatedAtMs = at)

    // --- Library ---------------------------------------------------------------------------------

    @Test
    fun `the Shows grid holds only shows and the Movies grid only films`() = runTest {
        val f = fixture()
        val shows = f.media.observeLibrary(LibraryQuery(kind = MediaKind.SHOW)).first()
        val films = f.media.observeLibrary(LibraryQuery(kind = MediaKind.MOVIE)).first()
        val all = f.media.observeLibrary(LibraryQuery()).first()

        assertThat(shows.map { it.item.id }).containsExactly(showId)
        assertThat(films.map { it.item.kind }.toSet()).containsExactly(MediaKind.MOVIE)
        assertThat(films).hasSize(3)
        assertThat(all).hasSize(4)
    }

    @Test
    fun `a show card carries no progress, but knows when the whole show is watched`() = runTest {
        val f = fixture()
        f.progressDao.rows.value = (s1 + s2).mapIndexed { i, id -> row(id, 25 * MINUTE, watched = true, at = i.toLong()) }

        val card = f.media.observeLibrary(LibraryQuery(kind = MediaKind.SHOW)).first().single()
        assertThat(card.progress).isNull()
        assertThat(card.isWatched).isTrue()
        assertThat(f.media.observeLibrary(LibraryQuery(kind = MediaKind.SHOW, watched = WatchedFilter.WATCHED)).first()).hasSize(1)
        assertThat(f.media.observeLibrary(LibraryQuery(kind = MediaKind.SHOW, watched = WatchedFilter.UNWATCHED)).first()).isEmpty()
    }

    @Test
    fun `a half-watched show is unwatched, as a half-watched film is`() = runTest {
        val f = fixture()
        f.progressDao.rows.value = listOf(row(s1[0], 25 * MINUTE, watched = true, at = 1))
        assertThat(f.media.observeLibrary(LibraryQuery(kind = MediaKind.SHOW, watched = WatchedFilter.UNWATCHED)).first()).hasSize(1)
        assertThat(f.media.observeLibrary(LibraryQuery(kind = MediaKind.SHOW, watched = WatchedFilter.WATCHED)).first()).isEmpty()
    }

    @Test
    fun `genres can be asked for one kind only`() = runTest {
        val f = fixture(
            CatalogIds.pin(
                TestCatalogues.entries(1).map { it.copy(id = null, genres = listOf("Comedy")) } +
                    TestCatalogues.show(genres = listOf("Sci-Fi")),
            ),
        )
        assertThat(f.media.genres()).containsExactly("Comedy", "Sci-Fi")
        assertThat(f.media.genres(MediaKind.SHOW)).containsExactly("Sci-Fi")
        assertThat(f.media.genres(MediaKind.MOVIE)).containsExactly("Comedy")
    }

    // --- Home ------------------------------------------------------------------------------------

    @Test
    fun `Home has a TV shows row before Recently added, and capped rows say where the rest are`() = runTest {
        val f = fixture()
        val rows = f.media.observeHome().first()

        assertThat(rows.map { it.id }.take(2)).containsExactly(MediaRepository.ROW_SHOWS, MediaRepository.ROW_CATALOG).inOrder()
        val showsRow = rows.first { it.id == MediaRepository.ROW_SHOWS }
        assertThat(showsRow.kind).isEqualTo(HomeRowKind.SHOWS)
        assertThat(showsRow.items.map { it.item.id }).containsExactly(showId)
        assertThat(showsRow.seeAllIn).isEqualTo("Shows")
        assertThat(rows.first { it.id == MediaRepository.ROW_CATALOG }.seeAllIn).isEqualTo("Movies and Shows")
        assertThat(rows.first { it.id == "genre-Drama" }.seeAllIn).isEqualTo("Movies and Shows")
    }

    @Test
    fun `a films-only catalogue has no TV shows row and points everything at Movies`() = runTest {
        val f = fixture(TestCatalogues.entries(3))
        val rows = f.media.observeHome().first()
        assertThat(rows.map { it.id }).doesNotContain(MediaRepository.ROW_SHOWS)
        assertThat(rows.map { it.seeAllIn }.toSet()).containsExactly("Movies")
    }

    // --- Continue Watching -----------------------------------------------------------------------

    @Test
    fun `Continue Watching holds one card per show, for its most recent episode`() = runTest {
        val f = fixture()
        f.progressDao.rows.value = listOf(
            row(s1[0], 10 * MINUTE, at = 1),
            row(film, 30 * MINUTE, duration = 90 * MINUTE, at = 2),
            row(s1[1], 12 * MINUTE, at = 3),
        )

        f.progress.observeContinueWatching().test {
            val cards = awaitItem()
            assertThat(cards.map { it.playableId }).containsExactly(s1[1], film).inOrder()
            val episodeCard = cards.first()
            assertThat(episodeCard.item.id).isEqualTo(showId)
            assertThat(episodeCard.episode?.id).isEqualTo(s1[1])
            assertThat(episodeCard.progress?.positionMs).isEqualTo(12 * MINUTE)

            // Removing the card removes that episode's progress only; the other one surfaces.
            f.progress.remove(episodeCard.playableId)
            assertThat(awaitItem().map { it.playableId }).containsExactly(film, s1[0]).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a progress row under a show's own id never reaches Continue Watching`() = runTest {
        val f = fixture()
        f.progressDao.rows.value = listOf(row(showId, 10 * MINUTE, at = 9))
        assertThat(f.progress.observeContinueWatching().first()).isEmpty()
    }

    private fun finished(id: String, at: Long) = row(id, 25 * MINUTE, watched = true, at = at)

    private suspend fun Fixture.continueWatching() = progress.observeContinueWatching().first()

    @Test
    fun `finishing an episode puts the next one up, as a card with no bar`() = runTest {
        val f = fixture()
        f.progressDao.rows.value = listOf(finished(s1[0], at = 5))

        val card = f.continueWatching().single()
        assertThat(card.item.id).isEqualTo(showId)
        assertThat(card.episode?.id).isEqualTo(s1[1])
        assertThat(card.progress).isNull()
        assertThat(card.playableId).isEqualTo(s1[1])
        assertThat(f.media.playAction(card)).isEqualTo(PlayAction.Play(s1[1], restart = false))
    }

    @Test
    fun `the up-next card crosses seasons and sits in the row by when the episode was finished`() = runTest {
        val f = fixture()
        f.progressDao.rows.value = listOf(finished(s1[2], at = 3), row(film, 30 * MINUTE, duration = 90 * MINUTE, at = 2))
        assertThat(f.continueWatching().map { it.playableId }).containsExactly(s2[0], film).inOrder()

        f.progressDao.rows.value = listOf(finished(s1[2], at = 3), row(film, 30 * MINUTE, duration = 90 * MINUTE, at = 4))
        assertThat(f.continueWatching().map { it.playableId }).containsExactly(film, s2[0]).inOrder()
    }

    @Test
    fun `a show finished to its last episode leaves Continue Watching`() = runTest {
        val f = fixture()
        f.progressDao.rows.value = listOf(finished(s1[0], at = 1), finished(s2[2], at = 2))
        assertThat(f.continueWatching()).isEmpty()
    }

    @Test
    fun `a show marked watched leaves Continue Watching, and a season marked watched puts the next one up`() = runTest {
        val f = fixture()
        f.progress.markWatched(showId, durationMs = null, watched = true)
        assertThat(f.continueWatching()).isEmpty()

        f.progress.markWatched(showId, durationMs = null, watched = false)
        f.progress.markSeasonWatched(showId, season = 1, watched = true)
        assertThat(f.continueWatching().single().episode?.id).isEqualTo(s2[0])
    }

    @Test
    fun `removing an up-next card keeps the progress and hides the card until another episode is finished`() = runTest {
        val f = fixture()
        f.progressDao.rows.value = listOf(finished(s1[0], at = 5))

        f.progress.remove(f.continueWatching().single().playableId)
        assertThat(f.continueWatching()).isEmpty()
        assertThat(f.progressDao.rows.value.single().itemId).isEqualTo(s1[0])
        assertThat(f.states.states.value.single().dismissedAfterEpisodeId).isEqualTo(s1[0])

        f.time.advance(MINUTE)
        f.progress.save(s1[1], positionMs = 25 * MINUTE, durationMs = 25 * MINUTE)
        assertThat(f.continueWatching().single().episode?.id).isEqualTo(s1[2])
    }

    @Test
    fun `finishing the same episode again after a dismissal brings the card back`() = runTest {
        val f = fixture()
        f.progressDao.rows.value = listOf(finished(s1[0], at = 5))
        f.progress.remove(s1[1])
        assertThat(f.continueWatching()).isEmpty()

        f.time.advance(MINUTE)
        f.progress.save(s1[0], positionMs = 25 * MINUTE, durationMs = 25 * MINUTE)
        assertThat(f.continueWatching().single().episode?.id).isEqualTo(s1[1])
    }

    @Test
    fun `starting an episode after a dismissal brings the show back with its progress`() = runTest {
        val f = fixture()
        f.progressDao.rows.value = listOf(finished(s1[0], at = 5))
        f.progress.remove(s1[1])

        f.time.advance(MINUTE)
        f.progress.save(s1[2], positionMs = 10 * MINUTE, durationMs = 25 * MINUTE)
        val card = f.continueWatching().single()
        assertThat(card.episode?.id).isEqualTo(s1[2])
        assertThat(card.progress?.positionMs).isEqualTo(10 * MINUTE)
    }

    @Test
    fun `removing a part-watched episode's card does not swap in an up-next card`() = runTest {
        val f = fixture()
        f.progressDao.rows.value = listOf(finished(s1[0], at = 1), row(s1[1], 12 * MINUTE, at = 2))
        assertThat(f.continueWatching().single().progress?.positionMs).isEqualTo(12 * MINUTE)

        f.progress.remove(s1[1])
        assertThat(f.continueWatching()).isEmpty()
        assertThat(f.progressDao.rows.value.map { it.itemId }).containsExactly(s1[0])
    }

    @Test
    fun `a dismissal for one show leaves another show's card and films alone`() = runTest {
        val f = fixture()
        f.progressDao.rows.value = listOf(finished(s1[0], at = 5), row(film, 30 * MINUTE, duration = 90 * MINUTE, at = 4))
        f.progress.remove(s1[1])
        assertThat(f.continueWatching().map { it.playableId }).containsExactly(film)
    }

    @Test
    fun `finishing a special puts nothing up next`() = runTest {
        val f = fixture()
        f.progressDao.rows.value = listOf(finished(special, at = 5))
        assertThat(f.continueWatching()).isEmpty()
    }

    // --- Marking watched -------------------------------------------------------------------------

    @Test
    fun `marking a show watched marks every regular episode and leaves specials alone`() = runTest {
        val f = fixture()
        f.progress.markWatched(showId, durationMs = null, watched = true)

        val rows = f.progressDao.rows.value.associateBy { it.itemId }
        assertThat(rows.keys).containsExactlyElementsIn(s1 + s2)
        assertThat(rows.values.all { it.watched && it.positionMs == 25 * MINUTE && it.durationMs == 25 * MINUTE }).isTrue()
        assertThat(rows).doesNotContainKey(showId)
        assertThat(rows).doesNotContainKey(special)
    }

    @Test
    fun `marking a show unwatched clears its episodes and nothing else`() = runTest {
        val f = fixture()
        f.progressDao.rows.value = listOf(row(s1[0], 25 * MINUTE, watched = true, at = 1), row(film, 5, at = 2), row(special, 3, at = 3))

        f.progress.markWatched(showId, durationMs = null, watched = false)

        assertThat(f.progressDao.rows.value.map { it.itemId }).containsExactly(film, special)
    }

    @Test
    fun `a season can be marked watched and unwatched on its own`() = runTest {
        val f = fixture()
        f.progress.markSeasonWatched(showId, season = 2, watched = true)
        assertThat(f.progressDao.rows.value.map { it.itemId }).containsExactlyElementsIn(s2)

        f.progress.markSeasonWatched(showId, season = 2, watched = false)
        assertThat(f.progressDao.rows.value).isEmpty()

        f.progress.markSeasonWatched(showId, season = 9, watched = true)
        assertThat(f.progressDao.rows.value).isEmpty()
    }

    @Test
    fun `a single episode is marked watched like a film`() = runTest {
        val f = fixture()
        f.progress.markWatched(s1[2], durationMs = 25 * MINUTE, watched = true)
        assertThat(f.progressDao.rows.value.single().let { it.itemId to it.watched }).isEqualTo(s1[2] to true)
    }

    // --- Playing from a card ---------------------------------------------------------------------

    @Test
    fun `Play on a show card plays its next-up episode`() = runTest {
        val f = fixture()
        val showCard = MediaCard(item = f.catalog.item(showId)!!.item)
        f.progressDao.rows.value = listOf(row(s1[0], 25 * MINUTE, watched = true, at = 1))

        assertThat(f.media.playAction(showCard)).isEqualTo(PlayAction.Play(s1[1], restart = false))

        f.progressDao.rows.value = listOf(row(s1[0], 25 * MINUTE, watched = true, at = 1), row(s1[1], 8 * MINUTE, at = 2))
        assertThat(f.media.playAction(showCard)).isEqualTo(PlayAction.Resume(s1[1], 8 * MINUTE))
    }

    @Test
    fun `Play on an episode card plays that episode, and on a film card the film`() = runTest {
        val f = fixture()
        val showItem = f.catalog.item(showId)!!.item
        val episode = f.catalog.seasons(showId).first().episodes[2]
        f.progressDao.rows.value = listOf(row(episode.id, 9 * MINUTE, at = 1))

        assertThat(f.media.playAction(MediaCard(item = showItem, episode = episode)))
            .isEqualTo(PlayAction.Resume(episode.id, 9 * MINUTE))
        assertThat(f.media.playAction(MediaCard(item = f.catalog.item(film)!!.item)))
            .isEqualTo(PlayAction.Play(film, restart = false))
    }

    @Test
    fun `a film with no usable source is unavailable rather than a player that can only fail`() = runTest {
        val f = fixture()
        assertThat(f.media.playAction(MediaCard(item = f.catalog.item(brokenFilm)!!.item))).isEqualTo(PlayAction.Unavailable)
    }

    @Test
    fun `a show card on the hero gains its next-up episode and that episode's action`() = runTest {
        val f = fixture()
        val progress = mapOf(s1[0] to com.torfilx.core.model.PlaybackProgress(s1[0], 25 * MINUTE, 25 * MINUTE, watched = true, updatedAtMs = 1))

        val hero = f.media.heroItem(MediaCard(item = f.catalog.item(showId)!!.item), progress)

        assertThat(hero.card.episode?.id).isEqualTo(s1[1])
        assertThat(hero.card.playableId).isEqualTo(s1[1])
        assertThat(hero.action).isEqualTo(PlayAction.Play(s1[1], restart = false))
    }

    @Test
    fun `a show's seasons are observed with the catalogue`() = runTest {
        val f = fixture()
        val seasons = f.media.observeSeasons(showId).first()
        assertThat(seasons.map { it.number }).containsExactly(1, 2, 0).inOrder()
        assertThat(f.media.observeSeasons(film).first()).isEmpty()
        assertThat(f.media.playable(s2[0])?.id).isEqualTo(s2[0])
        assertThat(f.media.playable(showId)).isNull()
    }

    private companion object {
        const val MINUTE = 60_000L
    }

    // --- Search ----------------------------------------------------------------------------------

    @Test
    fun `an episode's name finds its show, and the result says which episode`() = runTest {
        val f = fixture()
        val results = f.media.search("of Season 2")

        val result = results.single()
        assertThat(result.card.item.id).isEqualTo(showId)
        assertThat(result.card.episode?.id).isEqualTo(s2[0])
        assertThat(result.matchedOn).isEqualTo("episode")
    }

    @Test
    fun `a show found by its title is not listed again for its episodes`() = runTest {
        val f = fixture(
            CatalogIds.pin(listOf(TestCatalogues.show(title = "Episode Guide", seasons = 1, episodesPerSeason = 2))),
        )
        val results = f.media.search("episode")
        assertThat(results.map { it.matchedOn }).containsExactly("title")
        assertThat(results.single().card.episode).isNull()
    }

    @Test
    fun `a query shorter than three characters searches titles only`() = runTest {
        val f = fixture()
        assertThat(f.media.search("of").none { it.matchedOn == "episode" }).isTrue()
    }
}
