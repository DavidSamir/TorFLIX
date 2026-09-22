package com.torfilx.core.testing

import com.torfilx.core.catalogue.CatalogueJson
import com.torfilx.core.catalogue.crypto.Ed25519Verifier
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.release.CatalogueReleaseVerifier
import com.torfilx.core.data.catalog.AppVersionProvider
import com.torfilx.core.data.catalog.CatalogAssetSource
import com.torfilx.core.data.catalog.FetchedCatalogStore
import com.torfilx.core.data.catalog.LayeredCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.builtins.ListSerializer
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/** A bundled `catalog.json` held in memory, with no manifest. */
class InMemoryCatalogAssetSource(private val json: ByteArray) : CatalogAssetSource {
    override fun readCatalog(): ByteArray = json
    override fun readManifest(): ByteArray? = null
}

/**
 * A real catalogue over [entries] — the app's own parser, index and snapshot — that trusts no
 * publisher, so it only ever serves what it was given. Already loaded when returned.
 *
 * @param workDir somewhere writable for the (unused) downloaded-release store.
 */
fun inMemoryCatalog(entries: List<CatalogEntryDto>, workDir: File): LayeredCatalog = LayeredCatalog(
    assets = InMemoryCatalogAssetSource(
        CatalogueJson.catalogWriter
            .encodeToString(ListSerializer(CatalogEntryDto.serializer()), entries)
            .encodeToByteArray(),
    ),
    store = FetchedCatalogStore { File(workDir, "catalogue") },
    verifier = CatalogueReleaseVerifier(Ed25519Verifier(emptyList())),
    appVersion = object : AppVersionProvider {
        override val versionCode: Int = 1
    },
    json = CatalogueJson.content,
).also { it.preload() }

/** Runs `viewModelScope` on a test dispatcher for the duration of each test. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
