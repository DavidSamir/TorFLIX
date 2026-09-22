package com.torfilx.core.data.catalog

import com.torfilx.core.model.Episode
import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.MediaSource
import com.torfilx.core.model.Season
import kotlinx.coroutines.flow.StateFlow

/**
 * A catalogue entry after validation: a film with its playable magnets, or a show with its seasons.
 *
 * A show's own [sources] is always empty: what plays is an episode, and each episode's sources are in
 * [episodeSources] under its id.
 */
data class CatalogItem(
    val item: MediaItem,
    val sources: List<MediaSource>,
    /** Shows only, in display order: regular seasons ascending, Specials last. */
    val seasons: List<Season> = emptyList(),
    /** Shows only: every episode's sources, keyed by episode id. */
    val episodeSources: Map<String, List<MediaSource>> = emptyMap(),
)

/**
 * Something that can be played: a film, or one episode of a show.
 *
 * Progress, the player route and the "what plays next" logic all work in terms of [id]. A show's own
 * id is never a playable; it resolves through its next-up episode instead.
 */
sealed interface Playable {
    val id: String
    val sources: List<MediaSource>
    val runtimeMs: Long?

    /** The title shown for it: a film's own, or the show's. */
    val item: MediaItem

    data class Film(override val item: MediaItem, override val sources: List<MediaSource>) : Playable {
        override val id: String get() = item.id
        override val runtimeMs: Long? get() = item.runtimeMs
    }

    data class EpisodeOf(
        val show: MediaItem,
        val episode: Episode,
        override val sources: List<MediaSource>,
    ) : Playable {
        override val id: String get() = episode.id
        override val runtimeMs: Long? get() = episode.runtimeMs
        override val item: MediaItem get() = show
    }
}

/**
 * The film catalogue the app is showing.
 *
 * There is always exactly one catalogue in use: the copy bundled inside the APK, or a newer signed
 * release downloaded from the peer network. Every screen reads through this interface, so a newer
 * release can be swapped in while the app runs and every observer of [info] sees the change.
 *
 * Reads never touch the network. The first read parses from disk, and everything after that is served
 * from memory.
 */
interface Catalog {

    /** Which catalogue is in use. [CatalogueInfo.generation] changes every time it is swapped. */
    val info: StateFlow<CatalogueInfo>

    /** The catalogue in use, loading it on first call. */
    fun snapshot(): CatalogSnapshot

    /** Parses the catalogue eagerly, off the main thread. Safe to call more than once. */
    fun preload() {
        snapshot()
    }

    fun items(): List<CatalogItem> = snapshot().items

    /** Domain items only: the list every screen actually renders. */
    fun mediaItems(): List<MediaItem> = snapshot().mediaItems

    fun item(id: String): CatalogItem? = snapshot().item(id)

    /** A film or an episode by its id. Null for a show's own id, and for anything not in the catalogue. */
    fun playable(id: String): Playable? = snapshot().playable(id)

    /** A show's seasons in display order; empty for a film or an unknown id. */
    fun seasons(showId: String): List<Season> = item(showId)?.seasons.orEmpty()

    /** The sources of a film or an episode. Empty for a show: shows play through their episodes. */
    fun sourcesFor(id: String): List<MediaSource> = playable(id)?.sources.orEmpty()

    fun genres(): List<String> = snapshot().genres

    /** Case-insensitive title search over pre-lowered keys. */
    fun search(query: String, limit: Int): List<MediaItem> = snapshot().search(query, limit)

    /** Shows found by an episode's name; see [CatalogSnapshot.searchEpisodes]. */
    fun searchEpisodes(query: String, limit: Int, excluding: Set<String>): List<CatalogSnapshot.EpisodeMatch> =
        snapshot().searchEpisodes(query, limit, excluding)

    /** True when fewer titles were parsed than the source declares. The library is incomplete. */
    val isIncomplete: Boolean get() = snapshot().isIncomplete

    /** Titles the source declares, so a screen can say "N of M" rather than just "N". */
    fun declaredTitleCount(): Int = snapshot().declaredCount
}

/** Which catalogue is in use, for the screens that say so. */
data class CatalogueInfo(
    /** Increments whenever a different catalogue is swapped in. 0 until the first load. */
    val generation: Int = 0,
    /** The release number. The bundled copy carries one too; 0 means it has no manifest. */
    val version: Long = 0,
    val origin: CatalogueOrigin = CatalogueOrigin.BUNDLED,
    val titleCount: Int = 0,
    val publishedAtMs: Long? = null,
    /** When a downloaded release was installed; null for the bundled copy. */
    val installedAtMs: Long? = null,
)

enum class CatalogueOrigin {
    /** The copy shipped inside the APK. */
    BUNDLED,

    /** A signed release downloaded from the peer network. */
    FETCHED,
}
