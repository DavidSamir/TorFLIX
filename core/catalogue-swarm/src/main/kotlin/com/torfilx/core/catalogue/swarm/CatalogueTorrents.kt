package com.torfilx.core.catalogue.swarm

import com.torfilx.core.catalogue.format.CatalogRelease
import org.libtorrent4j.Entry
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.create_torrent
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.file_storage
import org.libtorrent4j.swig.libtorrent
import java.io.File

/** Building release torrents, and checking that a torrent really is one. */
object CatalogueTorrents {

    /**
     * Small pieces, because releases are small. A few hundred kilobytes in 16 KiB pieces is a few dozen
     * pieces, so a download that is interrupted resumes from almost exactly where it stopped.
     */
    const val PIECE_SIZE = 16 * 1024

    const val DEFAULT_CREATOR = "torfilx-catalog-publisher"

    /** libtorrent chooses the pad file limit and the piece alignment itself. */
    private const val LIBTORRENT_DEFAULT = -1

    /**
     * Builds the torrent for the release in [releaseRoot], a `torfilx-catalogue-<version>` directory
     * holding exactly the three release files.
     *
     * @return the bencoded torrent. Building the same release again gives the same info hash.
     */
    fun build(
        releaseRoot: File,
        trackers: List<String>,
        creator: String = DEFAULT_CREATOR,
        comment: String? = null,
    ): ByteArray {
        require(CatalogRelease.isReleaseRootName(releaseRoot.name)) {
            "${releaseRoot.path} is not a release directory (expected torfilx-catalogue-<version>)"
        }
        val present = releaseRoot.list()?.toSet().orEmpty()
        require(present == CatalogRelease.RELEASE_FILES) {
            "${releaseRoot.path} must hold exactly ${CatalogRelease.RELEASE_FILES.sorted()}; it holds ${present.sorted()}"
        }
        val torrent = buildTorrent(releaseRoot, trackers, PIECE_SIZE, creator, comment)
        layoutProblem(TorrentInfo(torrent))?.let { problem ->
            error("libtorrent built a torrent that is not a valid release: $problem")
        }
        return torrent
    }

    /**
     * Builds a torrent of everything in [root], whatever it holds.
     *
     * This does what libtorrent4j's `TorrentBuilder` does, with one difference that matters. libtorrent
     * 1.2's torrent creator keeps only a reference to the file list it is given, and `TorrentBuilder`
     * lets go of its last Java reference to that list as soon as the creator exists. A garbage
     * collection while the pieces are hashed then frees the list under libtorrent. Here both stay
     * reachable until the torrent has been generated.
     *
     * @param pieceSize bytes per piece, or 0 to let libtorrent choose.
     */
    internal fun buildTorrent(
        root: File,
        trackers: List<String>,
        pieceSize: Int,
        creator: String?,
        comment: String?,
    ): ByteArray {
        val absolute = root.absoluteFile
        val parent = requireNotNull(absolute.parentFile) { "${root.path} has no parent directory" }
        val files = file_storage()
        libtorrent.add_files(files, absolute.path, create_torrent.optimize_alignment)
        require(files.total_size() > 0) { "${root.path} holds no data" }

        val creatorOfTorrent = create_torrent(
            files,
            pieceSize,
            LIBTORRENT_DEFAULT,
            create_torrent.optimize_alignment,
            LIBTORRENT_DEFAULT,
        )
        val hashError = error_code()
        libtorrent.set_piece_hashes(creatorOfTorrent, parent.path, hashError)
        check(hashError.value() == 0) { "libtorrent could not hash ${root.path}: ${hashError.message()}" }
        comment?.let { creatorOfTorrent.set_comment(it) }
        creator?.let { creatorOfTorrent.set_creator(it) }
        trackers.forEach { creatorOfTorrent.add_tracker(it, 0) }

        val torrent = Entry(creatorOfTorrent.generate()).bencode()
        NativeReachability.fence(creatorOfTorrent)
        NativeReachability.fence(files)
        return torrent
    }

    /** The torrent's info hash, 40 lower-case hex characters. */
    fun infoHash(torrent: ByteArray): String {
        val info = TorrentInfo(torrent)
        // The hash points into the torrent info's native memory without keeping it alive.
        val hex = info.infoHash().toHex()
        NativeReachability.fence(info)
        return hex
    }

    fun magnet(torrent: ByteArray): String = TorrentInfo(torrent).makeMagnetUri()

    /**
     * Why [info] is not a catalogue release, or null when it is one.
     *
     * A release torrent is a single `torfilx-catalogue-<version>` directory holding exactly the three
     * release files. Anything else is refused before a byte of it is downloaded, which keeps a
     * mistaken or hostile pointer from filling a television's storage with something that is not a
     * catalogue.
     */
    fun layoutProblem(info: TorrentInfo): String? {
        val name = info.name()
        if (!CatalogRelease.isReleaseRootName(name)) return "the torrent is named \"$name\", not torfilx-catalogue-<version>"
        val files = info.files()
        val seen = HashSet<String>()
        var problem: String? = null
        for (index in 0 until files.numFiles()) {
            if (files.padFileAt(index)) continue
            val path = files.filePath(index).replace(File.separatorChar, '/')
            val fileName = path.removePrefix("$name/")
            if (!path.startsWith("$name/") || fileName !in CatalogRelease.RELEASE_FILES) {
                problem = "the torrent holds an unexpected file \"$path\""
                break
            }
            seen += fileName
        }
        // The file list points into the torrent info.
        NativeReachability.fence(info)
        if (problem != null) return problem
        val missing = CatalogRelease.RELEASE_FILES - seen
        return if (missing.isEmpty()) null else "the torrent is missing ${missing.sorted()}"
    }
}
