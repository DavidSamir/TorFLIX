package com.torfilx.core.data.catalog

import com.torfilx.core.catalogue.CatalogueJson
import com.torfilx.core.catalogue.format.BundledCatalogManifest
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogManifest
import com.torfilx.core.catalogue.release.CatalogueReleaseVerifier
import com.torfilx.core.catalogue.release.CatalogueVersionRules
import com.torfilx.core.common.log.TorfilxLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Catalog"

/**
 * The catalogue in use: a downloaded release when a newer one is installed, the bundled copy otherwise.
 *
 * On load, an installed release is verified again, in full, before it is trusted. That catches a file
 * damaged on disk, and it means a build that stops trusting a publisher key also stops using what
 * that key signed. Anything that fails sends the app back to the bundled copy, and the bad download is
 * removed. A viewer never ends up with fewer films than the APK ships because a download went wrong.
 *
 * An app update whose bundled catalogue has caught up with the downloaded one wins the tie, and the
 * download is removed.
 */
@Singleton
class LayeredCatalog @Inject constructor(
    private val assets: CatalogAssetSource,
    private val store: FetchedCatalogStore,
    private val verifier: CatalogueReleaseVerifier,
    private val appVersion: AppVersionProvider,
    private val json: Json,
) : Catalog {

    private val lock = Any()

    @Volatile
    private var cached: CatalogSnapshot? = null

    private val _info = MutableStateFlow(CatalogueInfo())
    override val info: StateFlow<CatalogueInfo> = _info.asStateFlow()

    override fun snapshot(): CatalogSnapshot = cached ?: synchronized(lock) {
        cached ?: load().also(::keepIfComplete)
    }

    /** The release number of the copy inside the APK; 0 when it has no manifest. */
    fun bundledVersion(): Long = readBundledManifest()?.catalogVersion ?: 0L

    // --- Swapping in a new release ---------------------------------------------------------------

    /** A verified release, mapped and checked, ready to swap in. */
    class PreparedCatalogue internal constructor(
        val manifest: CatalogManifest,
        internal val items: List<CatalogItem>,
    ) {
        val titleCount: Int get() = items.size
    }

    /**
     * Maps a verified release into titles and checks that every entry became one.
     *
     * Runs before anything is installed, so a release the app cannot fully use is refused while the
     * catalogue in use is still untouched.
     *
     * @throws IncompleteCatalogueException when fewer titles map than the release declares.
     */
    fun prepare(manifest: CatalogManifest, entries: List<CatalogEntryDto>): PreparedCatalogue {
        val items = mapCatalogEntries(entries)
        if (items.size != manifest.titleCount) {
            throw IncompleteCatalogueException(manifest.catalogVersion, items.size, manifest.titleCount)
        }
        return PreparedCatalogue(manifest, items)
    }

    /** Makes [prepared] the catalogue in use. Every observer of [info] sees the new generation. */
    fun commit(prepared: PreparedCatalogue, installedAtMs: Long) {
        synchronized(lock) {
            val snapshot = CatalogSnapshot(
                info = CatalogueInfo(
                    generation = nextGeneration(),
                    version = prepared.manifest.catalogVersion,
                    origin = CatalogueOrigin.FETCHED,
                    titleCount = prepared.items.size,
                    publishedAtMs = prepared.manifest.publishedAtMs,
                    installedAtMs = installedAtMs,
                ),
                items = prepared.items,
                declaredCount = prepared.manifest.titleCount,
            )
            cached = snapshot
            _info.value = snapshot.info
            TorfilxLog.i(
                TAG,
                "Catalogue ${snapshot.info.version} is now in use: ${snapshot.items.size} titles " +
                    "(generation ${snapshot.info.generation})",
            )
        }
    }

    /** Removes the downloaded release and goes back to the bundled copy. */
    fun resetToBundled(): CatalogSnapshot = synchronized(lock) {
        store.uninstall()
        cached = null
        loadBundled(readBundledManifest()).also(::keepIfComplete)
    }

    // --- Loading ---------------------------------------------------------------------------------

    private fun keepIfComplete(snapshot: CatalogSnapshot) {
        if (snapshot.isCacheable) {
            cached = snapshot
            _info.value = snapshot.info
        } else {
            TorfilxLog.w(
                TAG,
                "Not keeping an incomplete catalogue (${snapshot.items.size} of ${snapshot.declaredCount}); " +
                    "the next read will try again",
            )
        }
    }

    private fun nextGeneration(): Int = _info.value.generation + 1

    private fun load(): CatalogSnapshot {
        val bundledManifest = readBundledManifest()
        val bundledVersion = bundledManifest?.catalogVersion ?: 0L
        val installed = store.installed()
        if (installed != null) {
            if (CatalogueVersionRules.preferFetched(installed.version, bundledVersion)) {
                loadFetched(installed)?.let { return it }
            } else {
                TorfilxLog.i(
                    TAG,
                    "The bundled catalogue ($bundledVersion) is at least as new as the downloaded one " +
                        "(${installed.version}); removing the download",
                )
                store.uninstall()
            }
        }
        return loadBundled(bundledManifest)
    }

    private fun loadFetched(installed: FetchedCatalogStore.Installed): CatalogSnapshot? {
        val startedAt = System.nanoTime()
        val verdict = verifier.verify(store.releaseRootFor(installed.version), appVersion.versionCode)
        if (verdict is CatalogueReleaseVerifier.Result.Rejected) {
            TorfilxLog.e(
                TAG,
                "The downloaded catalogue ${installed.version} was refused on load (${verdict.reason}: " +
                    "${verdict.detail}); using the bundled catalogue",
            )
            store.uninstall()
            return null
        }
        val ok = verdict as CatalogueReleaseVerifier.Result.Ok
        val items = mapCatalogEntries(ok.entries)
        if (items.size != ok.manifest.titleCount) {
            TorfilxLog.e(
                TAG,
                "CATALOGUE INCOMPLETE: the downloaded catalogue ${installed.version} mapped ${items.size} of " +
                    "${ok.manifest.titleCount} titles; using the bundled catalogue",
            )
            store.uninstall()
            return null
        }
        TorfilxLog.i(
            TAG,
            "Using the downloaded catalogue ${installed.version}: ${items.size} titles, verified in " +
                "${(System.nanoTime() - startedAt) / NANOS_PER_MS} ms",
        )
        return CatalogSnapshot(
            info = CatalogueInfo(
                generation = nextGeneration(),
                version = installed.version,
                origin = CatalogueOrigin.FETCHED,
                titleCount = items.size,
                publishedAtMs = ok.manifest.publishedAtMs,
                installedAtMs = installed.installedAtMs,
            ),
            items = items,
            declaredCount = ok.manifest.titleCount,
        )
    }

    private fun loadBundled(manifest: BundledCatalogManifest?): CatalogSnapshot {
        val parsed = try {
            parseCatalogBytes(assets.readCatalog(), json)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            // A hard read failure surfaces as an empty catalogue, which Home states outright, rather than
            // as a handful of titles that looks like a small library.
            TorfilxLog.e(TAG, "The bundled catalogue could not be read", error)
            ParsedCatalog(emptyList(), 0)
        }
        return CatalogSnapshot(
            info = CatalogueInfo(
                generation = nextGeneration(),
                version = manifest?.catalogVersion ?: 0L,
                origin = CatalogueOrigin.BUNDLED,
                titleCount = parsed.items.size,
                publishedAtMs = manifest?.publishedAtMs,
                installedAtMs = null,
            ),
            items = parsed.items,
            declaredCount = parsed.declaredCount,
        )
    }

    private fun readBundledManifest(): BundledCatalogManifest? {
        val bytes = runCatching { assets.readManifest() }.getOrNull()
        if (bytes == null) {
            TorfilxLog.w(TAG, "No bundled catalogue manifest; the bundled catalogue counts as version 0")
            return null
        }
        return runCatching {
            CatalogueJson.manifest.decodeFromString(BundledCatalogManifest.serializer(), bytes.decodeToString())
        }.onFailure {
            TorfilxLog.w(TAG, "The bundled catalogue manifest is unreadable; the bundled catalogue counts as version 0", it)
        }.getOrNull()
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}

/** A release that verified but could not be fully turned into titles. */
class IncompleteCatalogueException(
    val version: Long,
    val mapped: Int,
    val declared: Int,
) : Exception("Catalogue $version mapped $mapped of $declared titles")
