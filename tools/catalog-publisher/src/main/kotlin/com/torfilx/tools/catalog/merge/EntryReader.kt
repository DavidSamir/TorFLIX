package com.torfilx.tools.catalog.merge

import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogEpisodeDto
import com.torfilx.core.catalogue.format.CatalogIds
import com.torfilx.core.catalogue.format.CatalogMagnetDto
import com.torfilx.core.catalogue.format.CatalogRelease
import com.torfilx.core.catalogue.format.CatalogSeasonDto
import com.torfilx.core.model.MagnetLink
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Reads one title from an input file into the catalogue's own shape.
 *
 * The input folders are kept by hand and are messy, so this:
 * - repairs what can be repaired without guessing (a year written as a number, an id with a stray
 *   space, a magnet copied from a web page with `&amp;`, `imageUrl` for `image_url`) and says so;
 * - leaves out what the app would ignore anyway (a malformed magnet, a season with no number, an
 *   episode listed twice) and says so;
 * - refuses a title only when using it would mean guessing (an unknown type, a film with seasons) or
 *   would break the release (no title, an id the format cannot hold). An older copy of the same title
 *   is then used, when there is one.
 *
 * What comes out holds only the fields the format defines, so the merged file is exactly what the
 * publisher signs.
 */
internal class EntryReader(private val log: MergeLog) {

    class Read(
        /** The title in the catalogue's shape, or null when it cannot be published. */
        val entry: CatalogEntryDto?,
        /** Its identity across files: its explicit id, or the id derived from type, title and year. */
        val key: String?,
        /** Where it is, with its title, as every message about it names it. */
        val where: String,
    )

    fun read(element: JsonElement, at: String): Read {
        if (element !is JsonObject) {
            log.warning(at, "is ${kindOf(element)}, not a title; skipped")
            return Read(null, null, at)
        }
        val fields = Fields(element, ENTRY_FIELDS, ENTRY_ALIASES, at, "field")
        val title = text(fields["title"], "title", at)?.let { oneLine(it, "title", at) }.orEmpty()
        val where = if (title.isEmpty()) at else "$at \"${title.take(PREVIEW)}\""
        val id = id(fields["id"], where, derivedFrom = "its title and year")
        val type = text(fields["type"], "type", where)?.trim()?.lowercase().orEmpty()
        val year = text(fields["year"], "year", where, numbersAreText = true)?.let { oneLine(it, "year", where) }?.takeIf { it.isNotEmpty() }
        val isShow = type == CatalogEntryDto.TYPE_SHOW
        val knownType = type.isEmpty() || type == CatalogEntryDto.TYPE_MOVIE || isShow
        // A title of an unknown type is refused, but still identified by the kind it looks like, so an
        // older copy that stands in for it can say so.
        val looksLikeShow = isShow || (!knownType && !isEmptyList(fields["seasons"]))
        val key = when {
            id != null -> id
            title.isEmpty() -> null
            looksLikeShow -> CatalogIds.showBaseId(title, year)
            else -> CatalogIds.baseId(title, year)
        }

        fun refuse(reason: String): Read {
            log.warning(where, "$reason; this copy was not used")
            return Read(null, key, where)
        }

        if (title.isEmpty()) return refuse("has no title")
        if (breaksTitleCount(title)) return refuse(TITLE_COUNT_REASON)
        if (!knownType) {
            return refuse("has the type \"${type.take(PREVIEW)}\", which the app does not know: use \"show\" for a series, or leave \"type\" out for a film")
        }
        if (id == null && !CatalogIds.isValid(key!!)) {
            return refuse("would get the id \"${key.take(PREVIEW)}\", which the format cannot hold ($ID_RULE); give it an \"id\"")
        }

        val seasonsValue = fields["seasons"]
        val magnetsValue = fields["magnets"]
        if (!isShow && !isEmptyList(seasonsValue)) return refuse("is a film but has seasons: if it is a series, add \"type\": \"show\"")
        if (isShow && !isEmptyList(magnetsValue)) {
            log.warning(where, "is a show, so its own \"magnets\" were left out: a show's torrents belong to its episodes, or to a season's \"packs\"")
        }

        val seasons = if (isShow) seasons(seasonsValue, where) else emptyList()
        val magnets = if (isShow) emptyList() else magnets(magnetsValue, "magnet", where)
        if (isShow) {
            if (seasons.isEmpty()) return refuse("is a show with no usable season")
            val episodes = seasons.sumOf { it.episodes.size }
            if (episodes > CatalogRelease.MAX_EPISODES_PER_SHOW) {
                return refuse("has $episodes episodes, more than the ${CatalogRelease.MAX_EPISODES_PER_SHOW} a show may have")
            }
            reportUnplayableEpisodes(seasons, where)
        } else if (magnets.isEmpty()) {
            log.warning(where, "has no usable magnet, so it cannot be played")
        }

        val entry = CatalogEntryDto(
            id = id,
            type = if (isShow) CatalogEntryDto.TYPE_SHOW else null,
            title = title,
            year = year,
            imageUrl = url(fields["image_url"], "image_url", where),
            backdropUrl = url(fields["backdrop_url"], "backdrop_url", where),
            overview = prose(fields["overview"], "overview", where),
            genres = genres(fields["genres"], where),
            runtimeMinutes = positive(fields["runtimeMinutes"], "runtimeMinutes", where),
            magnets = magnets,
            seasons = seasons,
        )
        return Read(entry, key, where)
    }

    // --- shows -----------------------------------------------------------------------------------

    /** A season as it is being read; two seasons given the same number are put together here. */
    private class SeasonParts(val number: Int, val where: String, var name: String?, var imageUrl: String?) {
        val packs = ArrayList<CatalogMagnetDto>()
        val packHashes = HashSet<String>()
        val episodes = ArrayList<CatalogEpisodeDto>()
        val episodeNumbers = HashMap<Int, String>()

        fun build() = CatalogSeasonDto(number = number, name = name, imageUrl = imageUrl, packs = packs.toList(), episodes = episodes.toList())
    }

    private fun seasons(value: JsonElement?, where: String): List<CatalogSeasonDto> {
        val items = list(value, "seasons", where) ?: return emptyList()
        val byNumber = LinkedHashMap<Int, SeasonParts>()
        items.forEachIndexed { index, item ->
            val at = "$where, season ${index + 1} in the file"
            if (item !is JsonObject) {
                log.warning(at, "is ${kindOf(item)}, not a season; skipped")
                return@forEachIndexed
            }
            val fields = Fields(item, SEASON_FIELDS, SEASON_ALIASES, at, "season field")
            val number = wholeNumber(fields["number"], "number", at)
            if (number == null || !CatalogIds.isValidSeasonNumber(number)) {
                log.warning(at, if (number == null) "has no number; skipped" else "is numbered $number, but seasons are 0 (Specials) or more; skipped")
                return@forEachIndexed
            }
            val label = "$where S${twoDigits(number)}"
            val existing = byNumber[number]
            val parts = existing ?: SeasonParts(
                number = number,
                where = label,
                name = line(fields["name"], "name", label),
                imageUrl = url(fields["image_url"], "image_url", label),
            ).also { byNumber[number] = it }
            if (existing != null) {
                log.warning(label, "season $number is listed more than once; its episodes were put together, the first listing's name and image kept")
                existing.name = existing.name ?: line(fields["name"], "name", label)
                existing.imageUrl = existing.imageUrl ?: url(fields["image_url"], "image_url", label)
            }
            magnets(fields["packs"], "pack", label).forEach { pack ->
                if (parts.packHashes.add(canonicalInfoHash(pack.magnet))) {
                    parts.packs += pack
                } else {
                    log.warning(label, "a season pack is listed twice; the repeat was left out")
                }
            }
            episodes(fields["episodes"], label, parts)
        }
        return byNumber.values.mapNotNull { parts ->
            if (parts.episodes.isEmpty()) {
                log.warning(parts.where, "has no usable episode, so the season was left out")
                null
            } else {
                parts.build()
            }
        }
    }

    private fun episodes(value: JsonElement?, seasonWhere: String, season: SeasonParts) {
        val items = list(value, "episodes", seasonWhere) ?: return
        items.forEachIndexed { index, item ->
            val at = "$seasonWhere, episode ${index + 1} in the file"
            if (item !is JsonObject) {
                log.warning(at, "is ${kindOf(item)}, not an episode; skipped")
                return@forEachIndexed
            }
            val fields = Fields(item, EPISODE_FIELDS, EPISODE_ALIASES, at, "episode field")
            val number = wholeNumber(fields["number"], "number", at)
            if (number == null || !CatalogIds.isValidEpisodeNumber(number)) {
                log.warning(at, if (number == null) "has no number; skipped" else "is numbered $number, but episodes are 1 or more; skipped")
                return@forEachIndexed
            }
            val label = "${seasonWhere}E${twoDigits(number)}"
            season.episodeNumbers[number]?.let { first ->
                log.warning(label, "is listed again ($at); the first listing, $first, was kept")
                return@forEachIndexed
            }
            season.episodeNumbers[number] = at
            season.episodes += CatalogEpisodeDto(
                id = id(fields["id"], label, derivedFrom = "the show's id and the episode's number"),
                number = number,
                name = line(fields["name"], "name", label),
                overview = prose(fields["overview"], "overview", label),
                runtimeMinutes = positive(fields["runtimeMinutes"], "runtimeMinutes", label),
                airDate = airDate(fields["airDate"], label),
                imageUrl = url(fields["image_url"], "image_url", label),
                magnets = magnets(fields["magnets"], "magnet", label),
            )
        }
    }

    private fun reportUnplayableEpisodes(seasons: List<CatalogSeasonDto>, where: String) {
        val unplayable = seasons.flatMap { season ->
            if (season.packs.isNotEmpty()) emptyList() else season.episodes.filter { it.magnets.isEmpty() }.map { "S${twoDigits(season.number!!)}E${twoDigits(it.number!!)}" }
        }
        if (unplayable.isEmpty()) return
        val total = seasons.sumOf { it.episodes.size }
        if (unplayable.size == total) {
            log.warning(where, "has no usable magnet in any episode, so nothing of it can be played")
        } else {
            log.warning(where, "${unplayable.size} of $total episodes have no usable magnet and no season pack, so they cannot be played: ${preview(unplayable)}")
        }
    }

    // --- magnets ---------------------------------------------------------------------------------

    /**
     * The usable magnets of a `magnets` or `packs` list: well-formed, trimmed, each torrent once.
     *
     * @param noun `magnet` or `pack`, for messages.
     */
    private fun magnets(value: JsonElement?, noun: String, where: String): List<CatalogMagnetDto> {
        val field = "${noun}s"
        val items = when (value) {
            null, JsonNull -> return emptyList()
            is JsonArray -> value
            else -> {
                log.warning(where, "\"$field\" should be a list; read as a list of one")
                listOf(value)
            }
        }
        val hashes = HashSet<String>()
        val kept = ArrayList<CatalogMagnetDto>()
        items.forEachIndexed { index, item ->
            val at = "$where, $noun ${index + 1}"
            val (qualityValue, magnetValue) = when {
                item is JsonObject -> Fields(item, MAGNET_FIELDS, MAGNET_ALIASES, at, "magnet field").let { it["quality"] to it["magnet"] }
                item is JsonPrimitive && item.isString -> null to item
                else -> {
                    if (item !is JsonNull) log.warning(at, "is ${kindOf(item)}, not a magnet; left out")
                    return@forEachIndexed
                }
            }
            var magnet = text(magnetValue, "magnet", at)?.trim().orEmpty()
            if (magnet.isEmpty()) {
                log.warning(at, "has no magnet link; left out")
                return@forEachIndexed
            }
            if ("&amp;" in magnet) {
                magnet = magnet.replace("&amp;", "&")
                log.warning(at, "was copied with \"&amp;\" for \"&\"; repaired")
            }
            val hash = MagnetLink.infoHashOf(magnet)
            if (hash == null || breaksTitleCount(magnet)) {
                log.warning(at, "${magnetProblem(magnet)}; left out")
                return@forEachIndexed
            }
            if (!hashes.add(canonicalInfoHash(magnet))) {
                log.warning(at, "repeats a torrent already listed; left out")
                return@forEachIndexed
            }
            val quality = text(qualityValue, "quality", at, numbersAreText = true)?.let { oneLine(it, "quality", at) }?.takeIf { it.isNotEmpty() }
            kept += CatalogMagnetDto(quality = quality?.let { checked(it, "quality", at) }, magnet = magnet)
        }
        return kept
    }

    /**
     * Why a magnet cannot be used, showing the info hash itself: a hash one character too long looks
     * right at a glance, so its length is given too.
     */
    private fun magnetProblem(magnet: String): String {
        if (!magnet.startsWith("magnet:?", ignoreCase = true)) return "\"${magnet.take(PREVIEW)}\" is not a magnet link"
        val hash = magnet.substring("magnet:?".length).split('&')
            .firstOrNull { it.startsWith("xt=urn:btih:", ignoreCase = true) }
            ?.substring("xt=urn:btih:".length)
            ?: return "has no info hash (no xt=urn:btih: parameter)"
        return if (breaksTitleCount(magnet)) {
            TITLE_COUNT_REASON
        } else {
            "has the info hash \"${hash.take(PREVIEW)}\" (${hash.length} characters), which is not 40 hex or 32 base32 characters"
        }
    }

    // --- single fields ---------------------------------------------------------------------------

    /**
     * An explicit id, trimmed, or null when there is none or it cannot be used; the app then derives
     * one, exactly as it already does for this title, so leaving a bad id out changes nothing on a
     * television.
     */
    private fun id(value: JsonElement?, where: String, derivedFrom: String): String? {
        val raw = text(value, "id", where, numbersAreText = true) ?: return null
        val id = raw.trim()
        if (id.isEmpty()) {
            log.note(where, "has an empty \"id\"; one is derived from $derivedFrom")
            return null
        }
        if (!CatalogIds.isValid(id) || breaksTitleCount(id)) {
            log.warning(where, "the id \"${id.take(PREVIEW)}\" cannot be used ($ID_RULE); one is derived from $derivedFrom instead, as the app does")
            return null
        }
        if (id != raw) log.warning(where, "the id \"$id\" had spaces around it, which were removed")
        return id
    }

    /** A web address a television can load, or null. */
    private fun url(value: JsonElement?, field: String, where: String): String? {
        val url = text(value, field, where)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            log.warning(where, "\"$field\" \"${url.take(PREVIEW)}\" is not a web address (http:// or https://), so a television cannot load it; left out")
            return null
        }
        return checked(url, field, where)
    }

    private fun genres(value: JsonElement?, where: String): List<String> {
        val items = when (value) {
            null, JsonNull -> return emptyList()
            is JsonArray -> value
            is JsonPrimitive -> {
                log.warning(where, "\"genres\" should be a list; read as a list of one")
                listOf(value)
            }
            is JsonObject -> {
                log.warning(where, "\"genres\" is an object, not a list; left out")
                return emptyList()
            }
        }
        val seen = HashSet<String>()
        return items.mapNotNull { item ->
            val genre = text(item, "genres", where, numbersAreText = true)?.let { oneLine(it, "genre", where) }?.takeIf { it.isNotEmpty() }
                ?.let { checked(it, "genre", where) }
                ?: return@mapNotNull null
            if (seen.add(genre.lowercase())) {
                genre
            } else {
                log.tally(Severity.NOTE, "a genre repeated in one title was listed once", where)
                null
            }
        }
    }

    /** `1962-09-26`, or null. A date and time keeps its date. */
    private fun airDate(value: JsonElement?, where: String): String? {
        val raw = text(value, "airDate", where)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val date = if (DATE_TIME.matches(raw)) raw.take(ISO_DATE_LENGTH).also { log.note(where, "airDate \"$raw\" was read as $it") } else raw
        if (!isRealDate(date)) {
            log.warning(where, "airDate \"${raw.take(PREVIEW)}\" is not a date written like 1962-09-26; left out")
            return null
        }
        return date
    }

    /** Free text such as an overview: trimmed, line breaks kept. */
    private fun prose(value: JsonElement?, field: String, where: String): String? =
        text(value, field, where)?.trim()?.takeIf { it.isNotEmpty() }?.let { checked(it, field, where) }

    /** A one-line field such as a name. */
    private fun line(value: JsonElement?, field: String, where: String): String? =
        text(value, field, where)?.let { oneLine(it, field, where) }?.takeIf { it.isNotEmpty() }?.let { checked(it, field, where) }

    /** [text] on one line: line breaks, tabs and other control characters become spaces. Trimmed. */
    private fun oneLine(text: String, field: String, where: String): String {
        if (text.none { it.isISOControl() || it == LINE_SEPARATOR || it == PARAGRAPH_SEPARATOR }) return text.trim()
        log.warning(where, "\"$field\" had line breaks or control characters, which were replaced by spaces")
        return text.map { if (it.isISOControl() || it == LINE_SEPARATOR || it == PARAGRAPH_SEPARATOR) ' ' else it }
            .joinToString("")
            .replace(SPACES, " ")
            .trim()
    }

    /** [text] unless it would break the release's title count, in which case it is left out. */
    private fun checked(text: String, field: String, where: String): String? {
        if (!breaksTitleCount(text)) return text
        log.warning(where, "\"$field\" was left out: $TITLE_COUNT_REASON")
        return null
    }

    private fun positive(value: JsonElement?, field: String, where: String): Int? {
        val number = wholeNumber(value, field, where) ?: return null
        if (number < 1) {
            log.warning(where, "\"$field\" is $number; left out")
            return null
        }
        return number
    }

    /** A whole number. Quoted digits and `25.0` are read as numbers; anything else is left out. */
    private fun wholeNumber(value: JsonElement?, field: String, where: String): Int? {
        if (value == null || value is JsonNull) return null
        if (value !is JsonPrimitive || value.booleanOrNull != null) {
            log.warning(where, "\"$field\" is ${kindOf(value)}, not a number; left out")
            return null
        }
        val content = value.content.trim()
        val number = content.toIntOrNull()
            ?: content.toBigDecimalOrNull()
                ?.takeIf { it.stripTrailingZeros().scale() <= 0 }
                ?.let { runCatching { it.intValueExact() }.getOrNull() }
        if (number == null) {
            log.warning(where, "\"$field\" is \"${content.take(PREVIEW)}\", not a whole number; left out")
            return null
        }
        if (value.isString) log.tally(Severity.NOTE, "numbers written as text (\"25\") were read as numbers", where)
        return number
    }

    /**
     * A JSON string's content. A number is read as its text: said once in a tally where the format
     * often sees one (a year, a quality), and as a warning elsewhere. true/false and objects are refused.
     */
    private fun text(value: JsonElement?, field: String, where: String, numbersAreText: Boolean = false): String? = when (value) {
        null, JsonNull -> null
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> {
                log.warning(where, "\"$field\" is ${value.content}, not text; left out")
                null
            }
            else -> {
                val number = WHOLE_WITH_ZEROS.matchEntire(value.content)?.groupValues?.get(1) ?: value.content
                if (numbersAreText) {
                    log.tally(Severity.NOTE, "numbers given where the format has text (a year, an id, a quality) were read as text", where)
                } else {
                    log.warning(where, "\"$field\" is the number $number; read as the text \"$number\"")
                }
                number
            }
        }
        else -> {
            log.warning(where, "\"$field\" is ${kindOf(value)}, not text; left out")
            null
        }
    }

    /** The items of a list-valued field, or null (with a warning) when it is not a list. */
    private fun list(value: JsonElement?, field: String, where: String): List<JsonElement>? = when (value) {
        null, JsonNull -> null
        is JsonArray -> value
        else -> {
            log.warning(where, "\"$field\" is ${kindOf(value)}, not a list; left out")
            null
        }
    }

    private fun isEmptyList(value: JsonElement?): Boolean = value == null || value is JsonNull || (value is JsonArray && value.isEmpty())

    /**
     * The fields of one JSON object, read the way a person meant them.
     *
     * Exact names win. A name that differs only in case or punctuation (`imageUrl`, `Image URL`), or
     * is a known slip (`magnet` for `magnets`, an episode's `title` for its `name`), is read as the
     * real field with a warning, unless the real one is also there. Anything else is not part of the
     * format: the app ignores it, so it is left out, and tallied.
     */
    private inner class Fields(
        obj: JsonObject,
        known: Set<String>,
        aliases: Map<String, String>,
        where: String,
        what: String,
    ) {
        private val values = HashMap<String, JsonElement>()

        init {
            for ((name, value) in obj) if (name in known) values[name] = value
            for ((name, value) in obj) {
                if (name in known) continue
                val canonical = canonicalName(name)
                val target = aliases[canonical] ?: known.firstOrNull { canonicalName(it) == canonical }
                when {
                    target == null -> log.tally(Severity.NOTE, "$what \"${name.take(PREVIEW)}\" is not part of the catalogue format and was left out", where)
                    target in values -> log.warning(where, "\"$name\" was ignored because \"$target\" is also given")
                    else -> {
                        values[target] = value
                        log.warning(where, "\"$name\" was read as \"$target\"")
                    }
                }
            }
        }

        operator fun get(name: String): JsonElement? = values[name]?.takeUnless { it is JsonNull }
    }

    companion object {
        private const val PREVIEW = 60
        private const val ISO_DATE_LENGTH = 10
        private const val LINE_SEPARATOR = ' '
        private const val PARAGRAPH_SEPARATOR = ' '

        private const val ID_RULE = "no spaces, none of / ? # & % \\ \" ' < >, at most 200 characters"
        private const val TITLE_COUNT_REASON =
            "text that is exactly \"title\", or ends in a quote followed by title, breaks the release's count of titles"

        private val ENTRY_FIELDS = setOf(
            "id", "type", "title", "year", "image_url", "backdrop_url", "overview", "genres", "runtimeMinutes", "magnets", "seasons",
        )
        private val SEASON_FIELDS = setOf("number", "name", "image_url", "packs", "episodes")
        private val EPISODE_FIELDS = setOf("id", "number", "name", "overview", "runtimeMinutes", "airDate", "image_url", "magnets")
        private val MAGNET_FIELDS = setOf("quality", "magnet")

        /** Slips seen in hand-made files, by [canonicalName]. Each is read with a warning. */
        private val ENTRY_ALIASES = mapOf("name" to "title", "magnet" to "magnets", "genre" to "genres", "season" to "seasons")
        private val SEASON_ALIASES = mapOf("title" to "name", "episode" to "episodes", "pack" to "packs")
        private val EPISODE_ALIASES = mapOf("title" to "name", "magnet" to "magnets")
        private val MAGNET_ALIASES = mapOf("url" to "magnet", "uri" to "magnet", "link" to "magnet")

        private val SPACES = Regex(" {2,}")
        private val WHOLE_WITH_ZEROS = Regex("""(-?\d+)\.0+""")
        private val DATE_TIME = Regex("""\d{4}-\d{2}-\d{2}[T ].+""")
        private val DATE = Regex("""(\d{4})-(\d{2})-(\d{2})""")

        /** The same calendar check as the app's air-date parser: a real day, or nothing. */
        fun isRealDate(text: String): Boolean {
            val (y, m, d) = DATE.matchEntire(text)?.destructured ?: return false
            val year = y.toInt()
            val month = m.toInt()
            val day = d.toInt()
            if (month !in 1..12) return false
            val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
            val days = when (month) {
                2 -> if (leap) 29 else 28
                4, 6, 9, 11 -> 30
                else -> 31
            }
            return day in 1..days
        }
    }
}

/**
 * Whether [text], written as a JSON string, would contain the bytes `"title"`.
 *
 * A release requires as many of those bytes as it has titles (see `countDeclaredTitles`), and the
 * publisher refuses one where they disagree. Inside an encoded string the only unescaped quotes are the
 * two that delimit it, and a quote in the text is written `\"`, so the bytes appear exactly when the
 * text is `title` or ends in `"title`.
 */
internal fun breaksTitleCount(text: String): Boolean = text == "title" || text.endsWith("\"title")

/** A field name reduced to letters and digits, lower case: `image_url`, `imageUrl` and `Image URL` agree. */
internal fun canonicalName(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

/** The info hash of a well-formed magnet as 40 lower-case hex digits, whichever way it was written. */
internal fun canonicalInfoHash(magnet: String): String {
    val hash = MagnetLink.infoHashOf(magnet) ?: return ""
    if (hash.length == HEX_HASH_LENGTH) return hash.lowercase()
    var buffer = 0L
    var bits = 0
    val out = StringBuilder(HEX_HASH_LENGTH)
    for (char in hash.uppercase()) {
        buffer = (buffer shl BASE32_BITS) or BASE32_ALPHABET.indexOf(char).toLong()
        bits += BASE32_BITS
        while (bits >= HEX_BITS) {
            bits -= HEX_BITS
            out.append(HEX_DIGITS[((buffer shr bits) and HEX_MASK).toInt()])
        }
    }
    return out.toString()
}

private const val HEX_HASH_LENGTH = 40
private const val BASE32_BITS = 5
private const val HEX_BITS = 4
private const val HEX_MASK = 0xFL
private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
private const val HEX_DIGITS = "0123456789abcdef"

internal fun twoDigits(number: Int): String = number.toString().padStart(2, '0')

internal fun kindOf(element: JsonElement): String = when (element) {
    is JsonObject -> "an object"
    is JsonArray -> "a list"
    JsonNull -> "null"
    is JsonPrimitive -> when {
        element.isString -> "text"
        element.booleanOrNull != null -> element.content
        else -> "a number"
    }
}

/** The first few of [items], and how many more there are. */
internal fun preview(items: List<String>, limit: Int = 10): String =
    if (items.size <= limit) items.joinToString(", ") else items.take(limit).joinToString(", ") + " and ${items.size - limit} more"
