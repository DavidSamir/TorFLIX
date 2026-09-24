package com.torfilx.tools.catalog.merge

import com.torfilx.core.catalogue.CatalogueJson
import com.torfilx.core.catalogue.crypto.Sha256
import com.torfilx.core.catalogue.format.CatalogEntryDto
import com.torfilx.core.catalogue.format.CatalogIds
import com.torfilx.core.catalogue.format.CatalogManifest
import com.torfilx.core.catalogue.format.CatalogRelease
import kotlinx.serialization.builtins.ListSerializer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream

/** A release already built on this machine: `torfilx-catalogue-<version>/` with its manifest. */
internal class BuiltRelease(val root: File, val manifest: CatalogManifest) {
    val version: Long get() = manifest.catalogVersion
}

/** Finds the releases already built, and reads one back. */
internal object Releases {

    /**
     * The record of release numbers already used, one per line (`3  2026-09-24 built`), kept beside the
     * releases. A television ignores a release not newer than the one it has, so a number once used is
     * never used again, even after its folder is deleted.
     */
    const val USED_VERSIONS_FILE = "versions-used.txt"

    /** Every release number recorded in a [USED_VERSIONS_FILE] under [dirs], with where it is recorded. */
    fun usedVersions(dirs: List<File>, log: MergeLog): Map<Long, String> {
        val used = LinkedHashMap<Long, String>()
        dirs.map { File(it, USED_VERSIONS_FILE) }.filter { it.isFile }.forEach { file ->
            val text = try {
                TextDecoding.decode(file.readBytes())
            } catch (error: UnreadableFile) {
                log.error(file.path, "${error.message}; the release numbers it records cannot be checked")
                return@forEach
            }
            text.lines().forEachIndexed { index, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val version = line.split(WHITESPACE).first().toLongOrNull()?.takeIf { it > 0 }
                if (version == null) {
                    log.warning("${file.path} line ${index + 1}", "does not start with a release number; ignored")
                } else {
                    used.putIfAbsent(version, "${file.path} line ${index + 1}")
                }
            }
        }
        return used
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * Every complete release under [dirs]. A folder that looks like a release but has no readable
     * manifest, or a manifest for a different version, is reported and ignored.
     */
    fun find(dirs: List<File>, log: MergeLog): List<BuiltRelease> = dirs.filter { it.isDirectory }.flatMap { dir ->
        dir.listFiles().orEmpty()
            .filter { it.isDirectory && CatalogRelease.isReleaseRootName(it.name) }
            .mapNotNull { root -> read(root, log) }
    }

    private fun read(root: File, log: MergeLog): BuiltRelease? {
        val manifestFile = File(root, CatalogRelease.MANIFEST)
        val where = root.path
        if (!manifestFile.isFile || manifestFile.length() > CatalogRelease.MAX_MANIFEST_BYTES) {
            log.warning(where, "looks like a release but has no usable ${CatalogRelease.MANIFEST}; ignored")
            return null
        }
        val manifest = runCatching { CatalogueJson.manifest.decodeFromString(CatalogManifest.serializer(), manifestFile.readText()) }.getOrNull()
        if (manifest == null) {
            log.warning(where, "has a ${CatalogRelease.MANIFEST} that cannot be read; ignored")
            return null
        }
        if (CatalogRelease.rootDirName(manifest.catalogVersion) != root.name) {
            log.warning(where, "holds release ${manifest.catalogVersion}, which does not match its folder's name; ignored")
            return null
        }
        return BuiltRelease(root, manifest)
    }

    /** The catalogue of [release], checked against its manifest; null (with a warning) when damaged. */
    fun catalogue(release: BuiltRelease, log: MergeLog): ByteArray? {
        val gzFile = File(release.root, CatalogRelease.CATALOG_GZ)
        val manifest = release.manifest
        val problem = when {
            !gzFile.isFile -> "has no ${CatalogRelease.CATALOG_GZ}"
            gzFile.length() != manifest.gzBytes -> "has a ${CatalogRelease.CATALOG_GZ} of the wrong size"
            else -> null
        }
        val gz = if (problem == null) gzFile.readBytes() else null
        val json = gz?.takeIf { Sha256.hex(it) == manifest.sha256 }?.let { runCatching { gunzip(it, manifest.jsonBytes) }.getOrNull() }
        if (json == null || json.size.toLong() != manifest.jsonBytes) {
            log.warning(release.root.path, "${problem ?: "does not match its manifest"}, so it cannot be compared with; nothing was checked against it")
            return null
        }
        return json
    }

    private fun gunzip(gz: ByteArray, expected: Long): ByteArray = GZIPInputStream(gz.inputStream()).use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            check(out.size() <= expected) { "longer than its manifest says" }
        }
        out.toByteArray()
    }

    private const val BUFFER_BYTES = 64 * 1024
}

/**
 * What changes for televisions between the previous release and the new catalogue.
 *
 * Titles and episodes are matched by id, the key every viewer's progress and My List are stored under.
 * A title missing from the new catalogue was either removed on purpose (listed in the remove folder)
 * or it vanished: nothing asked for it to go. Vanishing is how a deleted or damaged input file shows up,
 * so it is reported one by one, and [CatalogMerge] stops the run when too many vanish.
 * A vanished title whose name and year reappear under another id has only changed id, which loses every
 * viewer's progress on it; that is reported with the id to put back.
 */
internal class ReleaseComparison(
    previous: BuiltRelease,
    previousJson: ByteArray,
    current: List<CatalogEntryDto>,
    removedTitles: Set<String>,
    removedEpisodes: Set<String>,
    private val log: MergeLog,
) {
    val version = previous.version
    var added = 0
        private set
    var removed = 0
        private set
    var vanished = 0
        private set
    var changed = 0
        private set
    var unchanged = 0
        private set
    val previousTitles: Int

    init {
        val where = "compared with release $version"
        val before = runCatching {
            CatalogueJson.content.decodeFromString(ListSerializer(CatalogEntryDto.serializer()), previousJson.decodeToString())
        }.getOrElse { emptyList() }
        previousTitles = before.size
        val beforeById = before.filter { it.id != null }.associateBy { it.id!! }
        val nowById = current.associateBy { it.id!! }
        added = nowById.keys.count { it !in beforeById }

        val gone = beforeById.keys.filter { it !in nowById }
        removed = gone.count { it in removedTitles }
        val vanishedIds = gone.filter { it !in removedTitles }
        vanished = vanishedIds.size
        val newIdsByName = current.filter { it.id !in beforeById }.groupBy { nameKey(it) }
        vanishedIds.forEach { id ->
            val old = beforeById.getValue(id)
            val renamed = newIdsByName[nameKey(old)].orEmpty()
            val label = "\"${old.title}\"${old.year?.let { " ($it)" }.orEmpty()}, id $id"
            if (renamed.isNotEmpty()) {
                log.warning(
                    where,
                    "$label now has the id ${renamed.joinToString { it.id!! }}, so every viewer's progress and My List entry for it is lost. " +
                        "To keep them, give its entry \"id\": \"$id\"",
                )
            } else {
                log.warning(where, "$label is in release $version but in neither the add nor the remove folder, so it disappears")
            }
        }

        beforeById.forEach { (id, old) ->
            val now = nowById[id] ?: return@forEach
            if (now == old) unchanged++ else changed++
            if (old.isShow && now.isShow) compareEpisodes(old, now, removedEpisodes, where)
        }
    }

    private fun compareEpisodes(old: CatalogEntryDto, now: CatalogEntryDto, removedEpisodes: Set<String>, where: String) {
        val nowIds = now.seasons.flatMapTo(HashSet()) { season -> season.episodes.mapNotNull { it.id } }
        val nowByCode = now.seasons.flatMap { season -> season.episodes.map { code(season.number, it.number) to it.id } }.toMap()
        val lost = ArrayList<String>()
        val moved = ArrayList<String>()
        old.seasons.forEach { season ->
            season.episodes.forEach { episode ->
                val id = episode.id ?: return@forEach
                if (id in nowIds || id in removedEpisodes) return@forEach
                val code = code(season.number, episode.number)
                val newId = nowByCode[code]
                if (newId != null) moved += "$code ($id is now $newId)" else lost += code
            }
        }
        if (moved.isNotEmpty()) {
            log.warning(
                where,
                "\"${now.title}\": ${moved.size} episodes changed id, so viewers' progress on them is lost: ${preview(moved, limit = 5)}. " +
                    "Give each its old \"id\" to keep it",
            )
        }
        if (lost.isNotEmpty()) {
            log.warning(where, "\"${now.title}\": ${lost.size} episodes in release $version are in neither folder, so they disappear: ${preview(lost)}")
        }
    }

    /** The share of the previous release's titles that vanished, in percent. */
    val vanishedPercent: Double get() = if (previousTitles == 0) 0.0 else vanished * PERCENT / previousTitles

    private fun nameKey(entry: CatalogEntryDto) = Triple(entry.isShow, CatalogIds.slug(entry.title.trim()), entry.year?.trim())

    private fun code(season: Int?, episode: Int?) = "S${twoDigits(season ?: 0)}E${twoDigits(episode ?: 0)}"

    private companion object {
        const val PERCENT = 100.0
    }
}
