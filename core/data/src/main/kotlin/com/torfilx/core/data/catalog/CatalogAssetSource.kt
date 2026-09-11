package com.torfilx.core.data.catalog

import android.content.Context
import com.torfilx.core.catalogue.format.CatalogRelease
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/** Where the bundled catalogue comes from. An interface so the catalogue logic runs in plain JVM tests. */
interface CatalogAssetSource {

    /** The bundled `catalog.json`, read fully. */
    fun readCatalog(): ByteArray

    /** The bundled `catalog-manifest.json`, or null when this build has none. */
    fun readManifest(): ByteArray?
}

@Singleton
class AndroidCatalogAssetSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : CatalogAssetSource {

    /**
     * Reads the asset **fully** before anything parses it.
     *
     * AssetManager's stream is not a file: for a compressed asset it is an inflater that can hand back
     * short reads, and on the older Fire OS releases this app supports it can fail part-way through a
     * multi-megabyte entry. `readBytes()` loops until end of stream, so either every byte arrives or it
     * throws. (The asset is also packaged uncompressed; see `noCompress` in the app build file.)
     */
    override fun readCatalog(): ByteArray =
        context.assets.open(CatalogRelease.BUNDLED_CATALOG_ASSET).use { it.readBytes() }

    override fun readManifest(): ByteArray? = try {
        context.assets.open(CatalogRelease.BUNDLED_MANIFEST_ASSET).use { it.readBytes() }
    } catch (_: FileNotFoundException) {
        null
    }
}
