package com.torfilx.core.data.catalog

import com.torfilx.core.model.MediaItem
import com.torfilx.core.model.MediaSource
import kotlinx.coroutines.flow.StateFlow

/** A catalogue entry with its playable magnets, after validation. */
data class CatalogItem(
    val item: MediaItem,
    val sources: List<MediaSource>,
)

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

    fun sourcesFor(id: String): List<MediaSource> = item(id)?.sources.orEmpty()

    fun genres(): List<String> = snapshot().genres

    /** Case-insensitive title search over pre-lowered keys. */
    fun search(query: String, limit: Int): List<MediaItem> = snapshot().search(query, limit)

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
