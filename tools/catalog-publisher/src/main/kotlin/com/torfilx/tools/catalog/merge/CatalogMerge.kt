package com.torfilx.tools.catalog.merge

import com.torfilx.core.catalogue.CatalogueJson
import com.torfilx.core.catalogue.format.CatalogContentRules
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogIds
import com.torfilx.core.catalogue.format.CatalogRelease
import com.torfilx.core.catalogue.format.countDeclaredTitles
import com.torfilx.core.catalogue.release.CatalogueReleaseWriter
import com.torfilx.tools.catalog.CatalogPinning
import com.torfilx.tools.catalog.MagnetTrackers
import com.torfilx.tools.catalog.OfflineCommands
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.Instant
import java.util.Locale

/** What a merge was asked to do. */
internal class MergeOptions(
    val addDir: File,
    /** Null when there is nothing to remove. */
    val removeDir: File?,
    val order: FileOrder = FileOrder.MODIFIED,
    val mergeEpisodes: Boolean = false,
    val stripTrackers: Boolean = false,
    /** Folders holding releases built earlier: the version and min version code carry on from them. */
    val releaseDirs: List<File> = emptyList(),
    val version: Long? = null,
    val minVersionCode: Int? = null,
    /** The app's current versionCode; a release no build up to it can install is refused. */
    val appVersionCode: Int? = null,
    val allowVanished: Boolean = false,
    val maxVanishedPercent: Double = CatalogMerge.DEFAULT_MAX_VANISHED_PERCENT,
    /** Files this run writes, which must never sit inside a folder it reads. */
    val outputs: List<File> = emptyList(),
)

/** What a merge produced. [json] is null whenever the merge failed. */
internal class MergeResult(
    val json: ByteArray?,
    val entries: List<CatalogEntryDto>,
    val filesFound: Int,
    val filesUnreadable: Int,
    val copies: Int,
    val superseded: Int,
    val refused: Int,
    val removeItems: Int,
    val removedTitles: Int,
    val removedEpisodes: Int,
    val gzBytes: Int,
    val version: Long?,
    val minVersionCode: Int?,
    val comparison: ReleaseComparison?,
    /** The release to publish again as it is, when nothing changed and no version was asked for. */
    val republish: BuiltRelease?,
) {
    val shows: Int get() = entries.count { it.isShow }
    val films: Int get() = entries.size - shows
    val episodes: Int get() = CatalogContentRules.episodeCount(entries)
}

/**
 * Merges the add and remove folders into one catalogue ready to be built and signed.
 *
 * 1. Every catalogue file in the add folder is ranked newest first ([FileOrder]) and read in that
 *    order; the newest usable copy of each title wins ([TitleMerger]).
 * 2. Ids are pinned exactly as `build` pins them, so the file written is the file signed.
 * 3. The remove folder is applied ([RemoveList]).
 * 4. The result is checked with the publisher's own rules and through the publisher's own `build`
 *    path, byte for byte, then against the last release built here ([ReleaseComparison]), and the
 *    release number and oldest app build are settled.
 *
 * It goes as far as it can even after an error, so one run reports every problem at once; [log]
 * decides whether the run failed.
 */
internal class CatalogMerge(
    private val options: MergeOptions,
    private val log: MergeLog,
    private val progress: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var filesFound = 0
    private var filesUnreadable = 0

    fun run(): MergeResult {
        val merger = TitleMerger(log, options.mergeEpisodes)
        val removals = RemoveList(log)
        if (!checkFolders()) return failed(merger, removals)
        readAddFolder(merger)
        if (filesFound == 0) return failed(merger, removals)
        val merged = merger.result()
        if (merged.isEmpty()) log.error("add folder", "holds no usable title")

        val pinned = CatalogIds.pin(merged.map { it.entry })
        readRemoveFolder(removals)
        val applied = removals.apply(pinned, merged.map { it.key })
        val entries = if (options.stripTrackers) {
            MagnetTrackers.stripAll(applied.entries, MagnetTrackers.DEFAULT_KEEP, OfflineCommands.DEFAULT_TRACKERS)
        } else {
            applied.entries
        }

        val json = encode(entries)
        val gzBytes = CatalogueReleaseWriter.gzip(json).size
        validate(entries, json, gzBytes)

        val releases = Releases.find(options.releaseDirs.filter { it.isDirectory }, log)
        val latest = releases.maxByOrNull { it.version }
        val latestJson = latest?.let { Releases.catalogue(it, log) }
        val comparison = if (latest != null && latestJson != null) {
            ReleaseComparison(latest, latestJson, entries, applied.removedTitles, applied.removedEpisodes, log).also { guardVanished(it) }
        } else {
            null
        }
        val minVersionCode = minVersionCode(entries, latest)
        val used = Releases.usedVersions(options.releaseDirs.filter { it.isDirectory }, log)
        val highest = listOfNotNull(latest?.version, used.keys.maxOrNull()).maxOrNull()
        // Publishing the latest release again only helps while no higher number has been used since.
        val unchanged = latest != null && latestJson != null && json.contentEquals(latestJson) &&
            minVersionCode == latest.manifest.minVersionCode && latest.version == highest
        val version = version(releases, latest, used, unchanged)
        val republish = latest?.takeIf { unchanged && options.version == null }
        republish?.let { log.note("release ${it.version}", "holds exactly this catalogue, so it is published again as it is rather than as a new release") }

        log.closeTallies()
        return MergeResult(
            json = json.takeUnless { log.failed },
            entries = entries,
            filesFound = filesFound,
            filesUnreadable = filesUnreadable,
            copies = merger.copies,
            superseded = merger.superseded,
            refused = merger.refused,
            removeItems = removals.size,
            removedTitles = applied.removedTitles.size,
            removedEpisodes = applied.removedEpisodes.size,
            gzBytes = gzBytes,
            version = version,
            minVersionCode = minVersionCode,
            comparison = comparison,
            republish = republish,
        )
    }

    /** The result of a run that could not read its folders: nothing to write, and no release. */
    private fun failed(merger: TitleMerger, removals: RemoveList): MergeResult {
        log.closeTallies()
        return MergeResult(
            json = null,
            entries = emptyList(),
            filesFound = filesFound,
            filesUnreadable = filesUnreadable,
            copies = merger.copies,
            superseded = merger.superseded,
            refused = merger.refused,
            removeItems = removals.size,
            removedTitles = 0,
            removedEpisodes = 0,
            gzBytes = 0,
            version = null,
            minVersionCode = null,
            comparison = null,
            republish = null,
        )
    }

    // --- input -----------------------------------------------------------------------------------

    /** The folders exist, are folders, do not overlap, and hold none of this run's outputs. */
    private fun checkFolders(): Boolean {
        var usable = true
        fun folder(dir: File?, name: String) {
            when {
                dir == null -> Unit
                !dir.exists() -> log.error("$name folder", "${dir.path} does not exist").also { usable = false }
                !dir.isDirectory -> log.error("$name folder", "${dir.path} is a file, not a folder").also { usable = false }
            }
        }
        folder(options.addDir, "add")
        folder(options.removeDir, "remove")
        if (!usable) return false

        val add = options.addDir.canonicalFile
        val remove = options.removeDir?.canonicalFile
        if (remove != null && (remove.startsWith(add) || add.startsWith(remove))) {
            log.error("folders", "the add folder ($add) and the remove folder ($remove) overlap, so files would be read as both")
            return false
        }
        options.outputs.map { it.absoluteFile.canonicalFile }.forEach { output ->
            listOfNotNull(add, remove).filter { output.startsWith(it) }.forEach { input ->
                log.error("folders", "$output is inside $input, so the merge would read its own output back as input next time")
                usable = false
            }
        }
        return usable
    }

    private fun readAddFolder(merger: TitleMerger) {
        val found = InputFiles.discover(options.addDir.toPath(), "add", log, Severity.WARNING)
        val files = InputFiles.newestFirst(found, options.order)
        filesFound = files.size
        if (files.isEmpty()) {
            log.error("add folder", "holds no catalogue files (${InputFiles.EXTENSIONS}) in ${options.addDir.path}")
            return
        }
        describeOrder(files)
        val started = clock()
        var lastReport = started
        files.forEachIndexed { rank, file ->
            log.record("[${rank + 1}/${files.size}] ${file.display}, modified ${iso(file.modifiedMs)}, ${file.size} bytes")
            val values = InputReader.values(file, log, Severity.WARNING)
            if (values == null) {
                filesUnreadable++
            } else {
                log.record("    ${values.size} items")
                merger.add(rank, file, values)
            }
            val now = clock()
            if (now - lastReport >= PROGRESS_EVERY_MS) {
                lastReport = now
                progress("  read ${rank + 1} of ${files.size} files (${merger.copies} copies of titles so far)")
            }
        }
    }

    /** Says how the files were ranked, and warns when modification times cannot tell them apart. */
    private fun describeOrder(files: List<InputFile>) {
        log.record("Files, newest first (${options.order.description}):")
        if (options.order != FileOrder.MODIFIED || files.size < 2) return
        val times = files.map { it.modifiedMs }
        val spread = times.max() - times.min()
        if (spread <= SAME_MOMENT_MS) {
            log.warning(
                "add folder",
                "all ${files.size} files were modified within ${spread} ms of each other, as after a checkout or a copy, so their " +
                    "modification times may not say which is newer. Name them so they sort by age (2026-09-24-films.json) and use --order name",
            )
            return
        }
        val tied = times.groupingBy { it }.eachCount().values.filter { it > 1 }.sum()
        if (tied > 0) log.note("add folder", "$tied files share a modification time with another file; among them, the name decides")
    }

    private fun readRemoveFolder(removals: RemoveList) {
        val dir = options.removeDir ?: return
        val files = InputFiles.newestFirst(InputFiles.discover(dir.toPath(), "remove", log, Severity.ERROR), FileOrder.NAME)
        files.forEach { file ->
            log.record("${file.display}, modified ${iso(file.modifiedMs)}, ${file.size} bytes")
            val values = InputReader.values(file, log, Severity.ERROR) ?: return@forEach
            log.record("    ${values.size} items")
            removals.add(file, values)
        }
    }

    // --- output ----------------------------------------------------------------------------------

    private fun encode(entries: List<CatalogEntryDto>): ByteArray =
        CatalogueJson.catalogWriter.encodeToString(ListSerializer(CatalogEntryDto.serializer()), entries).encodeToByteArray()

    /**
     * Holds the catalogue to everything that decides whether it can be published: the size limits,
     * the content rules the app checks before installing, a faithful read-back, and `build`'s own pin
     * step, which must leave the bytes exactly as they are, so what is signed is what was checked here.
     */
    private fun validate(entries: List<CatalogEntryDto>, json: ByteArray, gzBytes: Int) {
        val where = "merged catalogue"
        if (entries.isEmpty()) {
            log.error(where, "has no titles; an empty catalogue is never published")
            return
        }
        if (json.size > CatalogRelease.MAX_JSON_BYTES) {
            log.error(
                where,
                "is ${json.size} bytes, over the ${CatalogRelease.MAX_JSON_BYTES}-byte limit a television loads" +
                    if (options.stripTrackers) "" else "; --strip-trackers usually brings it well under",
            )
        }
        if (gzBytes > CatalogRelease.MAX_GZ_BYTES) {
            log.error(where, "is $gzBytes bytes compressed, over the ${CatalogRelease.MAX_GZ_BYTES}-byte limit")
        }
        val decoded = runCatching {
            CatalogueJson.content.decodeFromString(ListSerializer(CatalogEntryDto.serializer()), json.decodeToString())
        }.getOrElse {
            log.error(where, "cannot be read back: ${describe(it)}")
            return
        }
        if (decoded != entries) log.error(where, "does not read back as it was written; this is a bug in the merge")
        CatalogContentRules.problems(decoded, countDeclaredTitles(json), requireExplicitIds = true).forEach {
            log.error(where, "breaks a publishing rule: $it")
        }
        runCatching { CatalogPinning.pin(json) }
            .onSuccess { if (!it.json.contentEquals(json)) log.error(where, "would be changed by the publisher's build step; this is a bug in the merge") }
            .onFailure { log.error(where, "is refused by the publisher's build step: ${describe(it)}") }
    }

    // --- release ---------------------------------------------------------------------------------

    private fun guardVanished(comparison: ReleaseComparison) {
        if (comparison.vanished == 0 || options.allowVanished || comparison.vanishedPercent <= options.maxVanishedPercent) return
        log.error(
            "compared with release ${comparison.version}",
            "${comparison.vanished} of its ${comparison.previousTitles} titles (${String.format(Locale.ROOT, "%.1f", comparison.vanishedPercent)}%) would disappear " +
                "without being in the remove folder. Check that no file is missing from the add folder; if they are meant to go, " +
                "list them in the remove folder or pass --allow-vanished",
        )
    }

    /**
     * The oldest app build that may install the release.
     *
     * Asked for explicitly, it is used, but never below what shows need. Otherwise it carries on from
     * the last release built here, raised to what shows need: going lower would hand a later release to
     * builds an earlier one kept away, for whatever reason it did.
     */
    private fun minVersionCode(entries: List<CatalogEntryDto>, latest: BuiltRelease?): Int? {
        val where = "min version code"
        val needed = FIRST_SHOWS_VERSION_CODE.takeIf { entries.any { it.isShow } }
        val previous = latest?.manifest?.minVersionCode
        val previousVersion = latest?.version
        val asked = options.minVersionCode
        val chosen = when {
            asked != null && needed != null && asked < needed -> {
                log.error(where, "$asked is too low: the catalogue has shows, and builds before $needed show them as films that cannot play")
                return null
            }
            asked != null -> {
                if (previous != null && asked < previous) {
                    log.warning(where, "$asked is lower than release $previousVersion's $previous, so builds $asked to ${previous - 1} will now install it")
                }
                asked
            }
            else -> listOfNotNull(needed, previous).maxOrNull()?.also { chosen ->
                val reasons = listOfNotNull(
                    needed?.let { "the catalogue has shows, which need build $it" },
                    previous?.let { "release $previousVersion needed build $it" },
                )
                log.note(where, "$chosen, because ${reasons.joinToString(" and ")}")
            }
        }
        val app = options.appVersionCode
        if (chosen != null && app != null && chosen > app) {
            log.error(where, "$chosen is above the app's current build ($app), so no television could install this release")
        }
        return chosen
    }

    /**
     * The release number: asked for, or one higher than any number used here, whether a release folder
     * still holds it or only the record of used numbers does. Never one a television would ignore.
     */
    private fun version(releases: List<BuiltRelease>, latest: BuiltRelease?, used: Map<Long, String>, unchanged: Boolean): Long? {
        val asked = options.version
        val usedMax = used.keys.maxOrNull()
        val highest = listOfNotNull(latest?.version, usedMax).maxOrNull()
        val highestAt = if (usedMax != null && usedMax == highest && (latest == null || usedMax > latest.version)) {
            "recorded as used in ${used.getValue(usedMax)}"
        } else {
            latest?.root?.path
        }
        return when {
            asked != null && highest != null && asked <= highest -> {
                log.error(
                    "version",
                    "release $asked is not newer than release $highest ($highestAt): a television installs only a higher " +
                        "number than it has. Use ${highest + 1} or more",
                )
                null
            }
            asked != null -> asked
            highest == null -> {
                val searched = options.releaseDirs.joinToString { it.path }.ifEmpty { "no folder" }
                log.error(
                    "version",
                    "no earlier release was found (searched $searched), so the next release number is not known: pass --version, " +
                        "one higher than the last release you published",
                )
                null
            }
            unchanged && latest != null -> latest.version
            else -> (highest + 1).also {
                log.note("version", "$it, one higher than release $highest ($highestAt); ${releases.size} release folders found")
            }
        }
    }

    private fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    companion object {
        /** The first app build (0.2.7) that understands shows. */
        const val FIRST_SHOWS_VERSION_CODE = 17

        /** Past this share of the previous release's titles vanishing, the run stops unless told otherwise. */
        const val DEFAULT_MAX_VANISHED_PERCENT = 5.0

        private const val PROGRESS_EVERY_MS = 5_000L

        /** Files modified within this span of each other are taken to have been copied or checked out together. */
        private const val SAME_MOMENT_MS = 2_000L
    }
}
