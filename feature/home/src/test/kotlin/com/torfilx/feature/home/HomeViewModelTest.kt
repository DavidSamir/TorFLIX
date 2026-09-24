package com.torfilx.feature.home

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogIds
import com.torfilx.core.catalogue.testing.TestCatalogues
import com.torfilx.core.common.network.NetworkMonitor
import com.torfilx.core.data.database.ProgressEntity
import com.torfilx.core.data.repository.MediaRepository
import com.torfilx.core.data.repository.MyListRepository
import com.torfilx.core.data.repository.ProgressRepository
import com.torfilx.core.model.Episode
import com.torfilx.core.model.HeroItem
import com.torfilx.core.model.HomeRowKind
import com.torfilx.core.model.MediaCard
import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.MediaKind
import com.torfilx.core.model.PlayAction
import com.torfilx.core.testing.FakeMyListDao
import com.torfilx.core.testing.FakeProgressDao
import com.torfilx.core.testing.FakeShowStateDao
import com.torfilx.core.testing.FakeSearchHistoryDao
import com.torfilx.core.testing.FakeTimeProvider
import com.torfilx.core.testing.MainDispatcherRule
import com.torfilx.core.testing.inMemoryCatalog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Home with shows in the catalogue: its rows, its hero, and what Play on a card does. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val entries: List<CatalogEntryDto> = CatalogIds.pin(
        TestCatalogues.entries(2).map { it.copy(id = null) } + TestCatalogues.show(title = "Twilight"),
    )
    private val filmId = entries[0].id!!
    private val showId = entries[2].id!!
    private val s1 = entries[2].seasons.first { it.number == 1 }.episodes.map { it.id!! }

    private val progressDao = FakeProgressDao()

    private fun viewModel(): HomeViewModel {
        val catalog = inMemoryCatalog(entries, tmp.root)
        val time = FakeTimeProvider()
        val progress = ProgressRepository(progressDao, catalog, time, FakeShowStateDao())
        val myList = MyListRepository(FakeMyListDao(), time)
        return HomeViewModel(
            mediaRepository = MediaRepository(catalog, FakeSearchHistoryDao(), progress, myList, time, main.dispatcher),
            progressRepository = progress,
            myListRepository = myList,
            networkMonitor = object : NetworkMonitor {
                override val isOnline = flowOf(true)
            },
        )
    }

    private suspend fun HomeViewModel.content(): HomeUiState.Content =
        uiState.first { it is HomeUiState.Content } as HomeUiState.Content

    @Test
    fun `with nothing watched, the hero is the catalogue, and a show on it plays its first episode`() = runTest {
        val content = viewModel().content()

        assertThat(content.rows.map { it.kind }).contains(HomeRowKind.SHOWS)
        val showHero = content.hero.first { it.card.item.id == showId }
        assertThat(showHero.card.episode?.id).isEqualTo(s1[0])
        assertThat(showHero.action).isEqualTo(PlayAction.Play(s1[0], restart = false))
        assertThat(heroLabel(showHero)).isEqualTo("Play S1 E1")
    }

    @Test
    fun `an episode left part-way leads the hero and resumes where it stopped`() = runTest {
        progressDao.rows.value = listOf(ProgressEntity(s1[1], 10 * MINUTE, 25 * MINUTE, false, 5))
        val vm = viewModel()
        val content = vm.content()

        val continueRow = content.rows.first { it.kind == HomeRowKind.CONTINUE_WATCHING }
        assertThat(continueRow.items.single().playableId).isEqualTo(s1[1])
        val hero = content.hero.first()
        assertThat(hero.action).isEqualTo(PlayAction.Resume(s1[1], 10 * MINUTE))
        assertThat(heroLabel(hero)).isEqualTo("Resume S1 E2")

        // Play on the Continue Watching card plays that episode.
        assertThat(vm.playActionFor(continueRow.items.single())).isEqualTo(PlayAction.Resume(s1[1], 10 * MINUTE))
    }

    @Test
    fun `Play on a show's poster plays its next-up episode, never the show itself`() = runTest {
        progressDao.rows.value = listOf(ProgressEntity(s1[0], 25 * MINUTE, 25 * MINUTE, true, 5))
        val vm = viewModel()
        val poster = vm.content().rows.first { it.kind == HomeRowKind.SHOWS }.items.single()
        assertThat(poster.episode).isNull()
        assertThat(vm.playActionFor(poster)).isEqualTo(PlayAction.Play(s1[1], restart = false))
    }

    @Test
    fun `removing a show's Continue Watching card removes that episode's progress only`() = runTest {
        progressDao.rows.value = listOf(
            ProgressEntity(s1[0], 10 * MINUTE, 25 * MINUTE, false, 5),
            ProgressEntity(filmId, 10 * MINUTE, 90 * MINUTE, false, 6),
        )
        val vm = viewModel()
        val episodeCard = vm.content().rows.first { it.kind == HomeRowKind.CONTINUE_WATCHING }.items.first { it.episode != null }
        vm.removeFromContinueWatching(episodeCard)
        assertThat(progressDao.rows.value.map { it.itemId }).containsExactly(filmId)
    }

    @Test
    fun `the hero label says what the button will do`() {
        val film = MediaCard(MediaItem(id = "f", title = "F"))
        val show = MediaItem(id = "s", title = "S", kind = MediaKind.SHOW)
        val episode = Episode(id = "e", showId = "s", season = 2, number = 3)

        assertThat(heroLabel(HeroItem(film, PlayAction.Play("f", restart = false)))).isEqualTo("Play")
        assertThat(heroLabel(HeroItem(film, PlayAction.Play("f", restart = true)))).isEqualTo("Play again")
        assertThat(heroLabel(HeroItem(film, PlayAction.Resume("f", 5)))).isEqualTo("Resume")
        assertThat(heroLabel(HeroItem(film, PlayAction.Unavailable))).isEqualTo("Details")
        assertThat(heroLabel(HeroItem(MediaCard(show, episode = episode), PlayAction.Resume("e", 5)))).isEqualTo("Resume S2 E3")
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
