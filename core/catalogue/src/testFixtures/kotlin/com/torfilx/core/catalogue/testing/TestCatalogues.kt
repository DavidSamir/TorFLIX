package com.torfilx.core.catalogue.testing

import com.torfilx.core.catalogue.CatalogueJson
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogIds
import com.torfilx.core.catalogue.format.CatalogMagnetDto
import com.torfilx.core.catalogue.release.CatalogueReleaseWriter
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.Locale

/** Small, valid catalogues and releases for tests. */
object TestCatalogues {

    /** 2026-01-01T00:00:00Z, so releases written in tests are reproducible. */
    const val FIXED_PUBLISHED_AT_MS = 1_767_225_600_000L

    private const val YEAR_BASE = 1920
    private const val YEAR_SPREAD = 80

    /** A well-formed magnet whose info hash is unique to [index]. */
    fun magnet(index: Int): String =
        "magnet:?xt=urn:btih:${String.format(Locale.ROOT, "%040x", index + 1)}&dn=Fixture+$index"

    /** [count] titled entries with pinned ids, alternating two genres. */
    fun entries(count: Int, titlePrefix: String = "Fixture Film"): List<CatalogEntryDto> = CatalogIds.pin(
        (0 until count).map { index ->
            CatalogEntryDto(
                title = "$titlePrefix ${index + 1}",
                year = (YEAR_BASE + index % YEAR_SPREAD).toString(),
                genres = listOf(if (index % 2 == 0) "Comedy" else "Drama"),
                magnets = listOf(CatalogMagnetDto(quality = "720p", magnet = magnet(index))),
            )
        },
    )

    /** [entries] as `catalog.json` bytes, laid out the way the publisher writes them. */
    fun json(entries: List<CatalogEntryDto>): ByteArray = CatalogueJson.catalogWriter
        .encodeToString(ListSerializer(CatalogEntryDto.serializer()), entries)
        .encodeToByteArray()

    /** Writes and signs a release into `outDir/torfilx-catalogue-<version>/`. */
    fun writeRelease(
        outDir: File,
        version: Long,
        entries: List<CatalogEntryDto> = entries(3),
        seed: ByteArray = CatalogueTestKeys.SEED,
        minVersionCode: Int? = null,
        publishedAtMs: Long = FIXED_PUBLISHED_AT_MS,
    ): CatalogueReleaseWriter.Written =
        CatalogueReleaseWriter.write(json(entries), version, publishedAtMs, seed, outDir, minVersionCode)
}
