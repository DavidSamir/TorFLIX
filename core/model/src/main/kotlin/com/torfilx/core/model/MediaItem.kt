package com.torfilx.core.model

/**
 * Artwork URLs from the catalogue.
 */
data class Images(
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val thumb: String? = null,
)

/**
 * A title in the catalogue: a film, or a show.
 *
 * A film is one playable thing and its [id] is what progress is stored under. A show is never
 * played itself: its episodes are ([Episode.id]), and the show's [id] is what My List stores and what
 * its details screen opens. Everything that renders a card or a row works on this one type; only the
 * details screen and the player need to know which kind they have.
 *
 * Timestamps are epoch milliseconds (UTC): primitive on purpose, so the model stays free of
 * date-library churn and is trivially storable in Room.
 */
data class MediaItem(
    val id: String,
    val title: String,
    val sortTitle: String = title,
    val year: Int? = null,
    val runtimeMs: Long? = null,
    val communityRating: Double? = null,
    val ageRating: String? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val images: Images = Images(),
    val addedAtMs: Long? = null,
    val updatedAtMs: Long = 0L,
    val kind: MediaKind = MediaKind.MOVIE,
    /** Shows only: regular seasons, specials not counted. */
    val seasonCount: Int = 0,
    /** Shows only: every episode, specials included. */
    val episodeCount: Int = 0,
) {
    val isShow: Boolean get() = kind == MediaKind.SHOW
}
