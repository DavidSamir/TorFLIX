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
        assertThat(manifest.catalogVersion).isEqualTo(9L)
        assertThat(manifest.titleCount).isEqualTo(2016)
        assertThat(manifest.sha256).isEqualTo("bffbd5ebe39cfacc6ba0e2c986a3455e0bcacbc462a0944818f85feb845c06a8")
        assertWithMessage("catalog.json changed without rebuilding catalog-manifest.json")
            .that(manifest.sha256).isEqualTo(Sha256.hex(bytes))
        assertThat(manifest.jsonBytes).isEqualTo(bytes.size.toLong())
        assertThat(manifest.titleCount).isEqualTo(countDeclaredTitles(bytes))
    }

    @Test
    fun `every bundled title has a pinned id that survives publisher round trips`() {
        val entries = entries()

        assertThat(entries.filter { it.id == null }.map { it.title }).isEmpty()
        val episodes = entries.flatMap { entry -> entry.seasons.flatMap { it.episodes } }
        assertThat(episodes.filter { it.id == null }).isEmpty()
        // Published catalogues may choose explicit ids. Re-deriving them from renamed titles would
        // discard the publisher's stable ids and orphan progress already saved from that release.
        assertThat(CatalogIds.pin(entries)).isEqualTo(entries)
    }

    @Test
    fun `catalogue nine retains ids for returning titles`() {
        val entries = entries()
        val ids = entries.mapNotNull { it.id } + entries.flatMap { entry ->
            entry.seasons.flatMap { season -> season.episodes.mapNotNull { it.id } }
        }
        assertThat(ids).containsAtLeastElementsIn(
            listOf("catalog-the-office") + (1..6).map {
                "catalog-the-office-s01e${it.toString().padStart(2, '0')}"
            },
        )
    }

    @Test
    fun `re-writing the bundled catalogue reproduces it byte for byte`() {
        // The publisher decodes and re-encodes the catalogue when it builds a release. Adding fields to
        // the format for shows must not change a byte of what the films already are.
        val bytes = catalogFile.readBytes()
        val rewritten = CatalogueJson.catalogWriter
            .encodeToString(ListSerializer(CatalogEntryDto.serializer()), entries())
            .encodeToByteArray()
        assertThat(rewritten.decodeToString()).isEqualTo(bytes.decodeToString())
    }

    @Test
    fun `the bundled catalogue meets every rule a published release must meet`() {
        val bytes = catalogFile.readBytes()
        assertThat(CatalogContentRules.problems(entries(), countDeclaredTitles(bytes), requireExplicitIds = true)).isEmpty()
    }
}
