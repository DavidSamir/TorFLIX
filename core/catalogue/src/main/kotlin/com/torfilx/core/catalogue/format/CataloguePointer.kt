package com.torfilx.core.catalogue.format

/**
 * The value published in the DHT: which torrent holds the current release.
 *
 * It is deliberately tiny (about 65 bytes bencoded, against a 1000-byte limit for DHT items) and it
 * carries [catalogVersion] itself. The DHT's own sequence number cannot be chosen by the publisher,
 * because libtorrent increments whatever it finds, so the release number travels inside the value.
 *
 * Nothing is trusted from a pointer beyond "where to look". The release it names is verified in full,
 * and that release's signed manifest must carry the same version.
 */
data class CataloguePointer(
    val format: Int,
    /** The release torrent's info hash: 40 lower-case hex characters. */
    val infoHash: String,
    val catalogVersion: Long,
) {
    init {
        require(isValidInfoHash(infoHash)) { "Not a lower-case hex info hash: $infoHash" }
        require(catalogVersion > 0) { "Catalogue versions start at 1, got $catalogVersion" }
    }

    /** The DHT value as a bencodable dictionary: integers are [Long], strings are [String]. */
    fun toMap(): Map<String, Any> = linkedMapOf(
        KEY_VERSION to catalogVersion,
        KEY_INFO_HASH to infoHash,
        KEY_FORMAT to format.toLong(),
    )

    companion object {
        const val FORMAT = 1
        const val KEY_FORMAT = "v"
        const val KEY_INFO_HASH = "ih"
        const val KEY_VERSION = "cv"

        private val INFO_HASH = Regex("^[0-9a-f]{40}$")

        fun isValidInfoHash(value: String): Boolean = INFO_HASH.matches(value)

        /** Reads a DHT value back; null when it is not a well-formed pointer. */
        fun fromMap(values: Map<String, Any?>): CataloguePointer? {
            val format = (values[KEY_FORMAT] as? Number)?.toInt() ?: return null
            val infoHash = (values[KEY_INFO_HASH] as? String)?.lowercase() ?: return null
            val version = (values[KEY_VERSION] as? Number)?.toLong() ?: return null
            if (!isValidInfoHash(infoHash) || version <= 0) return null
            return CataloguePointer(format, infoHash, version)
        }
    }
}
