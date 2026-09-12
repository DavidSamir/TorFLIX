package com.torfilx.core.catalogue.release

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.CatalogueJson
import com.torfilx.core.catalogue.Hex
import com.torfilx.core.catalogue.crypto.Ed25519Keys
import com.torfilx.core.catalogue.crypto.Ed25519Verifier
import com.torfilx.core.catalogue.crypto.Sha256
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogManifest
import com.torfilx.core.catalogue.format.CatalogRelease
import com.torfilx.core.catalogue.format.countDeclaredTitles
import com.torfilx.core.catalogue.testing.CatalogueTestKeys
import com.torfilx.core.catalogue.testing.TestCatalogues
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Every way a release can be wrong, and the one way it can be right.
 *
 * Tampering is simulated at each layer the verifier guards: who signed the manifest, what the manifest
 * says, whether the compressed file is the one it describes, and whether the catalogue inside meets the
 * publishing rules. A publisher's own mistakes are simulated by re-signing an edited manifest with the
 * trusted key, because a valid signature alone must never be enough.
 */
class CatalogueReleaseVerifierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val verifier = CatalogueTestKeys.verifier()

    private fun release(
        version: Long = 7,
        entries: List<CatalogEntryDto> = TestCatalogues.entries(3),
        seed: ByteArray = CatalogueTestKeys.SEED,
        minVersionCode: Int? = null,
    ): File = TestCatalogues.writeRelease(tmp.newFolder(), version, entries, seed, minVersionCode).releaseRoot

    private fun verify(root: File, appVersionCode: Int = APP_VERSION_CODE) = verifier.verify(root, appVersionCode)

    private fun assertRejected(result: CatalogueReleaseVerifier.Result, reason: CatalogueRejectReason) {
        if (result !is CatalogueReleaseVerifier.Result.Rejected) throw AssertionError("expected $reason but was $result")
        assertThat(result.reason).isEqualTo(reason)
        assertThat(result.detail).isNotEmpty()
    }

    private fun assertOk(result: CatalogueReleaseVerifier.Result): CatalogueReleaseVerifier.Result.Ok =
        result as? CatalogueReleaseVerifier.Result.Ok ?: throw AssertionError("expected Ok but was $result")

    private fun manifestOf(root: File): CatalogManifest = CatalogueJson.manifest
        .decodeFromString(CatalogManifest.serializer(), File(root, CatalogRelease.MANIFEST).readText())

    /** Rewrites the manifest through [edit] and signs it again with the trusted key: a publisher's mistake. */
    private fun resign(root: File, edit: (CatalogManifest) -> CatalogManifest) =
        signManifest(root, CatalogueReleaseWriter.encodeManifest(edit(manifestOf(root))))

    private fun signManifest(root: File, manifestBytes: ByteArray, seed: ByteArray = CatalogueTestKeys.SEED) {
        File(root, CatalogRelease.MANIFEST).writeBytes(manifestBytes)
        File(root, CatalogRelease.SIGNATURE).writeText(Hex.encode(Ed25519Keys.sign(seed, manifestBytes)))
    }

    /** A correctly signed release of arbitrary JSON, bypassing the writer's publishing rules. */
    private fun rawRelease(json: String, titleCount: Int = countDeclaredTitles(json.encodeToByteArray())): File {
        val bytes = json.encodeToByteArray()
        val root = File(tmp.newFolder(), CatalogRelease.rootDirName(7)).apply { mkdirs() }
        val gz = CatalogueReleaseWriter.gzip(bytes)
        File(root, CatalogRelease.CATALOG_GZ).writeBytes(gz)
        val manifest = CatalogManifest(
            schemaVersion = CatalogRelease.SCHEMA_VERSION,
            catalogVersion = 7,
            publishedAtMs = 0,
            titleCount = titleCount,
            sha256 = Sha256.hex(gz),
            gzBytes = gz.size.toLong(),
            jsonBytes = bytes.size.toLong(),
        )
        signManifest(root, CatalogueReleaseWriter.encodeManifest(manifest))
        return root
    }

    // --- The release that is right ---------------------------------------------------------------

    @Test
    fun `a release written by the publisher verifies with everything it claims`() {
        val entries = TestCatalogues.entries(5)
        val ok = assertOk(verify(release(version = 12, entries = entries)))

        assertThat(ok.manifest.catalogVersion).isEqualTo(12)
        assertThat(ok.manifest.titleCount).isEqualTo(5)
        assertThat(ok.manifest.publishedAtMs).isEqualTo(TestCatalogues.FIXED_PUBLISHED_AT_MS)
        assertThat(ok.entries).isEqualTo(entries)
    }

    @Test
    fun `during a key rotation a release from either trusted key verifies`() {
        val rotating = CatalogueReleaseVerifier(
            Ed25519Verifier(listOf(CatalogueTestKeys.OTHER_PUBLIC_KEY, CatalogueTestKeys.PUBLIC_KEY)),
        )
        assertOk(rotating.verify(release(seed = CatalogueTestKeys.OTHER_SEED), APP_VERSION_CODE))
        assertOk(rotating.verify(release(), APP_VERSION_CODE))
    }

    // --- Who signed ------------------------------------------------------------------------------

    @Test
    fun `a release signed by an untrusted key is rejected`() {
        assertRejected(verify(release(seed = CatalogueTestKeys.OTHER_SEED)), CatalogueRejectReason.BAD_SIGNATURE)
    }

    @Test
    fun `a build that trusts no key accepts nothing`() {
        val trustsNobody = CatalogueReleaseVerifier(Ed25519Verifier(emptyList()))
        assertRejected(trustsNobody.verify(release(), APP_VERSION_CODE), CatalogueRejectReason.NO_TRUSTED_KEYS)
    }

    @Test
    fun `editing a signed manifest breaks its signature`() {
        val root = release()
        val manifest = File(root, CatalogRelease.MANIFEST)
        val edited = manifest.readText().replace("\"titleCount\": 3", "\"titleCount\": 4")
        assertThat(edited).isNotEqualTo(manifest.readText())
        manifest.writeText(edited)

        assertRejected(verify(root), CatalogueRejectReason.BAD_SIGNATURE)
    }

    @Test
    fun `a signature that is not hexadecimal or has the wrong length is rejected`() {
        val notHex = release().also { File(it, CatalogRelease.SIGNATURE).writeText("not a signature") }
        assertRejected(verify(notHex), CatalogueRejectReason.BAD_SIGNATURE)

        val short = release().also { File(it, CatalogRelease.SIGNATURE).writeText("ab".repeat(63)) }
        assertRejected(verify(short), CatalogueRejectReason.BAD_SIGNATURE)
    }

    @Test
    fun `a release missing any of its files is rejected`() {
        CatalogRelease.RELEASE_FILES.forEach { name ->
            val root = release().also { File(it, name).delete() }
            assertRejected(verify(root), CatalogueRejectReason.MISSING_FILE)
        }
        assertRejected(verify(File(tmp.root, "no-such-release")), CatalogueRejectReason.MISSING_FILE)
    }

    // --- What the signed manifest says -----------------------------------------------------------

    @Test
    fun `a release that needs a newer app is refused by an older build and accepted by a newer one`() {
        val root = release(minVersionCode = 99)
        assertRejected(verify(root, appVersionCode = 98), CatalogueRejectReason.NEEDS_NEWER_APP)
        assertThat(assertOk(verify(root, appVersionCode = 99)).manifest.minVersionCode).isEqualTo(99)
    }

    @Test
    fun `an unknown release schema is refused as exactly that`() {
        val root = release().also { resign(it) { manifest -> manifest.copy(schemaVersion = 2) } }
        assertRejected(verify(root), CatalogueRejectReason.SCHEMA_UNSUPPORTED)
    }

    @Test
    fun `a signed manifest with impossible values is refused`() {
        listOf<(CatalogManifest) -> CatalogManifest>(
            { it.copy(catalogVersion = 0) },
            { it.copy(titleCount = -1) },
            { it.copy(sha256 = "abc") },
            { it.copy(jsonBytes = 0) },
            { it.copy(gzBytes = -4) },
        ).forEach { edit ->
            val root = release().also { resign(it, edit) }
            assertRejected(verify(root), CatalogueRejectReason.BAD_MANIFEST)
        }
    }

    @Test
    fun `a signed manifest that is not JSON is refused`() {
        val root = release().also { signManifest(it, "this is not json".encodeToByteArray()) }
        assertRejected(verify(root), CatalogueRejectReason.BAD_MANIFEST)
    }

    @Test
    fun `a release larger than the device will load is refused before anything is decompressed`() {
        val tooBig = release().also { resign(it) { manifest -> manifest.copy(jsonBytes = CatalogRelease.MAX_JSON_BYTES + 1) } }
        assertRejected(verify(tooBig), CatalogueRejectReason.SIZE_CAP)

        val hugeManifest = release().also {
            val padded = "{" + " ".repeat(CatalogRelease.MAX_MANIFEST_BYTES.toInt()) + "}"
            signManifest(it, padded.encodeToByteArray())
        }
        assertRejected(verify(hugeManifest), CatalogueRejectReason.SIZE_CAP)
    }

    // --- Whether the compressed catalogue is the one described -----------------------------------

    @Test
    fun `a catalogue altered after signing fails the hash check`() {
        val root = release()
        val gz = File(root, CatalogRelease.CATALOG_GZ)
        val bytes = gz.readBytes()
        val middle = bytes.size / 2
        bytes[middle] = (bytes[middle].toInt() xor 0xFF).toByte()
        gz.writeBytes(bytes)

        assertRejected(verify(root), CatalogueRejectReason.SHA256_MISMATCH)
    }

    @Test
    fun `a truncated catalogue fails the size check`() {
        val root = release()
        val gz = File(root, CatalogRelease.CATALOG_GZ)
        gz.writeBytes(gz.readBytes().copyOf(gz.length().toInt() - 1))

        assertRejected(verify(root), CatalogueRejectReason.SIZE_MISMATCH)
    }

    @Test
    fun `a signed decompressed size that is wrong in either direction is caught`() {
        val understated = release().also { resign(it) { manifest -> manifest.copy(jsonBytes = manifest.jsonBytes - 1) } }
        assertRejected(verify(understated), CatalogueRejectReason.JSON_SIZE_MISMATCH)

        val overstated = release().also { resign(it) { manifest -> manifest.copy(jsonBytes = manifest.jsonBytes + 1) } }
        assertRejected(verify(overstated), CatalogueRejectReason.JSON_SIZE_MISMATCH)
    }

    @Test
    fun `bytes that are not gzip are caught even when their signed hash matches`() {
        val root = release()
        val garbage = ByteArray(200) { (it * 7).toByte() }
        File(root, CatalogRelease.CATALOG_GZ).writeBytes(garbage)
        resign(root) { it.copy(sha256 = Sha256.hex(garbage), gzBytes = garbage.size.toLong()) }

        assertRejected(verify(root), CatalogueRejectReason.GZIP_INVALID)
    }

    @Test
    fun `a title count that disagrees with the catalogue is caught`() {
        val root = release().also { resign(it) { manifest -> manifest.copy(titleCount = 4) } }
        assertRejected(verify(root), CatalogueRejectReason.TITLE_COUNT_MISMATCH)
    }

    // --- What the catalogue holds ----------------------------------------------------------------

    @Test
    fun `a signed catalogue without ids is refused`() {
        assertRejected(verify(rawRelease("""[{"title":"A"},{"title":"B"}]""")), CatalogueRejectReason.INVALID_ENTRIES)
    }

    @Test
    fun `a signed catalogue with duplicate ids is refused`() {
        val root = rawRelease("""[{"id":"x","title":"A"},{"id":"x","title":"B"}]""")
        assertRejected(verify(root), CatalogueRejectReason.INVALID_ENTRIES)
    }

    @Test
    fun `a signed catalogue with a blank title is refused`() {
        val root = rawRelease("""[{"id":"a","title":"A"},{"id":"b","title":""}]""")
        assertRejected(verify(root), CatalogueRejectReason.INVALID_ENTRIES)
    }

    @Test
    fun `a signed catalogue that does not decode is refused`() {
        val root = rawRelease("""[{"id":"a","title":"A"},{"id":"b","title":"B" BROKEN""", titleCount = 2)
        assertRejected(verify(root), CatalogueRejectReason.INVALID_ENTRIES)
    }

    // --- The writer ------------------------------------------------------------------------------

    @Test
    fun `the writer refuses a catalogue it could not publish`() {
        val unpinned = """[{"title":"A"}]""".encodeToByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            CatalogueReleaseWriter.write(unpinned, 1, 0, CatalogueTestKeys.SEED, tmp.newFolder())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CatalogueReleaseWriter.write(
                TestCatalogues.json(TestCatalogues.entries(1)),
                0,
                0,
                CatalogueTestKeys.SEED,
                tmp.newFolder(),
            )
        }
    }

    @Test
    fun `the writer never changes a release that already exists`() {
        val out = tmp.newFolder()
        TestCatalogues.writeRelease(out, 3)
        assertThrows(IllegalArgumentException::class.java) {
            TestCatalogues.writeRelease(out, 3, TestCatalogues.entries(4))
        }
    }

    @Test
    fun `the same catalogue and version always produce the same release bytes`() {
        val first = TestCatalogues.writeRelease(tmp.newFolder(), 5).releaseRoot
        val second = TestCatalogues.writeRelease(tmp.newFolder(), 5).releaseRoot
        CatalogRelease.RELEASE_FILES.forEach { name ->
            assertThat(File(second, name).readBytes()).isEqualTo(File(first, name).readBytes())
        }
    }

    private companion object {
        const val APP_VERSION_CODE = 16
    }
}
