package com.torfilx.core.data.catalog

import com.torfilx.core.catalogue.format.CatalogRelease
import com.torfilx.core.common.log.TorfilxLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "CatalogStore"

/**
 * The downloaded catalogue releases on disk.
 *
 * ```
 * <root>/current.json                                     which release is installed
 * <root>/releases/<version>/                              the torrent's save directory
 * <root>/releases/<version>/torfilx-catalogue-<version>/  the release files
 * <root>/releases/<version>/catalogue.torrent             kept so the release can be seeded again
 * ```
 *
 * A release is downloaded straight into its own directory and verified there, so installing it is
 * one small atomic write of `current.json`. Nothing is ever moved while libtorrent has it open, and a
 * crash at any point leaves either the old release or the new one in use, never half of each.
 *
 * Lives under `filesDir/catalogue`, deliberately outside `filesDir/torrents`, which the torrent engine
 * empties on every start and measures against the storage budget.
 *
 * @param rootProvider resolved on first use, so building this object never touches the disk.
 */
class FetchedCatalogStore(rootProvider: () -> File) {

    @Serializable
    data class Installed(
        val version: Long,
        val infoHash: String,
        val installedAtMs: Long,
    )

    private val root: File by lazy(rootProvider)
    private val json = Json { ignoreUnknownKeys = true }
    private val releasesDir: File get() = File(root, RELEASES)
    private val pointerFile: File get() = File(root, CURRENT)

    /** The torrent save directory for [version]. */
    fun saveDirFor(version: Long): File = File(releasesDir, version.toString())

    /** Where the release files of [version] sit once downloaded. */
    fun releaseRootFor(version: Long): File = File(saveDirFor(version), CatalogRelease.rootDirName(version))

    private fun torrentFileFor(version: Long): File = File(saveDirFor(version), TORRENT_FILE)

    /** The installed release, or null when there is none or its files have gone. */
    @Synchronized
    fun installed(): Installed? {
        if (!pointerFile.isFile) return null
        val installed = runCatching { json.decodeFromString(Installed.serializer(), pointerFile.readText()) }
            .onFailure { TorfilxLog.w(TAG, "The installed-release record is unreadable; ignoring it", it) }
            .getOrNull()
            ?: return null
        if (installed.version <= 0 || !releaseRootFor(installed.version).isDirectory) return null
        return installed
    }

    /**
     * An empty save directory for downloading [version]. Anything left there by an earlier attempt is
     * removed first. The installed release is never touched.
     */
    @Synchronized
    fun prepareDownload(version: Long): File {
        require(version > 0) { "Catalogue versions start at 1" }
        check(version != installed()?.version) { "Catalogue $version is already installed" }
        val dir = saveDirFor(version)
        dir.deleteRecursively()
        check(dir.mkdirs() || dir.isDirectory) { "Could not create ${dir.path}" }
        return dir
    }

    /**
     * Makes [version] the installed release. Its files must already be in place and verified.
     *
     * The torrent is written first and the record last, so the release only counts as installed once
     * everything it needs is on disk.
     */
    @Synchronized
    fun install(version: Long, infoHash: String, torrentBytes: ByteArray, installedAtMs: Long): Installed {
        check(releaseRootFor(version).isDirectory) { "Catalogue $version has no files to install" }
        torrentFileFor(version).writeBytes(torrentBytes)
        val installed = Installed(version, infoHash, installedAtMs)
        writeAtomically(pointerFile, json.encodeToString(Installed.serializer(), installed))
        return installed
    }

    /** The bencoded torrent of an installed release, for seeding it again. */
    @Synchronized
    fun torrentBytes(version: Long): ByteArray? = torrentFileFor(version).takeIf { it.isFile }?.readBytes()

    /**
     * Forgets the installed release: the record first, so an interrupted removal can never leave a
     * half-deleted release in use, then its files.
     */
    @Synchronized
    fun uninstall() {
        val current = runCatching { json.decodeFromString(Installed.serializer(), pointerFile.readText()) }.getOrNull()
        pointerFile.delete()
        current?.let { saveDirFor(it.version).deleteRecursively() }
    }

    /** Deletes the files of [version], unless it is the installed release. */
    @Synchronized
    fun deleteRelease(version: Long) {
        if (version == installed()?.version) return
        saveDirFor(version).deleteRecursively()
    }

    /**
     * Deletes every release except the installed one: failed and refused downloads, and releases that
     * have been replaced. Best effort; anything still locked is retried next time.
     *
     * @return how many release directories were removed.
     */
    @Synchronized
    fun deleteAllExceptInstalled(): Int {
        val keep = installed()?.version?.toString()
        val stale = releasesDir.listFiles().orEmpty().filter { it.name != keep }
        return stale.count { it.deleteRecursively() }
    }

    private fun writeAtomically(target: File, text: String) {
        check(root.isDirectory || root.mkdirs()) { "Could not create ${root.path}" }
        val temporary = File(root, target.name + ".tmp")
        temporary.writeText(text)
        // Rename replaces the target atomically on Android. Windows, where unit tests may run, refuses
        // to rename over an existing file, so there the old one is removed first.
        if (!temporary.renameTo(target)) {
            target.delete()
            check(temporary.renameTo(target)) { "Could not write ${target.path}" }
        }
    }

    private companion object {
        const val RELEASES = "releases"
        const val CURRENT = "current.json"
        const val TORRENT_FILE = "catalogue.torrent"
    }
}
