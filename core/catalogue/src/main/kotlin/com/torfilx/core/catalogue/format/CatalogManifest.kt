package com.torfilx.core.catalogue.format

import kotlinx.serialization.Serializable

/**
 * What a catalogue release claims about itself, signed by the publisher byte for byte as written.
 *
 * Every number here is checked against the release's actual contents before the app uses it, so the
 * manifest is a promise the verifier holds the release to, not something taken on trust.
 */
@Serializable
data class CatalogManifest(
    /** The release format. The app refuses a schema it does not know rather than guessing. */
    val schemaVersion: Int,
    /** Monotonic release number. A higher number replaces a lower one; a tie keeps what is installed. */
    val catalogVersion: Long,
    val publishedAtMs: Long,
    /** Entries in `catalog.json`, which must also equal the `"title"` keys its raw bytes declare. */
    val titleCount: Int,
    /** SHA-256 of `catalog.json.gz`, lower-case hex. */
    val sha256: String,
    val gzBytes: Long,
    /** Size of `catalog.json` once decompressed. Decompression stops the moment it would exceed this. */
    val jsonBytes: Long,
    /** The oldest app build (versionCode) that understands this release, when the publisher sets one. */
    val minVersionCode: Int? = null,
)

/**
 * Describes the catalogue bundled inside the APK, shipped next to it as `catalog-manifest.json`.
 *
 * Not signed: it travels inside the APK, which is itself signed. Its only job is to say which release
 * the bundled copy is, so the app can tell whether a catalogue fetched from the swarm is newer.
 */
@Serializable
data class BundledCatalogManifest(
    val schemaVersion: Int,
    val catalogVersion: Long,
    val publishedAtMs: Long,
    val titleCount: Int,
    /** SHA-256 of the bundled `catalog.json`, so a test can prove the two files were built together. */
    val sha256: String,
    val jsonBytes: Long,
)

/** Names, limits and constants of the release format. */
object CatalogRelease {
    const val SCHEMA_VERSION = 1

    const val CATALOG_GZ = "catalog.json.gz"
    const val MANIFEST = "manifest.json"
    const val SIGNATURE = "manifest.sig"

    const val BUNDLED_CATALOG_ASSET = "catalog.json"
    const val BUNDLED_MANIFEST_ASSET = "catalog-manifest.json"

    /** Salt of the DHT item that points at the current release. A different salt is a separate feed. */
    const val DHT_SALT = "torfilx-catalog-v1"

    /*
     * Size limits, chosen for the floor device. A 2016 Fire TV Stick has roughly a 128 MB heap, and a
     * decompressed catalogue is held as bytes, then as a string, then as objects. Today's catalogue is
     * 2 MB of JSON and a few hundred KB compressed, so these leave room for a library several times
     * larger while turning an absurd release into a clean rejection instead of an out-of-memory crash.
     */
    const val MAX_GZ_BYTES: Long = 4L * 1024 * 1024
    const val MAX_JSON_BYTES: Long = 12L * 1024 * 1024
    const val MAX_MANIFEST_BYTES: Long = 16L * 1024
    const val MAX_SIGNATURE_FILE_BYTES: Long = 1024
    const val MAX_TITLES = 50_000

    /** The largest torrent the app will download as a catalogue release. */
    const val MAX_TORRENT_BYTES: Long = MAX_GZ_BYTES + MAX_MANIFEST_BYTES + MAX_SIGNATURE_FILE_BYTES

    /** The only files a release torrent may contain. */
    val RELEASE_FILES: Set<String> = setOf(CATALOG_GZ, MANIFEST, SIGNATURE)

    private const val ROOT_PREFIX = "torfilx-catalogue-"

    /** The directory, and torrent name, of release [catalogVersion]. */
    fun rootDirName(catalogVersion: Long): String = "$ROOT_PREFIX$catalogVersion"

    fun isReleaseRootName(name: String): Boolean {
        if (!name.startsWith(ROOT_PREFIX)) return false
        val digits = name.removePrefix(ROOT_PREFIX)
        return digits.isNotEmpty() && digits.all { it in '0'..'9' }
    }

    fun dhtSalt(): ByteArray = DHT_SALT.encodeToByteArray()
}
