package com.torfilx.core.catalogue.release

import com.torfilx.core.catalogue.CatalogueJson
import com.torfilx.core.catalogue.Hex
import com.torfilx.core.catalogue.crypto.Ed25519Keys
import com.torfilx.core.catalogue.crypto.Sha256
import com.torfilx.core.catalogue.format.BundledCatalogManifest
import com.torfilx.core.catalogue.format.CatalogContentRules
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogManifest
import com.torfilx.core.catalogue.format.CatalogRelease
import com.torfilx.core.catalogue.format.countDeclaredTitles
import kotlinx.serialization.builtins.ListSerializer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Writes and signs a catalogue release. Used by the publisher tool and by tests, never by the app.
 *
 * It refuses any catalogue the verifier would refuse, so every release it signs is installable, and
 * it is deterministic: the same catalogue, version and timestamp always produce the same bytes, and so
 * the same torrent info hash.
 */
object CatalogueReleaseWriter {

    class Written(val manifest: CatalogManifest, val releaseRoot: File)

    /**
     * Writes release [catalogVersion] of [catalogJson] into `outDir/torfilx-catalogue-<version>/`.
     *
     * @param seed the publisher's 32-byte private seed.
     * @throws IllegalArgumentException when the catalogue breaks a publishing rule, or the release
     *   directory already holds files (a published release never changes; use a new version).
     */
    fun write(
        catalogJson: ByteArray,
        catalogVersion: Long,
        publishedAtMs: Long,
        seed: ByteArray,
        outDir: File,
        minVersionCode: Int? = null,
    ): Written {
        require(catalogVersion > 0) { "Catalogue versions start at 1, got $catalogVersion" }
        require(catalogJson.size <= CatalogRelease.MAX_JSON_BYTES) {
            "catalog.json is ${catalogJson.size} bytes, over the ${CatalogRelease.MAX_JSON_BYTES}-byte limit"
        }
        val entries = CatalogueJson.content.decodeFromString(
            ListSerializer(CatalogEntryDto.serializer()),
            catalogJson.decodeToString(),
        )
        val problems = CatalogContentRules.problems(entries, countDeclaredTitles(catalogJson), requireExplicitIds = true)
        require(problems.isEmpty()) { "This catalogue cannot be published:\n  " + problems.joinToString("\n  ") }

        val root = File(outDir, CatalogRelease.rootDirName(catalogVersion))
        require(!root.exists() || root.list().isNullOrEmpty()) {
            "${root.path} already holds a release. A published release never changes: use a new version."
        }
        check(root.isDirectory || root.mkdirs()) { "Could not create ${root.path}" }

        val gz = gzip(catalogJson)
        require(gz.size <= CatalogRelease.MAX_GZ_BYTES) {
            "The compressed catalogue is ${gz.size} bytes, over the ${CatalogRelease.MAX_GZ_BYTES}-byte limit"
        }
        File(root, CatalogRelease.CATALOG_GZ).writeBytes(gz)

        val manifest = CatalogManifest(
            schemaVersion = CatalogRelease.SCHEMA_VERSION,
            catalogVersion = catalogVersion,
            publishedAtMs = publishedAtMs,
            titleCount = entries.size,
            sha256 = Sha256.hex(gz),
            gzBytes = gz.size.toLong(),
            jsonBytes = catalogJson.size.toLong(),
            minVersionCode = minVersionCode,
        )
        val manifestBytes = encodeManifest(manifest)
        File(root, CatalogRelease.MANIFEST).writeBytes(manifestBytes)
        File(root, CatalogRelease.SIGNATURE).writeText(Hex.encode(Ed25519Keys.sign(seed, manifestBytes)) + "\n")
        return Written(manifest, root)
    }

    /** The manifest bytes exactly as they are signed and written. */
    fun encodeManifest(manifest: CatalogManifest): ByteArray =
        (CatalogueJson.manifest.encodeToString(CatalogManifest.serializer(), manifest) + "\n").encodeToByteArray()

    /** Describes a bundled `catalog.json` so the app knows which release the APK carries. */
    fun bundledManifest(catalogJson: ByteArray, catalogVersion: Long, publishedAtMs: Long): BundledCatalogManifest {
        val entries = CatalogueJson.content.decodeFromString(
            ListSerializer(CatalogEntryDto.serializer()),
            catalogJson.decodeToString(),
        )
        return BundledCatalogManifest(
            schemaVersion = CatalogRelease.SCHEMA_VERSION,
            catalogVersion = catalogVersion,
            publishedAtMs = publishedAtMs,
            titleCount = entries.size,
            sha256 = Sha256.hex(catalogJson),
            jsonBytes = catalogJson.size.toLong(),
        )
    }

    fun encodeBundledManifest(manifest: BundledCatalogManifest): String =
        CatalogueJson.manifest.encodeToString(BundledCatalogManifest.serializer(), manifest) + "\n"

    /** Gzip with a fixed header (no timestamp), so identical input gives identical output. */
    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / GZIP_RATIO_GUESS + 1)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private const val GZIP_RATIO_GUESS = 8
}
