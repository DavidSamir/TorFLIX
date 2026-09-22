package com.torfilx.core.data.catalog

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.CatalogueJson
import com.torfilx.core.catalogue.format.CatalogIds
import com.torfilx.core.catalogue.testing.TestCatalogues
import com.torfilx.core.model.EpisodeFileMatcher
import com.torfilx.core.model.FileSelection
import com.torfilx.core.model.MediaKind
import org.junit.Test

/**
 * Shows through the app's real parser and the catalogue index built from it.
 *
 * The id tests matter most: an episode id is the key its progress is stored under, so the parser and
 * the publisher's pinning must derive exactly the same ids from the same file.
 */
class CatalogShowParseTest {

    private val json = CatalogueJson.content
    private val hashA = "0697bc07ebc5914085c2a3bce646509086bf6265"
    private val hashB = "bb5a1f6d17d3f8e01de20d42fb9860157a24456c"

    private fun parse(raw: String) = parseCatalog(raw, json)

    private fun snapshot(items: List<CatalogItem>) = CatalogSnapshot(CatalogueInfo(generation = 1), items, items.size)

    private val twilightZone = """
        [
          { "title": "The Kid", "year": "1921", "magnets": [{ "quality": "720p", "magnet": "magnet:?xt=urn:btih:$hashA" }] },
          {
            "type": "show", "title": "The Twilight Zone", "year": "1959",
            "image_url": "http://x/poster.jpg", "backdrop_url": "http://x/backdrop.jpg",
            "genres": ["Sci-Fi", "sci-fi", "Drama"],
            "seasons": [
              { "number": 0, "episodes": [{ "number": 1, "name": "Special" }] },
              { "number": 2, "name": "Season Two", "episodes": [
                  { "number": 1, "magnets": [{ "quality": "1080p", "magnet": "magnet:?xt=urn:btih:$hashB" }] }
              ] },
              { "number": 1, "episodes": [
                  { "number": 2, "name": "  ", "runtimeMinutes": 0, "airDate": "not a date",
                    "magnets": [{ "magnet": "magnet:?xt=urn:btih:broken" }] },
                  { "number": 1, "name": "Where Is Everybody?", "runtimeMinutes": 25, "airDate": "1959-10-02",
                    "image_url": "http://x/s01e01.jpg",
                    "magnets": [{ "quality": "720p", "magnet": "magnet:?xt=urn:btih:$hashA" }] }
              ] }
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun `a show parses into its seasons, regular ones in order and specials last`() {
        val show = parse(twilightZone)[1]

        assertThat(show.item.kind).isEqualTo(MediaKind.SHOW)
        assertThat(show.item.id).isEqualTo("show-the-twilight-zone-1959")
        assertThat(show.sources).isEmpty()
        assertThat(show.seasons.map { it.number }).containsExactly(1, 2, 0).inOrder()
        assertThat(show.seasons.map { it.name }).containsExactly("Season 1", "Season Two", "Specials").inOrder()
        assertThat(show.seasons[0].episodes.map { it.number }).containsExactly(1, 2).inOrder()
        assertThat(show.item.seasonCount).isEqualTo(2)
        assertThat(show.item.episodeCount).isEqualTo(4)
        assertThat(show.item.genres).containsExactly("Sci-Fi", "Drama").inOrder()
        assertThat(show.item.images.poster).isEqualTo("http://x/poster.jpg")
        assertThat(show.item.images.backdrop).isEqualTo("http://x/backdrop.jpg")
    }

    @Test
    fun `an episode carries its own details, sources and playability`() {
        val show = parse(twilightZone)[1]
        val pilot = show.seasons[0].episodes[0]
        val second = show.seasons[0].episodes[1]

        assertThat(pilot.id).isEqualTo("show-the-twilight-zone-1959-s01e01")
        assertThat(pilot.showId).isEqualTo(show.item.id)
        assertThat(pilot.name).isEqualTo("Where Is Everybody?")
        assertThat(pilot.runtimeMs).isEqualTo(25 * 60_000L)
        assertThat(pilot.airDateMs).isEqualTo(-323_481_600_000L) // 1959-10-02T00:00Z
        assertThat(pilot.image).isEqualTo("http://x/s01e01.jpg")
        assertThat(pilot.isPlayable).isTrue()
        assertThat(show.episodeSources.getValue(pilot.id).single().height).isEqualTo(720)

        // Blank name, zero runtime, a date that is not one, and only a malformed magnet.
        assertThat(second.name).isNull()
        assertThat(second.displayName).isEqualTo("Episode 2")
        assertThat(second.runtimeMs).isNull()
        assertThat(second.airDateMs).isNull()
        assertThat(second.isPlayable).isFalse()
        assertThat(show.episodeSources.getValue(second.id)).isEmpty()
    }

    @Test
    fun `a film keeps parsing exactly as before, and its backdrop falls back to its poster`() {
        val film = parse(twilightZone)[0]
        assertThat(film.item.kind).isEqualTo(MediaKind.MOVIE)
        assertThat(film.item.id).isEqualTo("catalog-the-kid-1921")
        assertThat(film.item.images.backdrop).isEqualTo(film.item.images.poster)
        assertThat(film.seasons).isEmpty()
    }

    @Test
    fun `the parser derives the same ids the publisher pins, films and episodes alike`() {
        val entries = listOf(
            TestCatalogues.show(title = "Same", year = "1960", showIndex = 0),
            TestCatalogues.show(title = "Same", year = "1960", showIndex = 1),
        ) + TestCatalogues.entries(2).map { it.copy(id = null) } + TestCatalogues.show(title = "Same", year = "1960", showIndex = 2)
        val pinned = CatalogIds.pin(entries)

        val parsed = parse(TestCatalogues.json(entries).decodeToString())

        val parsedIds = parsed.flatMap { item -> listOf(item.item.id) + item.seasons.flatMap { s -> s.episodes.map { it.id } } }
        val pinnedIds = pinned.flatMap { entry ->
            listOf(entry.id!!) + entry.seasons.sortedBy { it.number }.flatMap { s -> s.episodes.sortedBy { it.number }.map { it.id!! } }
        }
        assertThat(parsedIds).containsExactlyElementsIn(pinnedIds).inOrder()
        assertThat(parsedIds.toSet()).hasSize(parsedIds.size)
    }

    @Test
    fun `an entry of an unknown type is skipped without shifting anyone's id`() {
        val parsed = parse(
            """[
              {"type":"podcast","title":"Same","year":"1921"},
              {"title":"Same","year":"1921"},
              {"type":"SHOW","title":"S","year":"1990","seasons":[{"number":1,"episodes":[{"number":1}]}]}
            ]""",
        )
        assertThat(parsed.map { it.item.id }).containsExactly("catalog-same-1921", "show-s-1990").inOrder()
        assertThat(parsed[1].item.isShow).isTrue()
    }

    @Test
    fun `a film that carries seasons stays a film`() {
        val film = parse("""[{"title":"F","seasons":[{"number":1,"episodes":[{"number":1}]}]}]""").single()
        assertThat(film.item.kind).isEqualTo(MediaKind.MOVIE)
        assertThat(film.seasons).isEmpty()
    }

    @Test
    fun `two seasons with the same number are merged, and duplicate episodes keep distinct ids`() {
        val show = parse(
            """[{"type":"show","title":"S","year":"1990","seasons":[
              {"number":1,"episodes":[{"number":1},{"number":1}]},
              {"number":1,"episodes":[{"number":2}]}
            ]}]""",
        ).single()

        assertThat(show.seasons).hasSize(1)
        assertThat(show.seasons.single().episodes.map { it.id })
            .containsExactly("show-s-1990-s01e01", "show-s-1990-s01e01-2", "show-s-1990-s01e02").inOrder()
    }

    @Test
    fun `seasons and episodes without a usable number are skipped, and empty seasons dropped`() {
        val show = parse(
            """[{"type":"show","title":"S","year":"1990","seasons":[
              {"episodes":[{"number":1}]},
              {"number":-1,"episodes":[{"number":1}]},
              {"number":1,"episodes":[{"name":"no number"},{"number":0},{"number":3}]},
              {"number":2,"episodes":[]}
            ]}]""",
        ).single()

        assertThat(show.seasons.map { it.number }).containsExactly(1)
        assertThat(show.seasons.single().episodes.map { it.number }).containsExactly(3)
        assertThat(show.item.episodeCount).isEqualTo(1)
    }

    @Test
    fun `a show with no usable episodes is kept, as an unplayable title`() {
        val show = parse("""[{"type":"show","title":"Empty","seasons":[]}]""").single()
        assertThat(show.item.isShow).isTrue()
        assertThat(show.seasons).isEmpty()
        assertThat(show.item.seasonCount).isEqualTo(0)
    }

    @Test
    fun `the index resolves films and episodes, never a show's own id`() {
        val items = parse(twilightZone)
        val snapshot = snapshot(items)
        val show = items[1]
        val pilotId = show.seasons[0].episodes[0].id

        assertThat(snapshot.playable("catalog-the-kid-1921")).isInstanceOf(Playable.Film::class.java)
        val episode = snapshot.playable(pilotId) as Playable.EpisodeOf
        assertThat(episode.show.id).isEqualTo(show.item.id)
        assertThat(episode.item).isEqualTo(show.item)
        assertThat(episode.runtimeMs).isEqualTo(25 * 60_000L)
        assertThat(episode.sources).hasSize(1)
        assertThat(snapshot.playable(show.item.id)).isNull()
        assertThat(snapshot.playable("nope")).isNull()
        assertThat(snapshot.shows.map { it.id }).containsExactly(show.item.id)
    }

    @Test
    fun `shows are searched and their genres counted like films`() {
        val snapshot = snapshot(parse(twilightZone))
        assertThat(snapshot.search("twilight", 10).map { it.id }).containsExactly("show-the-twilight-zone-1959")
        assertThat(snapshot.genres).containsExactly("Drama", "Sci-Fi")
    }

    @Test
    fun `a season pack gives every episode of its season a source that names that episode's file`() {
        val show = parse(
            """[{"type":"show","title":"S","year":"1990","seasons":[
              {"number":1,
               "packs":[{"quality":"1080p","magnet":"magnet:?xt=urn:btih:$hashB"}],
               "episodes":[
                 {"number":2},
                 {"number":1,"magnets":[{"quality":"720p","magnet":"magnet:?xt=urn:btih:$hashA"}]}
               ]}
            ]}]""",
        ).single()
        val (e1, e2) = show.seasons.single().episodes

        val e1Sources = show.episodeSources.getValue(e1.id)
        assertThat(e1Sources.map { it.id }).containsExactly("torrent-$hashA", "pack-$hashB").inOrder()
        val pack = e1Sources.last()
        assertThat(pack.isSeasonPack).isTrue()
        assertThat(pack.label).isEqualTo("Torrent · 1080p (season)")
        assertThat(pack.fileSelection)
            .isEqualTo(FileSelection.Episode(EpisodeFileMatcher.Target(season = 1, episode = 1, ordinal = 0, episodesInSeason = 2)))

        // No torrent of its own, but the pack plays it.
        assertThat(e2.isPlayable).isTrue()
        assertThat(show.episodeSources.getValue(e2.id).single().fileSelection)
            .isEqualTo(FileSelection.Episode(EpisodeFileMatcher.Target(season = 1, episode = 2, ordinal = 1, episodesInSeason = 2)))
    }

    @Test
    fun `a malformed pack is dropped, and an episode left with nothing is unplayable`() {
        val show = parse(
            """[{"type":"show","title":"S","seasons":[
              {"number":1,"packs":[{"magnet":"magnet:?xt=urn:btih:broken"}],"episodes":[{"number":1}]}
            ]}]""",
        ).single()
        val episode = show.seasons.single().episodes.single()
        assertThat(episode.isPlayable).isFalse()
        assertThat(show.episodeSources.getValue(episode.id)).isEmpty()
    }

    @Test
    fun `packs from two entries for the same season both serve its episodes`() {
        val show = parse(
            """[{"type":"show","title":"S","seasons":[
              {"number":1,"packs":[{"magnet":"magnet:?xt=urn:btih:$hashA"}],"episodes":[{"number":1}]},
              {"number":1,"packs":[{"magnet":"magnet:?xt=urn:btih:$hashB"}],"episodes":[{"number":2}]}
            ]}]""",
        ).single()
        show.seasons.single().episodes.forEach { episode ->
            assertThat(show.episodeSources.getValue(episode.id).map { it.id }).containsExactly("pack-$hashA", "pack-$hashB")
        }
    }

    @Test
    fun `air dates parse as UTC midnight and nothing else`() {
        assertThat(parseAirDate("1970-01-02")).isEqualTo(86_400_000L)
        assertThat(parseAirDate(" 1970-01-01 ")).isEqualTo(0L)
        assertThat(parseAirDate("02/10/1959")).isNull()
        assertThat(parseAirDate("")).isNull()
        assertThat(parseAirDate(null)).isNull()
    }
}
