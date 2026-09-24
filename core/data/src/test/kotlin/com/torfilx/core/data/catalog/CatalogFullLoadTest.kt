package com.torfilx.core.data.catalog

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Test
import java.io.File

/**
 * Loads the **real shipped catalogue**, not a fixture, and a full-size catalogue at the scale the app
 * is built for.
 *
 * The reported symptom was "I don't see all the movies, I can see about 25". Ruling the data in or
 * out was the first thing that had to happen, and a three-entry fixture cannot do that: the parser
 * is a streaming one that keeps whatever it decoded before an error and silently stops, so a single
 * malformed entry half way down the file would truncate the library in exactly the way described
 * while every fixture-based test still passed.
 *
 * The bundled catalogue is whatever public-domain release the APK was cut with, which can be a
 * handful of titles, so the checks on it are about correctness, not size. Size is checked on
 * [FullSizeCatalogue.FILE]: two thousand generated films, the scale a catalogue release reaches
 * a television at, so a parser that slows down or stops early on a big file still fails here.
 */
class CatalogFullLoadTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }

    /** The asset as it sits in the module, so the test reads the same bytes the app packages. */
    private val catalogFile = File("src/main/assets/catalog.json")

    private val largeFile = FullSizeCatalogue.FILE

    @Test
    fun `the shipped catalogue file exists and is not empty`() {
        assertThat(catalogFile.exists()).isTrue()
        assertThat(parseCatalog(catalogFile.readText(), json)).isNotEmpty()
    }

    @Test
    fun `every entry in the shipped catalogue is parsed`() {
        val raw = catalogFile.readText()
        // Counting the top-level entries with a plain JSON reader is independent of the parser under
        // test, so a parser that stops early cannot make this assertion agree with it.
        val declared = json.parseToJsonElement(raw).jsonArray.size
        assertThat(parseCatalog(raw, json)).hasSize(declared)
    }

    @Test
    fun `ids are unique across the whole shipped catalogue, episodes included`() {
        // Duplicate ids are Compose list keys: a collision is a hard crash in the grid or the episode
        // list, and the disambiguation path is not a corner case in a hand-assembled file.
        val items = parseCatalog(catalogFile.readText(), json)
        val ids = items.map { it.item.id } + items.flatMap { it.episodeSources.keys }
        assertThat(ids.toSet()).hasSize(ids.size)
    }

    @Test
    fun `every title in the shipped catalogue can be played`() {
        // A new install starts from this file, before any catalogue has arrived from the swarm. A film
        // with no valid magnet, or a show with an episode that has none, is a dead card on a fresh
        // television.
        val items = parseCatalog(catalogFile.readText(), json)
        items.forEach { entry ->
            if (entry.seasons.isEmpty()) {
                assertWithMessage("${entry.item.title} has no playable source").that(entry.sources).isNotEmpty()
            } else {
                assertWithMessage("${entry.item.title} has no episodes").that(entry.episodeSources).isNotEmpty()
                entry.episodeSources.forEach { (episodeId, sources) ->
                    assertWithMessage("$episodeId has no playable source").that(sources).isNotEmpty()
                }
            }
        }
    }

    @Test
    fun `a full-size catalogue loads every one of its two thousand titles`() {
        val raw = largeFile.readText()
        // Counting the "title" keys is independent of the parser under test (the fixture is films only).
        val declared = Regex("\"title\"\\s*:").findAll(raw).count()
        val parsed = parseCatalog(raw, json)

        assertThat(declared).isAtLeast(2_000)
        assertThat(parsed).hasSize(declared)
        val ids = parsed.map { it.item.id }
        assertThat(ids.toSet()).hasSize(ids.size)
    }

    @Test
    fun `a full-size catalogue exposes enough genres to fill the home rows`() {
        // Home builds one row per genre; if this collapses, Home looks empty for reasons that have
        // nothing to do with the number of titles.
        val genres = parseCatalog(largeFile.readText(), json).flatMap { it.item.genres }.distinct()
        assertThat(genres.size).isAtLeast(5)
    }

    @Test
    fun `parsing a full-size catalogue is fast enough for a stick`() {
        // The catalogue is parsed on a background thread at launch, but it also blocks the first
        // screen that asks for it. On a 2016 Fire TV Stick this budget is roughly 10x what the
        // desktop JVM takes, which is the headroom this guards.
        val raw = largeFile.readText()
        val startedAt = System.nanoTime()
        parseCatalog(raw, json)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertThat(elapsedMs).isLessThan(3_000)
    }
}
