package com.torfilx.feature.details

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogIds
import com.torfilx.core.catalogue.format.CatalogMagnetDto
import com.torfilx.core.catalogue.testing.TestCatalogues
import com.torfilx.core.data.catalog.LayeredCatalog
import com.torfilx.core.data.database.ProgressEntity
import com.torfilx.core.data.repository.MediaRepository
import com.torfilx.core.data.repository.MyListRepository
import com.torfilx.core.data.repository.ProgressRepository
import com.torfilx.core.data.settings.SettingsRepository
import com.torfilx.core.data.torrent.TorrentCoordinator
import com.torfilx.core.model.PlayAction
import com.torfilx.core.testing.FakeMyListDao
import com.torfilx.core.testing.FakeProgressDao
import com.torfilx.core.testing.FakeShowStateDao
import com.torfilx.core.testing.FakeSearchHistoryDao
import com.torfilx.core.testing.FakeTimeProvider
import com.torfilx.core.testing.MainDispatcherRule
import com.torfilx.core.testing.inMemoryCatalog
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The details screen's state and actions, over the app's real catalogue and repositories.
 *
 * What matters most here is what the primary button plays, which season is listed, and that every
 * route into playback goes through the sharing consent exactly once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val entries: List<CatalogEntryDto> = CatalogIds.pin(
        TestCatalogues.entries(1).map { it.copy(id = null) } +
            TestCatalogues.show(title = "Twilight", seasons = 2, episodesPerSeason = 3, withSpecials = true).let { show ->
                // S1 E2 gets a second quality, so its menu offers a choice.
                show.copy(
                    seasons = show.seasons.map { season ->
                        if (season.number != 1) {
                            season
                        } else {
                            season.copy(
                                episodes = season.episodes.map { e ->
                                    if (e.number != 2) e else e.copy(magnets = e.magnets + CatalogMagnetDto("1080p", TestCatalogues.magnet(9_999)))
                                },
                            )
                        }
                    },
                )
            },
    )
    private val filmId = entries[0].id!!
    private val show = entries[1]
    private val showId = show.id!!
    private val s1 = show.seasons.first { it.number == 1 }.episodes.map { it.id!! }
    private val s2 = show.seasons.first { it.number == 2 }.episodes.map { it.id!! }

    private val consent = MutableStateFlow(true)
    private val settings = mockk<SettingsRepository>(relaxed = true) {
        every { sharingConsent } returns consent
    }
    private val coordinator = mockk<TorrentCoordinator>(relaxed = true) {
        every { isAvailable() } returns true
    }

    private lateinit var catalog: LayeredCatalog
    private val progressDao = FakeProgressDao()
    private val myListDao = FakeMyListDao()

    private fun viewModel(id: String): DetailsViewModel {
        catalog = inMemoryCatalog(entries, tmp.root)
        val time = FakeTimeProvider()
        val progress = ProgressRepository(progressDao, catalog, time, FakeShowStateDao())
        val myList = MyListRepository(myListDao, time)
        val media = MediaRepository(catalog, FakeSearchHistoryDao(), progress, myList, time, main.dispatcher)
        return DetailsViewModel(
            savedStateHandle = SavedStateHandle(mapOf(DetailsViewModel.ARG_ITEM_ID to id)),
            mediaRepository = media,
            progressRepository = progress,
            myListRepository = myList,
            catalog = catalog,
            settingsRepository = settings,
            torrentCoordinator = coordinator,
            ioDispatcher = main.dispatcher,
        )
    }

    private suspend fun DetailsViewModel.content(): DetailsUiState.Content =
        uiState.first { it is DetailsUiState.Content } as DetailsUiState.Content

    private fun watched(id: String, at: Long) = ProgressEntity(id, 25 * MINUTE, 25 * MINUTE, watched = true, updatedAtMs = at)

    // --- What is shown ---------------------------------------------------------------------------

    @Test
    fun `a film shows its sources and no show half`() = runTest {
        val content = viewModel(filmId).content()
        assertThat(content.show).isNull()
        assertThat(content.sources).hasSize(1)
        assertThat(content.primaryAction).isEqualTo(PlayAction.Play(filmId, restart = false))
    }

    @Test
    fun `a new show lists its first season and plays its first episode`() = runTest {
        val content = viewModel(showId).content()
        val show = content.show!!
        assertThat(content.sources).isEmpty()
        assertThat(content.progress).isNull()
        assertThat(show.seasons.map { it.number }).containsExactly(1, 2, 0).inOrder()
        assertThat(show.selectedSeason?.number).isEqualTo(1)
        assertThat(show.nextUp?.id).isEqualTo(s1[0])
        assertThat(content.primaryAction).isEqualTo(PlayAction.Play(s1[0], restart = false))
        assertThat(showPrimaryLabel(content.primaryAction, show)).isEqualTo("Play S1 E1")
    }

    @Test
    fun `the listed season follows the next-up episode until the viewer picks one`() = runTest {
        progressDao.rows.value = s1.mapIndexed { i, id -> watched(id, i.toLong()) }
        val vm = viewModel(showId)
        assertThat(vm.content().show!!.selectedSeason?.number).isEqualTo(2)

        vm.selectSeason(vm.content().show!!.seasons.first { it.number == 0 })
        assertThat(vm.uiState.first { (it as? DetailsUiState.Content)?.show?.selectedSeason?.number == 0 }).isNotNull()
    }

    @Test
    fun `a half-watched episode is resumed, and the label says where`() = runTest {
        progressDao.rows.value = listOf(watched(s1[0], 1), ProgressEntity(s1[1], 12 * MINUTE + 40_000, 25 * MINUTE, false, 2))
        val content = viewModel(showId).content()
        assertThat(content.primaryAction).isEqualTo(PlayAction.Resume(s1[1], 12 * MINUTE + 40_000))
        assertThat(showPrimaryLabel(content.primaryAction, content.show!!)).isEqualTo("Resume S1 E2 · 12:40")
    }

    @Test
    fun `a fully watched show offers to start again`() = runTest {
        progressDao.rows.value = (s1 + s2).mapIndexed { i, id -> watched(id, i.toLong()) }
        val content = viewModel(showId).content()
        assertThat(content.primaryAction).isEqualTo(PlayAction.Play(s1[0], restart = true))
        assertThat(showPrimaryLabel(content.primaryAction, content.show!!)).isEqualTo("Play again from S1 E1")
    }

    @Test
    fun `an episode's id opens its show`() = runTest {
        val vm = viewModel(s2[1])
        assertThat(vm.content().item.id).isEqualTo(showId)
        vm.toggleMyList()
        assertThat(myListDao.entries.value.map { it.itemId }).containsExactly(showId)
    }

    @Test
    fun `an id that is not in the catalogue is an error, not a crash`() = runTest {
        assertThat(viewModel("nope").uiState.first { it is DetailsUiState.Error }).isNotNull()
    }

    // --- Playing ---------------------------------------------------------------------------------

    @Test
    fun `with consent, the primary button plays the next-up episode at once`() = runTest {
        val vm = viewModel(showId)
        vm.content()
        var played: PendingPlay? = null
        vm.requestPrimary { played = it }
        assertThat(played).isEqualTo(PendingPlay(s1[0], sourceId = null))
        assertThat(vm.lastPlayedEpisodeId).isNull()
    }

    @Test
    fun `without consent, the play waits for the dialog, and accepting it plays exactly that`() = runTest {
        consent.value = false
        val vm = viewModel(showId)
        val episode = vm.content().show!!.seasons.first().episodes[2]
        var played: PendingPlay? = null

        vm.requestEpisode(episode, sourceId = null) { played = it }
        assertThat(played).isNull()
        assertThat(vm.pendingPlay.value).isEqualTo(PendingPlay(episode.id, null))
        assertThat(vm.lastPlayedEpisodeId).isEqualTo(episode.id)

        vm.onConsentAccepted { played = it }
        assertThat(played).isEqualTo(PendingPlay(episode.id, null))
        assertThat(vm.pendingPlay.value).isNull()
        coVerify { settings.setSharingConsent(true) }
    }

    @Test
    fun `declining consent plays nothing and leaves the viewer on the screen`() = runTest {
        consent.value = false
        val vm = viewModel(filmId)
        val source = vm.content().sources.single()
        var played: PendingPlay? = null

        vm.requestPlayback(filmId, source.id) { played = it }
        vm.onConsentDeclined()

        assertThat(played).isNull()
        assertThat(vm.pendingPlay.value).isNull()
        coVerify { settings.setSharingConsent(false) }
    }

    // --- Menus -----------------------------------------------------------------------------------

    @Test
    fun `an episode's menu offers each quality when there is a choice, and its watched state`() = runTest {
        val vm = viewModel(showId)
        val season1 = vm.content().show!!.seasons.first()
        vm.openEpisodeMenu(season1.episodes[1])
        val menu = vm.menu.value as DetailsMenu.ForEpisode
        assertThat(menu.sources).hasSize(2)
        assertThat(menu.watched).isFalse()

        vm.markEpisodeWatched(menu.episode, watched = true)
        assertThat(vm.menu.value).isNull()
        assertThat(progressDao.rows.value.single().let { it.itemId to it.watched }).isEqualTo(s1[1] to true)
    }

    @Test
    fun `a season's menu marks the whole season, and knows when it already is`() = runTest {
        val vm = viewModel(showId)
        val season2 = vm.content().show!!.seasons.first { it.number == 2 }
        vm.openSeasonMenu(season2)
        assertThat((vm.menu.value as DetailsMenu.ForSeason).watched).isFalse()

        vm.markSeasonWatched(season2, watched = true)
        assertThat(progressDao.rows.value.map { it.itemId }).containsExactlyElementsIn(s2)

        vm.uiState.first { (it as? DetailsUiState.Content)?.show?.episodeProgress?.size == 3 }
        vm.openSeasonMenu(season2)
        assertThat((vm.menu.value as DetailsMenu.ForSeason).watched).isTrue()
        vm.dismissMenu()
        assertThat(vm.menu.value).isNull()
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
