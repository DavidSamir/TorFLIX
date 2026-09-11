package com.torfilx.core.catalogue.format

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One title as it appears in `catalog.json`.
 *
 * The schema is the one the app has always shipped, plus [id]. Every field is optional or defaulted so
 * a hand-edited file keeps loading; the stricter rules a *published* release must satisfy live in
 * [CatalogContentRules].
 */
@Serializable
data class CatalogEntryDto(
    /**
     * The title's permanent identity, and the key its watch progress and My List entry are stored under.
     *
     * Optional in a hand-edited file, where it is derived from the title and year. Every published
     * release pins it, so renaming a film or correcting its year in a later catalogue cannot orphan
     * what a viewer has already watched or saved.
     */
    val id: String? = null,
    val title: String = "",
    val year: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val magnets: List<CatalogMagnetDto> = emptyList(),
)

@Serializable
data class CatalogMagnetDto(
    val quality: String? = null,
    val magnet: String = "",
)
