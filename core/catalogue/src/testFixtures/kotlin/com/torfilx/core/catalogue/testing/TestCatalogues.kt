package com.torfilx.core.catalogue.testing

import com.torfilx.core.catalogue.CatalogueJson
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogEpisodeDto
import com.torfilx.core.catalogue.format.CatalogIds
import com.torfilx.core.catalogue.format.CatalogMagnetDto
import com.torfilx.core.catalogue.format.CatalogSeasonDto
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

    /**
     * A show entry, unpinned: [seasons] regular seasons of [episodesPerSeason] episodes each, plus a
     * one-episode Specials season when [withSpecials]. Every episode has its own well-formed magnet,
     * with info hashes that never collide with [entries]' films or another show's.
     */
    fun show(
        title: String = "Fixture Show",
        year: String = "1959",
        seasons: Int = 2,
        episodesPerSeason: Int = 3,
        withSpecials: Boolean = false,
        showIndex: Int = 0,
        genres: List<String> = listOf("Drama"),
    ): CatalogEntryDto {
        fun season(number: Int, count: Int) = CatalogSeasonDto(
            number = number,
            name = if (number == 0) "Specials" else "Season $number",
            episodes = (1..count).map { episode ->
                CatalogEpisodeDto(
                    number = episode,
                    name = "Episode $episode of season $number",
                    runtimeMinutes = 25,
                    magnets = listOf(
                        CatalogMagnetDto(
                            quality = "720p",
                            magnet = magnet(SHOW_HASH_BASE + showIndex * SHOW_HASH_STRIDE + number * SEASON_HASH_STRIDE + episode),
                        ),
                    ),
                )
            },
        )
        return CatalogEntryDto(
            type = CatalogEntryDto.TYPE_SHOW,
            title = title,
            year = year,
            genres = genres,
            seasons = (1..seasons).map { season(it, episodesPerSeason) } + if (withSpecials) listOf(season(0, 1)) else emptyList(),
        )
    }

    /** [films] films followed by [shows] shows, all pinned: a mixed catalogue as the publisher writes it. */
    fun mixed(films: Int = 2, shows: Int = 1, seasons: Int = 2, episodesPerSeason: Int = 3): List<CatalogEntryDto> =
        CatalogIds.pin(
            entries(films).map { it.copy(id = null) } +
                (0 until shows).map { show(title = "Fixture Show ${it + 1}", seasons = seasons, episodesPerSeason = episodesPerSeason, showIndex = it) },
        )

    private const val SHOW_HASH_BASE = 100_000
    private const val SHOW_HASH_STRIDE = 10_000
    private const val SEASON_HASH_STRIDE = 100

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
