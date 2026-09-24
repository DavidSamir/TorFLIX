package com.torfilx.tools.catalog.merge

import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogIds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the remove folder asks to take out of the catalogue, and taking it out.
 *
 * Each item names a title, or one episode, in one of these ways:
 * - an id, as text: `"catalog-inception-2010"`, or an episode's `"show-x-1962-s01e03"`;
 * - an object with an `"id"`, such as a title copied from a catalogue file. Its `"title"`, `"year"`
 *   and `"type"`, when given, must agree with the title that has that id, which catches a copy of the
 *   wrong title;
 * - an object with a `"title"` and no id, optionally with a `"year"` and a `"type"`. Titles are
 *   compared as their ids compare them, ignoring case and punctuation. It must name exactly one title.
 *
 * A title is removed whole, a show with all its episodes, even when the item lists some of them: only
 * an episode's own id removes a single episode.
 *
 * Removing is where a mistake does the most harm, so nothing here is guessed: an item that cannot be
 * read, names two titles, or disagrees with the title its id belongs to is an error, and the run stops
 * without publishing. An item that matches nothing is a warning, with the nearest names when there are.
 */
internal class RemoveList(private val log: MergeLog) {

    private sealed class Selector(val where: String, val listsSeasons: Boolean) {
        class ById(where: String, listsSeasons: Boolean, val id: String, val title: String?, val year: String?, val type: String?) :
            Selector(where, listsSeasons)

        class ByTitle(where: String, listsSeasons: Boolean, val title: String, val year: String?, val type: String?) :
            Selector(where, listsSeasons)
    }

    private val selectors = ArrayList<Selector>()

    val size: Int get() = selectors.size

    fun add(file: InputFile, values: List<JsonElement>) {
        values.forEachIndexed { position, value -> parse(value, "${file.display} #${position + 1}")?.let { selectors += it } }
    }

    private fun parse(value: JsonElement, where: String): Selector? {
        when (value) {
            is JsonPrimitive -> {
                if (value is JsonNull || !value.isString) {
                    log.error(where, "is ${kindOf(value)}; an item to remove is an id (text) or an object with an \"id\" or a \"title\"")
                    return null
                }
                val id = value.content.trim()
                if (id.isEmpty()) {
                    log.error(where, "is empty text; an item to remove is an id or an object with an \"id\" or a \"title\"")
                    return null
                }
                return Selector.ById(where, false, id, null, null, null)
            }
            is JsonArray -> {
                log.error(where, "is a list inside a list; list the titles to remove directly")
                return null
            }
            is JsonObject -> {
                val id = text(value, "id", where)
                val title = text(value, "title", where) ?: text(value, "name", where).takeIf { id == null }
                val year = text(value, "year", where)
                val type = text(value, "type", where)?.lowercase()
                if (type != null && type != CatalogEntryDto.TYPE_MOVIE && type != CatalogEntryDto.TYPE_SHOW) {
                    log.error(where, "has the type \"$type\"; it must be \"movie\" or \"show\", or be left out")
                    return null
                }
                val listsSeasons = field(value, "seasons").let { it is JsonArray && it.isNotEmpty() }
                return when {
                    id != null -> Selector.ById(where, listsSeasons, id, title, year, type)
                    title != null -> Selector.ByTitle(where, listsSeasons, title, year, type)
                    else -> {
                        log.error(where, "has neither an \"id\" nor a \"title\", so it names nothing to remove")
                        null
                    }
                }
            }
        }
    }

    /** A field's text, trimmed; a number counts as text. Null when absent or empty; an error when it is neither. */
    private fun text(obj: JsonObject, name: String, where: String): String? {
        val value = field(obj, name) ?: return null
        if (value is JsonNull) return null
        if (value !is JsonPrimitive) {
            log.error(where, "\"$name\" is ${kindOf(value)}, not text")
            return null
        }
        return value.content.trim().takeIf { it.isNotEmpty() }
    }

    private fun field(obj: JsonObject, name: String): JsonElement? =
        obj[name] ?: obj.entries.firstOrNull { canonicalName(it.key) == canonicalName(name) }?.value

    /** What [apply] took out. */
    class Applied(
        val entries: List<CatalogEntryDto>,
        val keys: List<String>,
        /** Ids of the titles removed, shows emptied by episode removals included. */
        val removedTitles: Set<String>,
        val removedEpisodes: Set<String>,
    )

    /**
     * Removes what the list names from [entries], which carry their pinned ids.
     *
     * @param keys each entry's merge key, parallel to [entries]; an id in the list may name either.
     */
    fun apply(entries: List<CatalogEntryDto>, keys: List<String>): Applied {
        val byId = HashMap<String, Int>()
        entries.forEachIndexed { index, entry -> byId[entry.id!!] = index }
        keys.forEachIndexed { index, key -> byId.putIfAbsent(key, index) }
        val byLowerId = byId.keys.groupBy { it.lowercase() }
        val episodes = HashMap<String, Pair<Int, String>>()
        entries.forEachIndexed { index, entry ->
            entry.seasons.forEach { season ->
                season.episodes.forEach { episode ->
                    episode.id?.let { episodes[it] = index to "S${twoDigits(season.number!!)}E${twoDigits(episode.number!!)}" }
                }
            }
        }
        val bySlug = HashMap<String, MutableList<Int>>()
        entries.forEachIndexed { index, entry -> bySlug.getOrPut(CatalogIds.slug(entry.title.trim())) { ArrayList() } += index }

        val removeTitle = BooleanArray(entries.size)
        val removeEpisodes = HashMap<Int, MutableSet<String>>()
        fun describe(index: Int): String = entries[index].let { "\"${it.title}\"${it.year?.let { year -> " ($year)" }.orEmpty()}, id ${it.id}" }

        fun removeTitleAt(index: Int, selector: Selector) {
            if (removeTitle[index]) {
                log.note(selector.where, "${describe(index)} is already removed by an earlier item")
                return
            }
            removeTitle[index] = true
            val entry = entries[index]
            if (entry.isShow && selector.listsSeasons) {
                log.note(
                    selector.where,
                    "removes the whole show ${describe(index)}, all ${entry.seasons.sumOf { it.episodes.size }} episodes; " +
                        "to remove single episodes, list their ids instead",
                )
            } else {
                log.note(selector.where, "removes ${describe(index)}")
            }
        }

        selectors.forEach { selector ->
            when (selector) {
                is Selector.ById -> {
                    val index = byId[selector.id]
                    val episode = episodes[selector.id]
                    when {
                        index != null -> if (agrees(selector, entries[index])) removeTitleAt(index, selector)
                        episode != null -> {
                            removeEpisodes.getOrPut(episode.first) { HashSet() } += selector.id
                            log.note(selector.where, "removes episode ${episode.second} of ${describe(episode.first)}")
                        }
                        else -> {
                            val hints = byLowerId[selector.id.lowercase()].orEmpty().map { "the id \"$it\"" } +
                                bySlug[CatalogIds.slug(selector.id)].orEmpty().map { describe(it) }
                            unmatched(selector, "no title or episode has the id \"${selector.id}\"", hints)
                        }
                    }
                }
                is Selector.ByTitle -> {
                    val sameName = bySlug[CatalogIds.slug(selector.title)].orEmpty()
                    val matches = sameName.filter { index ->
                        val entry = entries[index]
                        (selector.year == null || selector.year == entry.year?.trim()) &&
                            (selector.type == null || (selector.type == CatalogEntryDto.TYPE_SHOW) == entry.isShow)
                    }
                    when (matches.size) {
                        0 -> unmatched(
                            selector,
                            "no title is called \"${selector.title}\"${selector.year?.let { " from $it" }.orEmpty()}" +
                                selector.type?.let { " of type $it" }.orEmpty(),
                            sameName.map { describe(it) },
                        )
                        1 -> removeTitleAt(matches.single(), selector)
                        else -> log.error(
                            selector.where,
                            "\"${selector.title}\" names ${matches.size} titles: ${matches.joinToString("; ") { describe(it) }}. " +
                                "Say which with a \"year\", or use the \"id\"; nothing was removed for this item",
                        )
                    }
                }
            }
        }

        val kept = ArrayList<CatalogEntryDto>(entries.size)
        val keptKeys = ArrayList<String>(entries.size)
        val removedTitles = LinkedHashSet<String>()
        val removedEpisodes = LinkedHashSet<String>()
        entries.forEachIndexed { index, entry ->
            if (removeTitle[index]) {
                removedTitles += entry.id!!
                return@forEachIndexed
            }
            val dropped = removeEpisodes[index]
            if (dropped == null) {
                kept += entry
                keptKeys += keys[index]
                return@forEachIndexed
            }
            removedEpisodes += dropped
            val seasons = entry.seasons.mapNotNull { season ->
                val left = season.episodes.filter { it.id !in dropped }
                when {
                    left.size == season.episodes.size -> season
                    left.isEmpty() -> {
                        log.note("", "season ${season.number} of ${describe(index)} has no episodes left, so it was removed too")
                        null
                    }
                    else -> season.copy(episodes = left)
                }
            }
            if (seasons.isEmpty()) {
                log.note("", "${describe(index)} has no episodes left, so the show was removed too")
                removedTitles += entry.id!!
            } else {
                kept += entry.copy(seasons = seasons)
                keptKeys += keys[index]
            }
        }
        return Applied(kept, keptKeys, removedTitles, removedEpisodes)
    }

    /** Whether the title an id names is the title the item describes. An error when it is not. */
    private fun agrees(selector: Selector.ById, entry: CatalogEntryDto): Boolean {
        val differences = buildList {
            if (selector.title != null && CatalogIds.slug(selector.title) != CatalogIds.slug(entry.title.trim())) {
                add("its title is \"${entry.title}\", not \"${selector.title}\"")
            }
            if (selector.year != null && selector.year != entry.year?.trim()) add("its year is ${entry.year ?: "not given"}, not ${selector.year}")
            if (selector.type != null && (selector.type == CatalogEntryDto.TYPE_SHOW) != entry.isShow) {
                add("it is a ${if (entry.isShow) "show" else "film"}, not a ${if (selector.type == CatalogEntryDto.TYPE_SHOW) "show" else "film"}")
            }
        }
        if (differences.isEmpty()) return true
        log.error(
            selector.where,
            "the id \"${selector.id}\" belongs to a different title than this item describes: ${differences.joinToString("; ")}. " +
                "Nothing was removed for this item; to remove that title anyway, give only its id",
        )
        return false
    }

    private fun unmatched(selector: Selector, message: String, hints: List<String>) {
        val suffix = if (hints.isEmpty()) "" else "; did you mean ${preview(hints.distinct(), limit = 5)}?"
        log.warning(selector.where, "$message, so nothing was removed$suffix")
    }
}
