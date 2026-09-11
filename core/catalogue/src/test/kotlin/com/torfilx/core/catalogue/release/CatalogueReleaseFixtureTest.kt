package com.torfilx.core.catalogue.release

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.torfilx.core.catalogue.format.CatalogRelease
import com.torfilx.core.catalogue.testing.CatalogueTestKeys
import com.torfilx.core.catalogue.testing.TestCatalogues
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * A release written once and committed, checked by today's verifier.
 *
 * The round-trip tests elsewhere write a release and verify it with the same build, so a change that
 * broke the writer and the verifier in the same way would pass them. This one cannot be fooled like
 * that: releases already published to the swarm look like this file, and they must keep verifying.
 *
 * The compressed bytes are not compared with a fresh write, because deflate output legitimately differs
 * between zlib builds (the JDK on Windows and on Linux ship different ones). The decompressed content
 * is compared instead, which is what the format actually promises.
 *
 * To rebuild it after a deliberate format change, run the tests once with TORFILX_REGENERATE_FIXTURES=1.
 */
class CatalogueReleaseFixtureTest {

    private val fixtureParent = File("src/test/resources/release-fixture")
    private val root = File(fixtureParent, CatalogRelease.rootDirName(FIXTURE_VERSION))

    @Test
    fun `the committed release still verifies`() {
        assertWithMessage("fixture missing at ${root.absolutePath}").that(root.isDirectory).isTrue()

        val result = CatalogueTestKeys.verifier().verify(root, appVersionCode = 1)

        val ok = result as? CatalogueReleaseVerifier.Result.Ok ?: throw AssertionError("expected Ok, was $result")
        assertThat(ok.manifest.catalogVersion).isEqualTo(FIXTURE_VERSION)
        assertThat(ok.manifest.publishedAtMs).isEqualTo(TestCatalogues.FIXED_PUBLISHED_AT_MS)
        assertThat(ok.entries).isEqualTo(TestCatalogues.entries(FIXTURE_TITLES))
    }

    @Test
    fun `the committed catalogue is exactly what the writer lays out today`() {
        val decompressed = GZIPInputStream(File(root, CatalogRelease.CATALOG_GZ).inputStream()).use { it.readBytes() }
        assertThat(decompressed.decodeToString())
            .isEqualTo(TestCatalogues.json(TestCatalogues.entries(FIXTURE_TITLES)).decodeToString())
    }

    @Test
    fun `regenerate the committed release`() {
        assumeTrue(System.getenv("TORFILX_REGENERATE_FIXTURES") == "1")
        root.deleteRecursively()
        TestCatalogues.writeRelease(fixtureParent, FIXTURE_VERSION, TestCatalogues.entries(FIXTURE_TITLES))
    }

    private companion object {
        const val FIXTURE_VERSION = 7L
        const val FIXTURE_TITLES = 4
    }
}
