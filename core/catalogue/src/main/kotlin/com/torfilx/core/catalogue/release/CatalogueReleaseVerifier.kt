package com.torfilx.core.catalogue.release

import com.torfilx.core.catalogue.CatalogueJson
import com.torfilx.core.catalogue.Hex
import com.torfilx.core.catalogue.crypto.Ed25519Keys
import com.torfilx.core.catalogue.crypto.Ed25519Verifier
import com.torfilx.core.catalogue.crypto.Sha256
import com.torfilx.core.catalogue.format.CatalogContentRules
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogManifest
import com.torfilx.core.catalogue.format.CatalogRelease
import com.torfilx.core.catalogue.format.countDeclaredTitles
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * Decides whether a catalogue release on disk may be installed.
 *
 * The order of the checks is the point. Nothing beyond the manifest is read until the manifest's
 * signature has verified, and nothing is decompressed until the compressed file's size and hash match
 * that signed manifest. Someone able to hand the app arbitrary bytes gets, at worst, a signature check
 * on a file of a few kilobytes. A release from the real publisher still has to prove, down to the
 * title count, that it is exactly what was signed.
 *
 * The publisher tool's `verify` command runs this same class, so a release that passes on the
 * publisher's machine passes on the television.
 */
class CatalogueReleaseVerifier(
    private val verifier: Ed25519Verifier,
    private val json: Json = CatalogueJson.content,
) {

    sealed interface Result {
        data class Ok(val manifest: CatalogManifest, val entries: List<CatalogEntryDto>) : Result
        data class Rejected(val reason: CatalogueRejectReason, val detail: String) : Result
    }

    /** Verifies the release whose files sit directly in [releaseRoot]. Never throws for a bad release. */
    fun verify(releaseRoot: File, appVersionCode: Int): Result = try {
        verifyOrThrow(releaseRoot, appVersionCode)
    } catch (rejection: Rejection) {
        Result.Rejected(rejection.reason, rejection.detail)
    }

    private fun verifyOrThrow(root: File, appVersionCode: Int): Result.Ok {
        if (verifier.keyCount == 0) reject(CatalogueRejectReason.NO_TRUSTED_KEYS, "this build trusts no publisher key")
        if (!root.isDirectory) reject(CatalogueRejectReason.MISSING_FILE, "no release directory at ${root.path}")
        val manifestFile = File(root, CatalogRelease.MANIFEST)
        val signatureFile = File(root, CatalogRelease.SIGNATURE)
        val catalogFile = File(root, CatalogRelease.CATALOG_GZ)
        listOf(manifestFile, signatureFile, catalogFile).forEach { file ->
            if (!file.isFile) reject(CatalogueRejectReason.MISSING_FILE, "${file.name} is missing")
        }

        // 1. The signature, over the manifest exactly as it sits on disk. It is never re-serialised.
        if (manifestFile.length() > CatalogRelease.MAX_MANIFEST_BYTES) {
            reject(CatalogueRejectReason.SIZE_CAP, "the manifest is ${manifestFile.length()} bytes")
        }
        if (signatureFile.length() > CatalogRelease.MAX_SIGNATURE_FILE_BYTES) {
            reject(CatalogueRejectReason.BAD_SIGNATURE, "the signature file is ${signatureFile.length()} bytes")
        }
        val manifestBytes = manifestFile.readBytes()
        val signature = Hex.decodeOrNull(signatureFile.readText())
            ?: reject(CatalogueRejectReason.BAD_SIGNATURE, "the signature is not hexadecimal")
        if (signature.size != Ed25519Keys.SIGNATURE_BYTES) {
            reject(
                CatalogueRejectReason.BAD_SIGNATURE,
                "the signature is ${signature.size} bytes, not ${Ed25519Keys.SIGNATURE_BYTES}",
            )
        }
        if (!verifier.verify(manifestBytes, signature)) {
            reject(CatalogueRejectReason.BAD_SIGNATURE, "the manifest was not signed by a trusted publisher key")
        }

        // 2. What the signed manifest claims.
        val manifest = decodeManifest(manifestBytes)
        manifest.minVersionCode?.let { required ->
            if (appVersionCode < required) {
                reject(
                    CatalogueRejectReason.NEEDS_NEWER_APP,
                    "the release needs app build $required or later; this is build $appVersionCode",
                )
            }
        }
        if (manifest.gzBytes > CatalogRelease.MAX_GZ_BYTES || manifest.jsonBytes > CatalogRelease.MAX_JSON_BYTES) {
            reject(
                CatalogueRejectReason.SIZE_CAP,
                "the release is ${manifest.gzBytes} bytes compressed and ${manifest.jsonBytes} bytes " +
                    "decompressed; the limits are ${CatalogRelease.MAX_GZ_BYTES} and " +
                    "${CatalogRelease.MAX_JSON_BYTES}",
            )
        }

        // 3. The compressed file is exactly the one that was signed.
        if (catalogFile.length() != manifest.gzBytes) {
            reject(
                CatalogueRejectReason.SIZE_MISMATCH,
                "${CatalogRelease.CATALOG_GZ} is ${catalogFile.length()} bytes; the manifest says ${manifest.gzBytes}",
            )
        }
        val actualSha256 = Sha256.hex(catalogFile)
        if (actualSha256 != manifest.sha256.lowercase()) {
            reject(
                CatalogueRejectReason.SHA256_MISMATCH,
                "${CatalogRelease.CATALOG_GZ} hashes to $actualSha256; the manifest says ${manifest.sha256}",
            )
        }

        // 4. Decompression, bounded by the signed size.
        val catalogJson = gunzipExactly(catalogFile, manifest.jsonBytes)

        // 5. The content: every title the file declares must decode and meet the publishing rules.
        val declared = countDeclaredTitles(catalogJson)
        if (declared != manifest.titleCount) {
            reject(
                CatalogueRejectReason.TITLE_COUNT_MISMATCH,
                "catalog.json declares $declared titles; the manifest says ${manifest.titleCount}",
            )
        }
        val entries = try {
            json.decodeFromString(ListSerializer(CatalogEntryDto.serializer()), catalogJson.decodeToString())
        } catch (error: IllegalArgumentException) {
            reject(
                CatalogueRejectReason.INVALID_ENTRIES,
                "catalog.json does not decode: ${error.message?.take(DETAIL)}",
            )
        }
        if (entries.size != manifest.titleCount) {
            reject(
                CatalogueRejectReason.TITLE_COUNT_MISMATCH,
                "catalog.json decodes to ${entries.size} titles; the manifest says ${manifest.titleCount}",
            )
        }
        val problems = CatalogContentRules.problems(entries, declared, requireExplicitIds = true)
        if (problems.isNotEmpty()) reject(CatalogueRejectReason.INVALID_ENTRIES, problems.joinToString("; "))

        return Result.Ok(manifest, entries)
    }

    private fun decodeManifest(bytes: ByteArray): CatalogManifest {
        val text = bytes.decodeToString()
        // The schema is read on its own first, so a future format is reported as exactly that rather
        // than as a manifest that happens not to decode.
        val schema = runCatching { CatalogueJson.manifest.decodeFromString(SchemaProbe.serializer(), text) }
            .getOrNull()
            ?.schemaVersion
            ?: reject(CatalogueRejectReason.BAD_MANIFEST, "manifest.json has no readable schemaVersion")
        if (schema != CatalogRelease.SCHEMA_VERSION) {
            reject(
                CatalogueRejectReason.SCHEMA_UNSUPPORTED,
                "the release uses schema $schema; this build reads schema ${CatalogRelease.SCHEMA_VERSION}",
            )
        }
        val manifest = try {
            CatalogueJson.manifest.decodeFromString(CatalogManifest.serializer(), text)
        } catch (error: IllegalArgumentException) {
            reject(CatalogueRejectReason.BAD_MANIFEST, "manifest.json does not decode: ${error.message?.take(DETAIL)}")
        }
        val impossible = buildList {
            if (manifest.catalogVersion <= 0) add("catalogVersion ${manifest.catalogVersion}")
            if (manifest.titleCount !in 0..CatalogRelease.MAX_TITLES) add("titleCount ${manifest.titleCount}")
            if (!SHA256_HEX.matches(manifest.sha256)) add("sha256 \"${manifest.sha256.take(DETAIL)}\"")
            if (manifest.gzBytes <= 0) add("gzBytes ${manifest.gzBytes}")
            if (manifest.jsonBytes <= 0) add("jsonBytes ${manifest.jsonBytes}")
        }
        if (impossible.isNotEmpty()) {
            reject(CatalogueRejectReason.BAD_MANIFEST, "impossible manifest values: ${impossible.joinToString()}")
        }
        return manifest
    }

    /**
     * Decompresses into a buffer of exactly [expectedBytes], failing on a short or a long stream.
     *
     * The buffer is sized from the signed manifest, which has already been checked against the size
     * cap, so a gzip bomb can never grow past it.
     */
    private fun gunzipExactly(file: File, expectedBytes: Long): ByteArray {
        val out = ByteArray(expectedBytes.toInt())
        try {
            GZIPInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                var filled = 0
                while (filled < out.size) {
                    val read = input.read(out, filled, out.size - filled)
                    if (read < 0) break
                    filled += read
                }
                if (filled != out.size) {
                    reject(
                        CatalogueRejectReason.JSON_SIZE_MISMATCH,
                        "${CatalogRelease.CATALOG_GZ} decompresses to $filled bytes; the manifest says $expectedBytes",
                    )
                }
                if (input.read() != -1) {
                    reject(
                        CatalogueRejectReason.JSON_SIZE_MISMATCH,
                        "${CatalogRelease.CATALOG_GZ} decompresses to more than the $expectedBytes bytes " +
                            "the manifest says",
                    )
                }
            }
        } catch (error: IOException) {
            reject(
                CatalogueRejectReason.GZIP_INVALID,
                "${CatalogRelease.CATALOG_GZ} is not valid gzip: ${error.message}",
            )
        }
        return out
    }

    @Serializable
    private data class SchemaProbe(val schemaVersion: Int)

    /** Carries a rejection out of the nested checks. Cheap: no stack trace is ever needed. */
    private class Rejection(val reason: CatalogueRejectReason, val detail: String) : RuntimeException(detail) {
        override fun fillInStackTrace(): Throwable = this
    }

    private fun reject(reason: CatalogueRejectReason, detail: String): Nothing = throw Rejection(reason, detail)

    private companion object {
        const val DETAIL = 200
        val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")
    }
}
