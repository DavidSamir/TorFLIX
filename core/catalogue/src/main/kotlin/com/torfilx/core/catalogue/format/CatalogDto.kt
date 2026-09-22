package com.torfilx.core.catalogue.format

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One title as it appears in `catalog.json`: a film, or a show with seasons of episodes.
 *
 * The schema is the one the app has always shipped, plus [id], and for shows [type] and [seasons].
 * Every field is optional or defaulted so a hand-edited file keeps loading; the stricter rules a
 * *published* release must satisfy live in [CatalogContentRules].
 *
 * Property order is the order the publisher writes fields in, and defaults are never written. A film
 * carries no [type], [backdropUrl] or [seasons], so re-writing a films-only catalogue reproduces it
 * byte for byte: adding shows to the format changes nothing about the films already published.
 *
 * Inside a show, an episode's name is `"name"`, never `"title"`. The release format counts `"title"`
 * keys in the raw bytes and requires that count to equal the number of top-level entries; one
 * `"title"` per entry is what keeps that check meaningful with shows in the file.
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
    /** [TYPE_MOVIE] (also when absent) or [TYPE_SHOW]. Anything else is refused. */
    val type: String? = null,
    val title: String = "",
    val year: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    /** A 16:9 image for backgrounds. Optional; [imageUrl] stands in when absent. */
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val magnets: List<CatalogMagnetDto> = emptyList(),
    /** Shows only. */
    val seasons: List<CatalogSeasonDto> = emptyList(),
) {
    /** True for a show. A film has no type, or `movie`. */
    val isShow: Boolean get() = type?.trim().equals(TYPE_SHOW, ignoreCase = true)

    /** False for a type this build does not know, which the app skips and the publisher refuses. */
    val hasKnownType: Boolean
        get() = type == null || type.trim().lowercase() in KNOWN_TYPES

    companion object {
        const val TYPE_MOVIE = "movie"
        const val TYPE_SHOW = "show"
        private val KNOWN_TYPES = setOf(TYPE_MOVIE, TYPE_SHOW)
    }
}

@Serializable
data class CatalogMagnetDto(
    val quality: String? = null,
    val magnet: String = "",
)

/** One season of a show. Season `0` is Specials. */
@Serializable
data class CatalogSeasonDto(
    val number: Int? = null,
    val name: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    /** Whole-season torrents. An episode may be played out of one by picking its file. */
    val packs: List<CatalogMagnetDto> = emptyList(),
    val episodes: List<CatalogEpisodeDto> = emptyList(),
)

/** One episode. Its [id] is what the viewer's progress on it is stored under. */
@Serializable
data class CatalogEpisodeDto(
    val id: String? = null,
    val number: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val runtimeMinutes: Int? = null,
    /** ISO date, `1959-10-02`. Display only. */
    val airDate: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val magnets: List<CatalogMagnetDto> = emptyList(),
)
