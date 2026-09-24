package com.torfilx.tools.catalog.merge

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.CatalogueJson
import com.torfilx.core.catalogue.format.CatalogContentRules
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.countDeclaredTitles
import com.torfilx.core.catalogue.release.CatalogueReleaseWriter
import com.torfilx.core.catalogue.testing.CatalogueTestKeys
import com.torfilx.core.catalogue.testing.TestCatalogues
import com.torfilx.tools.catalog.CatalogPinning
import com.torfilx.tools.catalog.CatalogPublisherCli
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.zip.GZIPOutputStream

/** `merge` end to end, through the same entry point the publishing scripts use. */
class MergeCommandTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val output = ByteArrayOutputStream()
    private val errors = ByteArrayOutputStream()
    private lateinit var work: File
    private lateinit var add: File
    private lateinit var remove: File
    private lateinit var releases: File
    private lateinit var out: File

    @Before
    fun setUp() {
        work = tmp.newFolder("work")
        add = File(work, "add").apply { mkdirs() }
        remove = File(work, "remove").apply { mkdirs() }
        releases = File(work, "releases").apply { mkdirs() }
        out = File(work, "output/catalog.json")
    }

    private fun merge(vararg extra: String, version: String? = "1"): Int {
        output.reset()
        errors.reset()
        val argv = listOf("merge", "--add", add.path, "--remove", remove.path, "--out", out.path) +
            (if (version != null) listOf("--version", version) else emptyList()) + extra
        return CatalogPublisherCli(PrintStream(output, true), PrintStream(errors, true), clock = { T0 }, workingDir = work).run(argv)
    }

    private fun write(dir: File, name: String, text: String, modified: Long = T0): File =
        File(dir, name).apply {
            parentFile.mkdirs()
            writeText(text)
            setLastModified(modified)
        }

    private fun merged(): List<CatalogEntryDto> =
        CatalogueJson.content.decodeFromString(ListSerializer(CatalogEntryDto.serializer()), out.readText())

    private val report: String get() = File(out.parentFile, "merge-report.txt").readText()

    private val summary: Map<String, String>
        get() = File(out.parentFile, "merge-summary.properties").readLines().filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    private val console: String get() = output.toString() + errors.toString()

    private fun film(title: String, year: Int, hash: Int, id: String? = null, poster: String? = null): JsonElement = buildJsonObject {
        id?.let { put("id", it) }
        put("title", title)
        put("year", year.toString())
        poster?.let { put("image_url", it) }
        putJsonArray("magnets") {
            add(
                buildJsonObject {
                    put("quality", "720p")
                    put("magnet", TestCatalogues.magnet(hash))
                },
            )
        }
    }

    /** A show; [episodes] maps each season number to its episode numbers. */
    private fun show(title: String, year: Int, episodes: Map<Int, List<Int>>, hashBase: Int, id: String? = null): JsonElement = buildJsonObject {
        id?.let { put("id", it) }
        put("type", "show")
        put("title", title)
        put("year", year.toString())
        putJsonArray("seasons") {
            episodes.forEach { (season, numbers) ->
                add(
                    buildJsonObject {
                        put("number", season)
                        putJsonArray("episodes") {
                            numbers.forEach { number ->
                                add(
                                    buildJsonObject {
                                        put("number", number)
                                        put("name", "Episode $number")
                                        putJsonArray("magnets") {
                                            add(buildJsonObject { put("magnet", TestCatalogues.magnet(hashBase + season * 100 + number)) })
                                        }
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    private fun list(vararg items: JsonElement): String = JsonArray(items.toList()).toString()

    private fun encode(entries: List<CatalogEntryDto>): String =
        JsonArray(entries.map { CatalogueJson.content.encodeToJsonElement(CatalogEntryDto.serializer(), it) }).toString()

    // --- newest wins ----------------------------------------------------------------------------

    @Test
    fun `the newest copy wins and every title keeps the place it was introduced at`() {
        write(add, "old.json", list(film("Alpha", 2001, 1, poster = "https://x.org/old.jpg"), film("Beta", 2002, 2)), T0)
        write(add, "new.json", list(film("Gamma", 2003, 3), film("Alpha", 2001, 1, poster = "https://x.org/new.jpg")), T0 + HOUR)

        assertThat(merge()).isEqualTo(0)

        val titles = merged()
        assertThat(titles.map { it.title }).containsExactly("Gamma", "Alpha", "Beta").inOrder()
        assertThat(titles.first { it.title == "Alpha" }.imageUrl).isEqualTo("https://x.org/new.jpg")
        assertThat(titles.map { it.id }).containsExactly("catalog-gamma-2003", "catalog-alpha-2001", "catalog-beta-2002").inOrder()
        assertThat(summary["status"]).isEqualTo("ok")
        assertThat(summary["titles"]).isEqualTo("3")
        assertThat(report).contains("older copy of catalog-alpha-2001")
    }

    @Test
    fun `a copy with an id and one without it are the same title`() {
        write(add, "old.json", list(film("The Kid", 1921, 1, id = "catalog-the-kid-1921", poster = "https://x.org/a.jpg")), T0)
        write(add, "new.json", list(film("The Kid", 1921, 1, poster = "https://x.org/b.jpg")), T0 + HOUR)

        assertThat(merge()).isEqualTo(0)

        assertThat(merged().single().imageUrl).isEqualTo("https://x.org/b.jpg")
        assertThat(merged().single().id).isEqualTo("catalog-the-kid-1921")
    }

    @Test
    fun `a film and a show with the same title and year are different titles`() {
        write(add, "a.json", list(film("Zorro", 1957, 1), show("Zorro", 1957, mapOf(1 to listOf(1)), 1000)))

        assertThat(merge()).isEqualTo(0)

        assertThat(merged().map { it.id }).containsExactly("catalog-zorro-1957", "show-zorro-1957")
    }

    @Test
    fun `order by name ranks numbers by value, whatever the modification times say`() {
        write(add, "batch-10.json", list(film("Alpha", 2001, 1, poster = "https://x.org/ten.jpg")), T0)
        write(add, "batch-9.json", list(film("Alpha", 2001, 1, poster = "https://x.org/nine.jpg")), T0 + HOUR)

        assertThat(merge("--order", "name")).isEqualTo(0)

        assertThat(merged().single().imageUrl).isEqualTo("https://x.org/ten.jpg")
    }

    @Test
    fun `files modified at the same moment are a warning, since their times cannot say which is newer`() {
        write(add, "a.json", list(film("Alpha", 2001, 1)), T0)
        write(add, "b.json", list(film("Beta", 2002, 2)), T0 + 500)

        assertThat(merge()).isEqualTo(0)
        assertThat(console).contains("modified within 500 ms of each other")
        assertThat(merge("--strict")).isEqualTo(1)
    }

    @Test
    fun `a refused newest copy gives way to the older one, and says so`() {
        write(add, "old.json", list(film("Alpha", 2001, 1)), T0)
        write(add, "new.json", """[{"title": "Alpha", "year": "2001", "type": "series"}]""", T0 + HOUR)

        assertThat(merge()).isEqualTo(0)

        assertThat(merged().single().title).isEqualTo("Alpha")
        assertThat(console).contains("has the type \"series\", which the app does not know")
        assertThat(console).contains("this older copy was used because the newer one")
    }

    @Test
    fun `a title listed twice in one file keeps its first listing`() {
        write(add, "a.json", list(film("Alpha", 2001, 1, poster = "https://x.org/1.jpg"), film("Alpha", 2001, 1, poster = "https://x.org/2.jpg")))

        assertThat(merge()).isEqualTo(0)

        assertThat(merged().single().imageUrl).isEqualTo("https://x.org/1.jpg")
        assertThat(console).contains("is listed again in the same file")
    }

    // --- shows -----------------------------------------------------------------------------------

    @Test
    fun `episodes only an older copy of a show has are reported, and kept with --merge-episodes`() {
        write(add, "old.json", list(show("Hillbillies", 1962, mapOf(1 to listOf(1, 2, 3)), 1000)), T0)
        write(add, "new.json", list(show("Hillbillies", 1962, mapOf(1 to listOf(1, 2), 2 to listOf(1)), 1000)), T0 + HOUR)

        assertThat(merge()).isEqualTo(0)
        assertThat(merged().single().seasons.map { season -> season.number to season.episodes.map { it.number } })
            .containsExactly(1 to listOf(1, 2), 2 to listOf(1)).inOrder()
        assertThat(console).contains("1 episodes are only in older copies of this show, so they are left out: S01E03 (add/old.json)")

        assertThat(merge("--merge-episodes")).isEqualTo(0)
        assertThat(merged().single().seasons.map { season -> season.number to season.episodes.map { it.number } })
            .containsExactly(1 to listOf(1, 2, 3), 2 to listOf(1)).inOrder()
        assertThat(merged().single().seasons.flatMap { s -> s.episodes.map { it.id } }).contains("show-hillbillies-1962-s01e03")
    }

    @Test
    fun `a show's slips are repaired and said, and what cannot be placed is left out`() {
        val text = """
            [{"type": "Show", "title": "Hillbillies", "year": 1962,
              "magnets": [{"magnet": "${TestCatalogues.magnet(9)}"}],
              "seasons": [
                {"number": "1", "episodes": [
                  {"number": 1, "title": "Pilot", "airDate": "1962-09-26T20:00:00Z", "magnets": ["${TestCatalogues.magnet(11)}"]},
                  {"number": 1, "name": "Pilot again", "magnets": [{"magnet": "${TestCatalogues.magnet(12)}"}]},
                  {"name": "No number"},
                  {"number": 2, "airDate": "26/09/1962", "magnets": [{"magnet": "${TestCatalogues.magnet(13)}"}]}
                ]},
                {"number": 1, "episodes": [{"number": 3, "magnets": [{"magnet": "${TestCatalogues.magnet(14)}"}]}]},
                {"number": -1, "episodes": [{"number": 1}]},
                {"number": 2, "episodes": []}
              ]}]
        """.trimIndent()
        write(add, "a.json", text)

        assertThat(merge()).isEqualTo(0)

        val show = merged().single()
        assertThat(show.type).isEqualTo("show")
        assertThat(show.year).isEqualTo("1962")
        assertThat(show.magnets).isEmpty()
        assertThat(show.seasons.map { season -> season.number to season.episodes.map { it.number } }).containsExactly(1 to listOf(1, 2, 3))
        val pilot = show.seasons.single().episodes.first()
        assertThat(pilot.name).isEqualTo("Pilot")
        assertThat(pilot.airDate).isEqualTo("1962-09-26")
        assertThat(show.seasons.single().episodes[1].airDate).isNull()
        assertThat(console).contains("its own \"magnets\" were left out")
        assertThat(console).contains("\"title\" was read as \"name\"")
        assertThat(console).contains("season 1 is listed more than once")
        assertThat(console).contains("the first listing")
        assertThat(console).contains("is numbered -1")
        assertThat(console).contains("has no usable episode, so the season was left out")
        assertThat(console).contains("is not a date written like 1962-09-26")
    }

    @Test
    fun `a film with seasons is refused rather than guessed to be a show`() {
        write(add, "a.json", """[{"title": "Alpha", "seasons": [{"number": 1, "episodes": [{"number": 1}]}]}, ${film("Beta", 2002, 2)}]""")

        assertThat(merge()).isEqualTo(0)

        assertThat(merged().map { it.title }).containsExactly("Beta")
        assertThat(console).contains("is a film but has seasons: if it is a series, add \"type\": \"show\"")
    }

    // --- field repairs ---------------------------------------------------------------------------

    @Test
    fun `slips in a film's fields are repaired and said`() {
        val hash = TestCatalogues.magnet(1).substringAfter("btih:").substringBefore('&')
        val text = """
            [{"id": " my-film ", "Title": "Alpha\nPart Two", "year": 2001, "imageUrl": "https://x.org/p.jpg",
              "backdrop_url": "file:///C:/b.jpg", "genres": "Drama", "runtimeMinutes": "120", "source_url": "https://x.org",
              "magnets": [
                {"quality": 720, "magnet": " magnet:?dn=A&amp;xt=urn:btih:$hash "},
                {"quality": "1080p", "magnet": "magnet:?xt=urn:btih:${hash.uppercase()}&dn=again"},
                {"quality": "480p", "magnet": "magnet:?xt=urn:btih:broken"}
              ]}]
        """.trimIndent()
        write(add, "a.json", text)

        assertThat(merge()).isEqualTo(0)

        val film = merged().single()
        assertThat(film.id).isEqualTo("my-film")
        assertThat(film.title).isEqualTo("Alpha Part Two")
        assertThat(film.year).isEqualTo("2001")
        assertThat(film.imageUrl).isEqualTo("https://x.org/p.jpg")
        assertThat(film.backdropUrl).isNull()
        assertThat(film.genres).containsExactly("Drama")
        assertThat(film.runtimeMinutes).isEqualTo(120)
        assertThat(film.magnets.map { it.quality to it.magnet }).containsExactly("720" to "magnet:?dn=A&xt=urn:btih:$hash")
        assertThat(console).contains("had spaces around it")
        assertThat(console).contains("\"Title\" was read as \"title\"")
        assertThat(console).contains("line breaks or control characters")
        assertThat(console).contains("\"imageUrl\" was read as \"image_url\"")
        assertThat(console).contains("is not a web address")
        assertThat(console).contains("\"genres\" should be a list")
        assertThat(console).contains("\"&amp;\" for \"&\"; repaired")
        assertThat(console).contains("repeats a torrent already listed")
        assertThat(console).contains("has the info hash \"broken\" (6 characters), which is not 40 hex or 32 base32 characters")
        assertThat(report).contains("field \"source_url\" is not part of the catalogue format")
    }

    @Test
    fun `text that would break the release's title count is left out, and the catalogue still builds`() {
        val text = """[{"title": "Alpha", "year": "2001", "overview": "He said \"title", "genres": ["title", "Drama"],
            "magnets": [{"magnet": "${TestCatalogues.magnet(1)}"}]}, {"title": "title", "magnets": []}]"""
        write(add, "a.json", text)

        assertThat(merge()).isEqualTo(0)

        val bytes = out.readBytes()
        assertThat(countDeclaredTitles(bytes)).isEqualTo(1)
        assertThat(merged().single().overview).isNull()
        assertThat(merged().single().genres).containsExactly("Drama")
    }

    @Test
    fun `an invalid id is dropped for the derived one, as the app does`() {
        write(add, "a.json", """[{"id": "my film/2001", "title": "Alpha", "year": "2001", "magnets": [{"magnet": "${TestCatalogues.magnet(1)}"}]}]""")

        assertThat(merge()).isEqualTo(0)

        assertThat(merged().single().id).isEqualTo("catalog-alpha-2001")
        assertThat(console).contains("cannot be used")
    }

    @Test
    fun `a title whose derived id could not be published needs an explicit one`() {
        write(add, "a.json", """[{"title": "Alpha", "year": "2001/02"}, ${film("Beta", 2002, 2)}]""")

        assertThat(merge()).isEqualTo(0)

        assertThat(merged().map { it.title }).containsExactly("Beta")
        assertThat(console).contains("would get the id \"catalog-alpha-2001/02\"")
    }

    // --- reading files ---------------------------------------------------------------------------

    @Test
    fun `files are read however they were saved`() {
        File(add, "utf16.json").writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + list(film("Utf Sixteen", 2001, 1)).toByteArray(Charsets.UTF_16LE))
        File(add, "bom.json").writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + list(film("Bom", 2002, 2)).toByteArray())
        File(add, "packed.json.gz").writeBytes(gzip(list(film("Packed", 2003, 3)).toByteArray()))
        write(add, "lines.jsonl", "${film("Line One", 2004, 4)}\n\n${film("Line Two", 2005, 5)}\n")
        write(add, "comments.json", "// hand-edited\n[\n  ${film("Commented", 2006, 6)}, /* trailing comma: */\n]")
        write(add, "single.json", film("Single", 2007, 7).toString())
        write(add, "wrapper.json", """{"movies": ${list(film("Wrapped", 2008, 8))}, "count": 1}""")
        write(add, "sub/folder/deep.json", list(film("Deep", 2009, 9)))
        add.walk().forEach { it.setLastModified(T0) }

        assertThat(merge("--order", "name")).isEqualTo(0)

        assertThat(merged().map { it.title }).containsExactly(
            "Utf Sixteen", "Bom", "Packed", "Line One", "Line Two", "Commented", "Single", "Wrapped", "Deep",
        )
        assertThat(report).contains("saved as UTF-16")
        assertThat(report).contains("comments or trailing commas")
        assertThat(report).contains("holds a single title")
        assertThat(report).contains("titles were read from its list \"movies\"")
    }

    @Test
    fun `a file that cannot be read is skipped with a warning, and fails the run under --strict`() {
        write(add, "good.json", list(film("Alpha", 2001, 1)))
        write(add, "cut.json", """[{"title": "Beta", "year": "2002", "magn""", T0 + HOUR)
        File(add, "latin1.json").writeBytes("""[{"title": "Am""".toByteArray() + byteArrayOf(0xE9.toByte()) + """lie"}]""".toByteArray())
        write(add, "blank.json", "   \n")

        assertThat(merge("--order", "name")).isEqualTo(0)
        assertThat(merged().map { it.title }).containsExactly("Alpha")
        assertThat(console).contains("add/cut.json: is not valid JSON")
        assertThat(console).contains("line 1, column")
        assertThat(console).contains("add/latin1.json: is not valid UTF-8 text")
        assertThat(console).contains("add/blank.json: is empty")

        assertThat(merge("--order", "name", "--strict")).isEqualTo(1)
        assertThat(out.exists()).isFalse()
    }

    @Test
    fun `other files are passed over, and a name that looks meant to be read is a warning`() {
        write(add, "films.json", list(film("Alpha", 2001, 1)))
        write(add, "films2.json.txt", list(film("Beta", 2002, 2)))
        write(add, "notes.md", "# notes")
        write(add, "._films.json", "\u0000\u0005binary")
        write(add, ".git/objects.json", list(film("Gamma", 2003, 3)))

        assertThat(merge()).isEqualTo(0)

        assertThat(merged().map { it.title }).containsExactly("Alpha")
        assertThat(console).contains("add/films2.json.txt: not read: only names ending in")
        assertThat(report).contains("hidden file, not read")
        assertThat(report).contains("hidden folder, not read")
    }

    @Test
    fun `values that are not titles are skipped`() {
        write(add, "a.json", """[null, 42, "Alpha", ${film("Beta", 2002, 2)}, {"year": "2001"}]""")

        assertThat(merge()).isEqualTo(0)

        assertThat(merged().map { it.title }).containsExactly("Beta")
        assertThat(console).contains("#1: is null, not a title")
        assertThat(console).contains("#5: has no title")
    }

    // --- folders ---------------------------------------------------------------------------------

    @Test
    fun `an empty or missing add folder is an error and writes nothing`() {
        assertThat(merge()).isEqualTo(1)
        assertThat(console).contains("holds no catalogue files")

        add.deleteRecursively()
        assertThat(merge()).isEqualTo(1)
        assertThat(console).contains("does not exist")
        assertThat(out.exists()).isFalse()
        assertThat(summary["status"]).isEqualTo("failed")
    }

    @Test
    fun `a failed run deletes the catalogue an earlier run wrote`() {
        write(add, "a.json", list(film("Alpha", 2001, 1)))
        assertThat(merge()).isEqualTo(0)
        assertThat(out.exists()).isTrue()

        write(remove, "bad.json", "[{")
        assertThat(merge()).isEqualTo(1)

        assertThat(out.exists()).isFalse()
    }

    @Test
    fun `the output may not sit inside a folder that is read`() {
        write(add, "a.json", list(film("Alpha", 2001, 1)))
        out = File(add, "merged/catalog.json")

        assertThat(merge()).isEqualTo(1)

        assertThat(console).contains("would read its own output back as input")
    }

    // --- remove ----------------------------------------------------------------------------------

    @Test
    fun `the remove folder takes titles out by id, by title and year, and by title alone`() {
        write(
            add,
            "a.json",
            list(
                film("Alpha", 2001, 1),
                film("Beta", 2002, 2),
                film("Gamma", 2003, 3),
                film("Delta", 2004, 4),
                show("Epsilon", 1962, mapOf(1 to listOf(1, 2)), 1000),
            ),
        )
        write(remove, "ids.json", """["catalog-alpha-2001"]""")
        write(remove, "titles.json", """[{"title": "BETA", "year": 2002}, {"title": "gamma"}]""")
        write(remove, "copied.json", list(show("Epsilon", 1962, mapOf(1 to listOf(1)), 1000)))

        assertThat(merge()).isEqualTo(0)

        assertThat(merged().map { it.title }).containsExactly("Delta")
        assertThat(console).contains("removed 4 titles")
        assertThat(report).contains("removes the whole show")
    }

    @Test
    fun `an episode's id removes that episode, and a show left with none goes too`() {
        write(add, "a.json", list(show("Alpha", 1962, mapOf(1 to listOf(1, 2), 2 to listOf(1)), 1000), show("Beta", 1963, mapOf(1 to listOf(1)), 2000)))
        write(remove, "episodes.json", """["show-alpha-1962-s01e02", "show-alpha-1962-s02e01", {"id": "show-beta-1963-s01e01"}]""")

        assertThat(merge()).isEqualTo(0)

        val alpha = merged().single()
        assertThat(alpha.seasons.map { season -> season.number to season.episodes.map { it.number } }).containsExactly(1 to listOf(1))
        assertThat(report).contains("season 2 of \"Alpha\" (1962), id show-alpha-1962 has no episodes left")
        assertThat(report).contains("\"Beta\" (1963), id show-beta-1963 has no episodes left, so the show was removed too")
    }

    @Test
    fun `an item that matches nothing is a warning, with the nearest names`() {
        write(add, "a.json", list(film("Inception", 2010, 1)))
        write(remove, "r.json", """["Catalog-Inception-2010", {"title": "Inception", "year": "2011"}]""")

        assertThat(merge()).isEqualTo(0)

        assertThat(merged().map { it.title }).containsExactly("Inception")
        assertThat(console).contains(
            "no title or episode has the id \"Catalog-Inception-2010\", so nothing was removed; did you mean the id \"catalog-inception-2010\"",
        )
        assertThat(console).contains("no title is called \"Inception\" from 2011, so nothing was removed; did you mean \"Inception\" (2010)")
        assertThat(merge("--strict")).isEqualTo(1)
    }

    @Test
    fun `an item naming two titles, or disagreeing with its id, is an error and nothing is published`() {
        write(add, "a.json", list(film("Crash", 1996, 1), film("Crash", 2004, 2), film("Alpha", 2001, 3)))
        write(remove, "r.json", """[{"title": "Crash"}, {"id": "catalog-alpha-2001", "title": "Beta"}]""")

        assertThat(merge()).isEqualTo(1)

        assertThat(console).contains("\"Crash\" names 2 titles")
        assertThat(console).contains("the id \"catalog-alpha-2001\" belongs to a different title than this item describes")
        assertThat(out.exists()).isFalse()
    }

    @Test
    fun `a remove file that cannot be read stops the run`() {
        write(add, "a.json", list(film("Alpha", 2001, 1)))
        write(remove, "r.json", """["catalog-alpha-2001",""")
        write(remove, "n.json", """[7]""")

        assertThat(merge()).isEqualTo(1)

        assertThat(console).contains("remove/r.json: is not valid JSON")
        assertThat(console).contains("remove/n.json #1: is a number")
    }

    // --- release ---------------------------------------------------------------------------------

    @Test
    fun `the merged file is exactly the file build signs`() {
        write(add, "a.json", list(film("Alpha", 2001, 1), show("Beta", 1962, mapOf(1 to listOf(1, 2)), 1000)))

        assertThat(merge(version = "3")).isEqualTo(0)

        val bytes = out.readBytes()
        assertThat(CatalogPinning.pin(bytes).json).isEqualTo(bytes)
        val entries = merged()
        assertThat(CatalogContentRules.problems(entries, countDeclaredTitles(bytes), requireExplicitIds = true)).isEmpty()
        val written = CatalogueReleaseWriter.write(bytes, 3, T0, CatalogueTestKeys.SEED, File(work, "dist"), minVersionCode = 17)
        assertThat(written.manifest.titleCount).isEqualTo(2)
        assertThat(summary["sha256"]).isNotEmpty()
        assertThat(summary["catalog"]).isEqualTo(out.path.replace('\\', '/'))
    }

    @Test
    fun `the version is one higher than the newest release built, and never lower`() {
        TestCatalogues.writeRelease(releases, 2, TestCatalogues.entries(3))
        TestCatalogues.writeRelease(releases, 1, TestCatalogues.entries(3))
        write(add, "a.json", encode(TestCatalogues.entries(4)))

        assertThat(merge("--releases", releases.path, version = null)).isEqualTo(0)
        assertThat(summary["version"]).isEqualTo("3")
        assertThat(summary["previous_version"]).isEqualTo("2")

        assertThat(merge("--releases", releases.path, version = "2")).isEqualTo(1)
        assertThat(console).contains("release 2 is not newer than release 2")
    }

    @Test
    fun `a number recorded as used is never used again, even when its release folder is gone`() {
        write(add, "a.json", list(film("Alpha", 2001, 1)))
        assertThat(merge()).isEqualTo(0)
        CatalogueReleaseWriter.write(out.readBytes(), 2, T0, CatalogueTestKeys.SEED, releases)
        write(releases, Releases.USED_VERSIONS_FILE, "# release numbers already used\n3  2026-09-24 published; folder deleted\n")

        assertThat(merge("--releases", releases.path, version = null)).isEqualTo(0)
        assertThat(summary["version"]).isEqualTo("4")
        assertThat(summary["republish"]).isNull()

        assertThat(merge("--releases", releases.path, version = "3")).isEqualTo(1)
        assertThat(console).contains("release 3 is not newer than release 3 (recorded as used in")
    }

    @Test
    fun `with no release to follow, the version must be given`() {
        write(add, "a.json", list(film("Alpha", 2001, 1)))

        assertThat(merge("--releases", releases.path, version = null)).isEqualTo(1)

        assertThat(console).contains("pass --version")
    }

    @Test
    fun `shows need build 17, and the last release's minimum carries on`() {
        write(add, "a.json", list(show("Alpha", 1962, mapOf(1 to listOf(1)), 1000)))
        assertThat(merge()).isEqualTo(0)
        assertThat(summary["min_version_code"]).isEqualTo("17")

        assertThat(merge("--min-version-code", "16")).isEqualTo(1)
        assertThat(console).contains("16 is too low")

        assertThat(merge("--app-version-code", "15")).isEqualTo(1)
        assertThat(console).contains("above the app's current build (15)")

        write(add, "a.json", list(film("Alpha", 2001, 1)))
        TestCatalogues.writeRelease(releases, 4, TestCatalogues.entries(1), minVersionCode = 19)
        assertThat(merge("--releases", releases.path, "--allow-vanished", version = null)).isEqualTo(0)
        assertThat(summary["min_version_code"]).isEqualTo("19")
    }

    @Test
    fun `titles of the last release that vanish without being removed stop the run`() {
        val previous = TestCatalogues.entries(4)
        TestCatalogues.writeRelease(releases, 2, previous)
        write(add, "a.json", encode(previous.take(2)))

        assertThat(merge("--releases", releases.path, version = null)).isEqualTo(1)
        assertThat(console).contains("2 of its 4 titles (50.0%) would disappear")
        assertThat(console).contains("is in release 2 but in neither the add nor the remove folder")

        assertThat(merge("--releases", releases.path, "--allow-vanished", version = null)).isEqualTo(0)

        write(remove, "r.json", buildJsonArray { previous.drop(2).forEach { add(JsonPrimitive(it.id)) } }.toString())
        write(add, "a.json", encode(previous))
        assertThat(merge("--releases", releases.path, version = null)).isEqualTo(0)
        assertThat(console).contains("Compared with release 2: 0 added, 2 removed, 0 vanished")
    }

    @Test
    fun `a title whose id would change is reported with the id to put back`() {
        TestCatalogues.writeRelease(releases, 2, listOf(CatalogEntryDto(id = "kid-1921", title = "The Kid", year = "1921")))
        write(add, "a.json", list(film("The Kid", 1921, 1)))

        assertThat(merge("--releases", releases.path, "--allow-vanished", version = null)).isEqualTo(0)

        assertThat(console).contains("now has the id catalog-the-kid-1921, so every viewer's progress and My List entry for it is lost")
        assertThat(console).contains("give its entry \"id\": \"kid-1921\"")
    }

    @Test
    fun `an unchanged catalogue is published again rather than as a new release`() {
        write(add, "a.json", list(film("Alpha", 2001, 1)))
        assertThat(merge()).isEqualTo(0)
        CatalogueReleaseWriter.write(out.readBytes(), 5, T0, CatalogueTestKeys.SEED, releases)

        assertThat(merge("--releases", releases.path, version = null)).isEqualTo(0)

        assertThat(summary["version"]).isEqualTo("5")
        assertThat(summary["republish"]).endsWith("torfilx-catalogue-5")
        assertThat(console).contains("Nothing changed: release 5 is published again")
    }

    // --- scale -----------------------------------------------------------------------------------

    @Test
    fun `thousands of overlapping files merge to one copy per title`() {
        repeat(FILES) { index ->
            val titles = (0 until TITLES_PER_FILE).map { offset ->
                val film = (index + offset) % DISTINCT
                film("Film $film", 2000, film, poster = "https://x.org/$index.jpg")
            }
            write(add, "batch-$index.json", JsonArray(titles).toString(), T0 + index * 1000L)
        }

        assertThat(merge()).isEqualTo(0)

        val titles = merged()
        assertThat(titles).hasSize(DISTINCT)
        assertThat(titles.map { it.id }.toSet()).hasSize(DISTINCT)
        // The newest file is the last one written; the films it holds carry its poster.
        assertThat(titles.first { it.title == "Film ${(FILES - 1) % DISTINCT}" }.imageUrl).isEqualTo("https://x.org/${FILES - 1}.jpg")
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { buffer -> GZIPOutputStream(buffer).use { it.write(bytes) } }.toByteArray()

    private companion object {
        const val T0 = 1_767_225_600_000L
        const val HOUR = 3_600_000L
        const val FILES = 2_000
        const val TITLES_PER_FILE = 3
        const val DISTINCT = 500
    }
}

/** How input files are ranked by name. */
class NaturalOrderTest {
    @Test
    fun `digits compare by value and letters without case`() {
        val names = listOf("batch-10.json", "Batch-9.json", "batch-9.json", "2026-09-24.json", "2026-09-01.json", "batch-010.json")
        assertThat(names.sortedWith(NaturalOrder))
            .containsExactly("2026-09-01.json", "2026-09-24.json", "Batch-9.json", "batch-9.json", "batch-010.json", "batch-10.json")
            .inOrder()
    }
}
