package com.torfilx.core.data.catalog

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.format.CatalogRelease
import com.torfilx.core.catalogue.release.CatalogueReleaseVerifier
import com.torfilx.core.catalogue.testing.CatalogueTestKeys
import com.torfilx.core.catalogue.testing.TestCatalogues
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Which catalogue is in use: the bundled copy, or a newer downloaded release that still verifies.
 *
 * Every fallback path ends on the bundled copy with the bad download removed, because a viewer must
 * never end up with fewer films than the APK ships.
 */
class LayeredCatalogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bundledEntries = TestCatalogues.entries(3, titlePrefix = "Bundled")
    private lateinit var store: FetchedCatalogStore

    @Before
    fun setUp() {
        store = FetchedCatalogStore { File(tmp.root, "catalogue") }
    }

    private fun bundled(version: Long = 5) = FakeCatalogAssetSource.bundled(version, bundledEntries)

    private fun verifiedRelease(version: Long, entries: List<com.torfilx.core.catalogue.format.CatalogEntryDto>) =
        CatalogueTestKeys.verifier().verify(
            TestCatalogues.writeRelease(tmp.newFolder(), version, entries).releaseRoot,
            FixedAppVersion.APP_VERSION_CODE,
        ) as CatalogueReleaseVerifier.Result.Ok

    @Test
    fun `with nothing downloaded the bundled catalogue is used`() {
        val catalog = layeredCatalog(bundled(), store)

        val snapshot = catalog.snapshot()

        assertThat(snapshot.info.origin).isEqualTo(CatalogueOrigin.BUNDLED)
        assertThat(snapshot.info.version).isEqualTo(5)
        assertThat(snapshot.info.titleCount).isEqualTo(3)
        assertThat(snapshot.info.generation).isEqualTo(1)
        assertThat(snapshot.info.publishedAtMs).isEqualTo(TestCatalogues.FIXED_PUBLISHED_AT_MS)
        assertThat(catalog.info.value).isEqualTo(snapshot.info)
        assertThat(snapshot.mediaItems.map { it.id }).isEqualTo(bundledEntries.map { it.id })
        assertThat(catalog.bundledVersion()).isEqualTo(5)
    }

    @Test
    fun `a newer downloaded release is used instead of the bundled one`() {
        val fetched = TestCatalogues.entries(4, titlePrefix = "Fetched")
        store.installTestRelease(8, fetched, installedAtMs = 42)

        val info = layeredCatalog(bundled(), store).snapshot().info

        assertThat(info.origin).isEqualTo(CatalogueOrigin.FETCHED)
        assertThat(info.version).isEqualTo(8)
        assertThat(info.titleCount).isEqualTo(4)
        assertThat(info.installedAtMs).isEqualTo(42)
        assertThat(info.publishedAtMs).isEqualTo(TestCatalogues.FIXED_PUBLISHED_AT_MS)
    }

    @Test
    fun `a download no newer than the bundled catalogue is removed`() {
        store.installTestRelease(5, TestCatalogues.entries(4, titlePrefix = "Fetched"))

        val info = layeredCatalog(bundled(version = 5), store).snapshot().info

        assertThat(info.origin).isEqualTo(CatalogueOrigin.BUNDLED)
        assertThat(store.installed()).isNull()
        assertThat(store.saveDirFor(5).exists()).isFalse()
    }

    @Test
    fun `a download that no longer verifies falls back to the bundled catalogue and is removed`() {
        store.installTestRelease(8, TestCatalogues.entries(4, titlePrefix = "Fetched"), seed = CatalogueTestKeys.OTHER_SEED)

        val info = layeredCatalog(bundled(), store).snapshot().info

        assertThat(info.origin).isEqualTo(CatalogueOrigin.BUNDLED)
        assertThat(store.installed()).isNull()
    }

    @Test
    fun `a download damaged on disk falls back to the bundled catalogue and is removed`() {
        store.installTestRelease(8, TestCatalogues.entries(4, titlePrefix = "Fetched"))
        val gz = File(store.releaseRootFor(8), CatalogRelease.CATALOG_GZ)
        val bytes = gz.readBytes().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x55).toByte() }
        gz.writeBytes(bytes)

        val info = layeredCatalog(bundled(), store).snapshot().info

        assertThat(info.origin).isEqualTo(CatalogueOrigin.BUNDLED)
        assertThat(store.installed()).isNull()
    }

    @Test
    fun `a download that needs a newer app falls back to the bundled catalogue`() {
        TestCatalogues.writeRelease(store.saveDirFor(8), 8, TestCatalogues.entries(2), minVersionCode = 99)
        store.install(8, "ab".repeat(20), byteArrayOf(1), 1)

        val info = layeredCatalog(bundled(), store, appVersionCode = 16).snapshot().info

        assertThat(info.origin).isEqualTo(CatalogueOrigin.BUNDLED)
    }

    @Test
    fun `a bundled catalogue without a manifest counts as version 0`() {
        store.installTestRelease(1, TestCatalogues.entries(2, titlePrefix = "Fetched"))
        val catalog = layeredCatalog(bundled(version = 0), store)

        assertThat(catalog.bundledVersion()).isEqualTo(0)
        assertThat(catalog.snapshot().info.origin).isEqualTo(CatalogueOrigin.FETCHED)
    }

    @Test
    fun `committing a prepared release swaps it in and bumps the generation`() {
        val catalog = layeredCatalog(bundled(), store)
        val before = catalog.snapshot()
        val fetched = TestCatalogues.entries(4, titlePrefix = "Fetched")
        val release = verifiedRelease(9, fetched)

        catalog.commit(catalog.prepare(release.manifest, release.entries), installedAtMs = 77)

        val after = catalog.snapshot()
        assertThat(after).isNotSameInstanceAs(before)
        assertThat(after.info).isEqualTo(
            CatalogueInfo(
                generation = before.info.generation + 1,
                version = 9,
                origin = CatalogueOrigin.FETCHED,
                titleCount = 4,
                publishedAtMs = TestCatalogues.FIXED_PUBLISHED_AT_MS,
                installedAtMs = 77,
            ),
        )
        assertThat(catalog.info.value).isEqualTo(after.info)
        assertThat(catalog.item(fetched.first().id!!)).isNotNull()
        assertThat(catalog.item(bundledEntries.first().id!!)).isNull()
    }

    @Test
    fun `a release whose entries do not all become titles is refused before anything changes`() {
        val catalog = layeredCatalog(bundled(), store)
        val before = catalog.snapshot()
        val release = verifiedRelease(9, TestCatalogues.entries(3, titlePrefix = "Fetched"))

        assertThrows(IncompleteCatalogueException::class.java) {
            catalog.prepare(release.manifest.copy(titleCount = 4), release.entries)
        }
        assertThat(catalog.snapshot()).isSameInstanceAs(before)
    }

    @Test
    fun `going back to the bundled catalogue removes the download`() {
        store.installTestRelease(8, TestCatalogues.entries(4, titlePrefix = "Fetched"))
        val catalog = layeredCatalog(bundled(), store)
        val fetched = catalog.snapshot().info
        assertThat(fetched.origin).isEqualTo(CatalogueOrigin.FETCHED)

        val reset = catalog.resetToBundled()

        assertThat(reset.info.origin).isEqualTo(CatalogueOrigin.BUNDLED)
        assertThat(reset.info.version).isEqualTo(5)
        assertThat(reset.info.generation).isEqualTo(fetched.generation + 1)
        assertThat(catalog.info.value).isEqualTo(reset.info)
        assertThat(store.installed()).isNull()
    }

    @Test
    fun `an unreadable bundled catalogue is not kept and is read again`() {
        val assets = bundled().apply { failuresLeft = 1 }
        val catalog = layeredCatalog(assets, store)

        assertThat(catalog.snapshot().items).isEmpty()
        assertThat(catalog.info.value.generation).isEqualTo(0)

        assertThat(catalog.snapshot().items).hasSize(3)
        assertThat(catalog.snapshot().items).hasSize(3)
        assertThat(assets.reads).isEqualTo(2)
        assertThat(catalog.info.value.generation).isEqualTo(1)
    }

    @Test
    fun `the real shipped catalogue loads in full through the layered catalogue`() {
        val json = File("src/main/assets/catalog.json").readBytes()
        val manifest = File("src/main/assets/catalog-manifest.json").takeIf { it.isFile }?.readBytes()

        val snapshot = layeredCatalog(FakeCatalogAssetSource(json, manifest), store).snapshot()

        assertThat(snapshot.isIncomplete).isFalse()
        assertThat(snapshot.items.size).isAtLeast(2_000)
        assertThat(snapshot.info.origin).isEqualTo(CatalogueOrigin.BUNDLED)
    }
}
