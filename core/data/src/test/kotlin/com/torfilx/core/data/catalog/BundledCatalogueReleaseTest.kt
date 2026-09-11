package com.torfilx.core.data.catalog

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.torfilx.core.catalogue.CatalogueJson
import com.torfilx.core.catalogue.crypto.Sha256
import com.torfilx.core.catalogue.format.BundledCatalogManifest
import com.torfilx.core.catalogue.format.CatalogContentRules
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogIds
import com.torfilx.core.catalogue.format.CatalogRelease
import com.torfilx.core.catalogue.format.countDeclaredTitles
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Test
import java.io.File

/**
 * The catalogue inside the APK, checked as a release.
 *
 * Its manifest decides whether a catalogue from the swarm is newer, and its pinned ids are what watch
 * progress and My List hang off. A hand edit to `catalog.json` that forgets to rebuild the manifest,
 * or that drops or changes an id, fails here instead of on a television.
 */
class BundledCatalogueReleaseTest {

    private val catalogFile = File("src/main/assets/${CatalogRelease.BUNDLED_CATALOG_ASSET}")
    private val manifestFile = File("src/main/assets/${CatalogRelease.BUNDLED_MANIFEST_ASSET}")

    private fun entries(): List<CatalogEntryDto> =
        CatalogueJson.content.decodeFromString(ListSerializer(CatalogEntryDto.serializer()), catalogFile.readText())

    @Test
    fun `the bundled catalogue ships with a manifest that describes exactly it`() {
        assertWithMessage(
            "${manifestFile.path} is missing or stale: rebuild it with the publisher's build command and --asset-dir",
        ).that(manifestFile.isFile).isTrue()
        val bytes = catalogFile.readBytes()

        val manifest = CatalogueJson.manifest.decodeFromString(BundledCatalogManifest.serializer(), manifestFile.readText())

        assertThat(manifest.schemaVersion).isEqualTo(CatalogRelease.SCHEMA_VERSION)
        assertThat(manifest.catalogVersion).isAtLeast(1L)
        assertWithMessage("catalog.json changed without rebuilding catalog-manifest.json")
            .that(manifest.sha256).isEqualTo(Sha256.hex(bytes))
        assertThat(manifest.jsonBytes).isEqualTo(bytes.size.toLong())
        assertThat(manifest.titleCount).isEqualTo(countDeclaredTitles(bytes))
    }

    @Test
    fun `every bundled title has a pinned id, the same id the app derived before ids were pinned`() {
        val entries = entries()

        assertThat(entries.filter { it.id == null }.map { it.title }).isEmpty()
        // Deriving again from scratch must give exactly the pinned ids, or progress saved under the old
        // derived ids would be orphaned by the build that introduced pinning.
        val derived = CatalogIds.pin(entries.map { it.copy(id = null) }).map { it.id }
        assertThat(entries.map { it.id }).isEqualTo(derived)
    }

    @Test
    fun `the bundled catalogue meets every rule a published release must meet`() {
        val bytes = catalogFile.readBytes()
        assertThat(CatalogContentRules.problems(entries(), countDeclaredTitles(bytes), requireExplicitIds = true)).isEmpty()
    }
}
