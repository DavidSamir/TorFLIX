package com.torfilx.core.data.repository

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.release.CatalogueReleaseVerifier
import com.torfilx.core.catalogue.testing.CatalogueTestKeys
import com.torfilx.core.catalogue.testing.TestCatalogues
import com.torfilx.core.data.catalog.CatalogueOrigin
import com.torfilx.core.data.catalog.FakeCatalogAssetSource
import com.torfilx.core.data.catalog.FetchedCatalogStore
import com.torfilx.core.data.catalog.FixedAppVersion
import com.torfilx.core.data.catalog.LayeredCatalog
import com.torfilx.core.data.catalog.layeredCatalog
import com.torfilx.core.data.database.ProgressEntity
import com.torfilx.core.model.LibraryQuery
import com.torfilx.core.testing.FakeMyListDao
import com.torfilx.core.testing.FakeProgressDao
import com.torfilx.core.testing.FakeShowStateDao
import com.torfilx.core.testing.FakeSearchHistoryDao
import com.torfilx.core.testing.FakeTimeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A newer catalogue swapped in while the app runs reaches every screen without a restart.
 *
 * Before, these flows were built once from a catalogue that could never change; `observeItem` was a
 * one-shot `flowOf`. Now every one of them follows the catalogue's generation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaRepositoryGenerationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bundled = TestCatalogues.entries(3, titlePrefix = "Bundled")
    private val release = TestCatalogues.entries(4, titlePrefix = "Release")

    private class Fixture(
        val catalog: LayeredCatalog,
        val progressDao: FakeProgressDao,
        val progressRepository: ProgressRepository,
        val repository: MediaRepository,
    )

    private fun TestScope.fixture(): Fixture {
        val store = FetchedCatalogStore { File(tmp.root, "catalogue") }
        val catalog = layeredCatalog(FakeCatalogAssetSource.bundled(5, bundled), store)
        catalog.preload()
        val time = FakeTimeProvider()
        val progressDao = FakeProgressDao()
        val progressRepository = ProgressRepository(progressDao, catalog, time, FakeShowStateDao())
        val myListRepository = MyListRepository(FakeMyListDao(), time)
        val repository = MediaRepository(
            catalog = catalog,
            searchHistoryDao = FakeSearchHistoryDao(),
            progressRepository = progressRepository,
            myListRepository = myListRepository,
            timeProvider = time,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        return Fixture(catalog, progressDao, progressRepository, repository)
    }

    private fun LayeredCatalog.swapIn(entries: List<CatalogEntryDto>, version: Long) {
        val root = TestCatalogues.writeRelease(tmp.newFolder(), version, entries).releaseRoot
        val ok = CatalogueTestKeys.verifier().verify(root, FixedAppVersion.APP_VERSION_CODE) as CatalogueReleaseVerifier.Result.Ok
        commit(prepare(ok.manifest, ok.entries), installedAtMs = 1)
    }

    @Test
    fun `Home re-emits with the new titles when a newer catalogue is swapped in`() = runTest {
        val fixture = fixture()

        fixture.repository.observeHome().test {
            val before = awaitItem().first { it.id == MediaRepository.ROW_CATALOG }
            assertThat(before.items.map { it.item.title }).containsExactlyElementsIn(bundled.map { it.title })

            fixture.catalog.swapIn(release, version = 7)

            val after = awaitItem().first { it.id == MediaRepository.ROW_CATALOG }
            assertThat(after.items.map { it.item.title }).containsExactlyElementsIn(release.map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the library grid follows the catalogue in use`() = runTest {
        val fixture = fixture()

        fixture.repository.observeLibrary(LibraryQuery()).test {
            assertThat(awaitItem()).hasSize(3)
            fixture.catalog.swapIn(release, version = 7)
            assertThat(awaitItem()).hasSize(4)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a title that only exists in the newer catalogue appears on its details flow`() = runTest {
        val fixture = fixture()
        val newId = release.first().id!!

        fixture.repository.observeItem(newId).test {
            assertThat(awaitItem()).isNull()
            fixture.catalog.swapIn(release, version = 7)
            assertThat(awaitItem()?.title).isEqualTo(release.first().title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `continue watching shows a film once a catalogue that has it arrives`() = runTest {
        val fixture = fixture()
        val film = release[1].id!!
        fixture.progressDao.rows.value = listOf(
            ProgressEntity(film, positionMs = 10 * 60_000L, durationMs = 90 * 60_000L, watched = false, updatedAtMs = 5),
        )

        fixture.progressRepository.observeContinueWatching().test {
            assertThat(awaitItem()).isEmpty()
            fixture.catalog.swapIn(release, version = 7)
            assertThat(awaitItem().map { it.item.id }).containsExactly(film)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the catalogue the screens describe changes with the swap`() = runTest {
        val fixture = fixture()

        fixture.repository.observeCatalogue().test {
            val before = awaitItem()
            assertThat(before.origin).isEqualTo(CatalogueOrigin.BUNDLED)
            assertThat(before.version).isEqualTo(5)

            fixture.catalog.swapIn(release, version = 7)

            val after = awaitItem()
            assertThat(after.origin).isEqualTo(CatalogueOrigin.FETCHED)
            assertThat(after.version).isEqualTo(7)
            assertThat(after.generation).isEqualTo(before.generation + 1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `genres and search answer from the new catalogue`() = runTest {
        val fixture = fixture()
        val renamed = TestCatalogues.entries(2, titlePrefix = "Nosferatu").map { it.copy(genres = listOf("Horror")) }

        assertThat(fixture.repository.genres()).doesNotContain("Horror")
        assertThat(fixture.repository.search("nosferatu")).isEmpty()

        fixture.catalog.swapIn(renamed, version = 9)

        assertThat(fixture.repository.genres()).containsExactly("Horror")
        assertThat(fixture.repository.search("nosferatu")).hasSize(2)
    }
}

