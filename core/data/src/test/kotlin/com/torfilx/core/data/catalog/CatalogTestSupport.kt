package com.torfilx.core.data.catalog

import com.torfilx.core.catalogue.CatalogueJson
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.release.CatalogueReleaseWriter
import com.torfilx.core.catalogue.testing.CatalogueTestKeys
import com.torfilx.core.catalogue.testing.FakeCatalogueTransport
import com.torfilx.core.catalogue.testing.TestCatalogues
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException

/** A bundled catalogue held in memory, able to fail on demand. */
internal class FakeCatalogAssetSource(
    private val catalog: ByteArray,
    private val manifest: ByteArray?,
) : CatalogAssetSource {

    var failuresLeft = 0
    var reads = 0
        private set

    override fun readCatalog(): ByteArray {
        reads++
        if (failuresLeft > 0) {
            failuresLeft--
            throw IOException("simulated unreadable asset")
        }
        return catalog
    }

    override fun readManifest(): ByteArray? = manifest

    companion object {
        /** A bundled catalogue of [entries] as release [version]; version 0 ships no manifest at all. */
        fun bundled(version: Long, entries: List<CatalogEntryDto>): FakeCatalogAssetSource {
            val json = TestCatalogues.json(entries)
            val manifest = if (version > 0) {
                CatalogueReleaseWriter.encodeBundledManifest(
                    CatalogueReleaseWriter.bundledManifest(json, version, TestCatalogues.FIXED_PUBLISHED_AT_MS),
                ).encodeToByteArray()
            } else {
                null
            }
            return FakeCatalogAssetSource(json, manifest)
        }
    }
}

internal class FixedAppVersion(override val versionCode: Int = APP_VERSION_CODE) : AppVersionProvider {
    companion object {
        const val APP_VERSION_CODE = 16
    }
}

/** The updater's settings and history, in memory. */
internal class FakeCatalogueUpdatePrefs(
    enabled: Boolean = true,
    var sharingConsent: Boolean = true,
    var useDht: Boolean = true,
) : CatalogueUpdatePrefs {

    val enabledFlow = MutableStateFlow(enabled)
    val recordFlow = MutableStateFlow(CatalogueUpdateRecord())
    var rejected: RejectedCatalogue? = null

    override val catalogUpdatesEnabled: Flow<Boolean> = enabledFlow
    override val catalogueUpdateRecord: Flow<CatalogueUpdateRecord> = recordFlow

    override suspend fun catalogueUpdateSettings() =
        CatalogueUpdateSettings(updatesEnabled = enabledFlow.value, sharingConsent = sharingConsent, useDht = useDht)

    override suspend fun recordCatalogueCheck(atMs: Long, result: String, successful: Boolean) {
        val current = recordFlow.value
        recordFlow.value = current.copy(
            lastCheckMs = atMs,
            lastResult = result,
            lastSuccessMs = if (successful) atMs else current.lastSuccessMs,
        )
    }

    override suspend fun recordRejectedCatalogue(version: Long, appVersionCode: Int) {
        rejected = RejectedCatalogue(version, appVersionCode)
    }

    override suspend fun rejectedCatalogue(): RejectedCatalogue? = rejected
}

/** Starts the fake session when allowed, the way the torrent coordinator starts the engine. */
internal class FakeSessionGate(
    private val transport: FakeCatalogueTransport,
    var allow: Boolean = true,
) : CatalogueSessionGate {
    var calls = 0
        private set

    override suspend fun ensureRunning(): Boolean {
        calls++
        if (allow) transport.running.value = true
        return allow
    }
}

/** Writes a signed release into this store's directory for [version] and records it as installed. */
internal fun FetchedCatalogStore.installTestRelease(
    version: Long,
    entries: List<CatalogEntryDto>,
    seed: ByteArray = CatalogueTestKeys.SEED,
    installedAtMs: Long = 1_000L,
): FetchedCatalogStore.Installed {
    TestCatalogues.writeRelease(saveDirFor(version), version, entries, seed)
    val infoHash = FakeCatalogueTransport.infoHashFor(version)
    return install(version, infoHash, FakeCatalogueTransport.torrentBytesFor(infoHash), installedAtMs)
}

/** A layered catalogue that trusts the test publisher key. */
internal fun layeredCatalog(
    assets: CatalogAssetSource,
    store: FetchedCatalogStore,
    appVersionCode: Int = FixedAppVersion.APP_VERSION_CODE,
) = LayeredCatalog(assets, store, CatalogueTestKeys.verifier(), FixedAppVersion(appVersionCode), CatalogueJson.content)
