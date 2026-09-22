package com.torfilx.feature.library

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogIds
import com.torfilx.core.catalogue.testing.TestCatalogues
import com.torfilx.core.data.repository.MediaRepository
import com.torfilx.core.data.repository.MyListRepository
import com.torfilx.core.data.repository.ProgressRepository
import com.torfilx.core.data.settings.LibraryDefaults
import com.torfilx.core.data.settings.LibraryPreferences
import com.torfilx.core.model.LibrarySort
import com.torfilx.core.model.MediaKind
import com.torfilx.core.model.WatchedFilter
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

/** The Movies, Shows and My List grids over one catalogue of films and shows. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val entries: List<CatalogEntryDto> = CatalogIds.pin(
        TestCatalogues.entries(2).map { it.copy(id = null, genres = listOf("Comedy")) } +
            TestCatalogues.show(title = "Twilight", genres = listOf("Sci-Fi")),
    )
    private val showId = entries[2].id!!
    private val myListDao = FakeMyListDao()

    private fun viewModel(mode: LibraryMode, defaults: LibraryDefaults = LibraryDefaults()): LibraryViewModel {
        val catalog = inMemoryCatalog(entries, tmp.root)
        val time = FakeTimeProvider()
        val progress = ProgressRepository(FakeProgressDao(), catalog, time, FakeShowStateDao())
        val myList = MyListRepository(myListDao, time)
        return LibraryViewModel(
            savedStateHandle = SavedStateHandle(mapOf(LibraryViewModel.ARG_MODE to mode.name)),
            mediaRepository = MediaRepository(catalog, FakeSearchHistoryDao(), progress, myList, time, main.dispatcher),
            progressRepository = progress,
            myListRepository = myList,
            libraryPreferences = object : LibraryPreferences {
                override val libraryDefaults = flowOf(defaults)
            },
        )
    }

    private suspend fun LibraryViewModel.loaded() = uiState.first { !it.isLoading && it.cards.isNotEmpty() }

    @Test
    fun `the Shows tab lists only shows, with only the genres shows have`() = runTest {
        val vm = viewModel(LibraryMode.SHOWS)
        val state = vm.loaded()
        assertThat(state.mode).isEqualTo(LibraryMode.SHOWS)
        assertThat(state.cards.map { it.item.id }).containsExactly(showId)
        assertThat(vm.uiState.first { it.genres.isNotEmpty() }.genres).containsExactly("Sci-Fi")
    }

    @Test
    fun `the Movies tab lists only films`() = runTest {
        val state = viewModel(LibraryMode.MOVIES).loaded()
        assertThat(state.cards.map { it.item.kind }.toSet()).containsExactly(MediaKind.MOVIE)
        assertThat(state.cards).hasSize(2)
    }

    @Test
    fun `My List holds films and shows alike`() = runTest {
        val vm = viewModel(LibraryMode.MY_LIST)
        vm.toggleMyList(vm.let { viewModel(LibraryMode.SHOWS).loaded().cards.single() })
        val state = vm.uiState.first { it.cards.isNotEmpty() }
        assertThat(state.cards.map { it.item.id }).containsExactly(showId)
        assertThat(myListDao.entries.value.map { it.itemId }).containsExactly(showId)
    }

    @Test
    fun `a genre the tab does not have is dropped when the tab changes`() = runTest {
        val vm = viewModel(LibraryMode.MOVIES)
        vm.loaded()
        vm.setGenre("Comedy")
        vm.setMode(LibraryMode.SHOWS)
        val state = vm.uiState.first { it.mode == LibraryMode.SHOWS && it.genres == listOf("Sci-Fi") }
        assertThat(state.query.genre).isNull()
    }

    @Test
    fun `the grids open with the stored sort, and on unwatched titles when watched ones are hidden`() = runTest {
        val defaults = LibraryDefaults(sort = LibrarySort.ALPHABETICAL, hideWatched = true)
        val movies = viewModel(LibraryMode.MOVIES, defaults).loaded()
        assertThat(movies.query.sort).isEqualTo(LibrarySort.ALPHABETICAL)
        assertThat(movies.query.watched).isEqualTo(WatchedFilter.UNWATCHED)
    }

    @Test
    fun `My List keeps watched titles even when they are hidden elsewhere`() = runTest {
        val vm = viewModel(LibraryMode.MY_LIST, LibraryDefaults(sort = LibrarySort.YEAR, hideWatched = true))
        val state = vm.uiState.first { !it.isLoading }
        assertThat(state.query.sort).isEqualTo(LibrarySort.YEAR)
        assertThat(state.query.watched).isEqualTo(WatchedFilter.ALL)
    }

    @Test
    fun `an empty, unfiltered Shows grid means the catalogue has none`() = runTest {
        val state = LibraryUiState(mode = LibraryMode.SHOWS, isLoading = false)
        assertThat(state.isEmpty).isTrue()
        assertThat(state.isUnfiltered).isTrue()
    }
}
