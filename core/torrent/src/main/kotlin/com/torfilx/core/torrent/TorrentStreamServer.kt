package com.torfilx.core.torrent

import com.torfilx.core.common.log.TorfilxLog
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.min

private const val TAG = "TorrentHttp"

/** Piece index (clamped to the torrent) that holds [fileByteOffset] of a file at [fileOffset]. */
internal fun pieceIndexOf(fileOffset: Long, pieceLength: Int, numPieces: Int, fileByteOffset: Long): Int =
    ((fileOffset + fileByteOffset) / pieceLength).toInt().coerceIn(0, numPieces - 1)

/**
 * Bytes from [fileByteOffset] to the end of the piece that contains it — the maximum a single read
 * may cover without spilling into the following piece. Pure arithmetic, unit-tested, because getting
 * it wrong feeds zero-filled holes to the decoder.
 */
internal fun bytesUntilPieceEnd(fileOffset: Long, pieceLength: Int, fileByteOffset: Long): Long {
    val absolute = fileOffset + fileByteOffset
    val nextBoundary = ((absolute / pieceLength) + 1) * pieceLength.toLong()
    return (nextBoundary - absolute).coerceAtLeast(1L)
}

/**
 * Percent-encodes a file name so it survives as a single URL path segment.
 *
 * `URLEncoder` is form encoding, not path encoding: it turns a space into `+`, which a path reader
 * would take literally, so that one case is corrected. Slashes are already encoded by it.
 */
internal fun String.toUrlPathSegment(): String =
    java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

/**
 * The MIME type for a video file name.
 *
 * Serving everything as `video/mp4` (what this used to do) tells ExoPlayer to try the MP4 extractor
 * first for an MKV or AVI. It recovers by sniffing, but only after wasting the first reads of a
 * stream that is arriving piece by piece — and a mis-sniff on a partially-arrived header is one more
 * way for a title to fail on the first attempt and work on the third.
 */
internal fun contentTypeFor(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        "wmv" -> "video/x-ms-wmv"
        "mpg", "mpeg" -> "video/mpeg"
        "ogv" -> "video/ogg"
        "3gp" -> "video/3gpp"
        else -> "video/mp4"
    }

/**
 * What the HTTP server needs from the thing it is serving.
 *
 * Narrow on purpose: everything here is answerable without libtorrent, so the server's protocol
 * behaviour — in particular its promise never to send a short body — is testable against a fake with
 * a real file behind it. Before this, the only implementation dragged in a native `TorrentHandle` and
 * the server could not be exercised at all.
 */
internal interface StreamSource {
    val infoHash: String

    /** Index of the file within the torrent: part of its URL, so a stale request can be told apart. */
    val fileIndex: Int
    val fileName: String
    val fileSizeBytes: Long
    val filePath: File

    /** True when the byte at [fileByteOffset] is on disk and safe to read. */
    fun isReadableAt(fileByteOffset: Long): Boolean

    /** How far a single read may go from [fileByteOffset] without crossing into another piece. */
    fun bytesUntilPieceEnd(fileByteOffset: Long): Long

    /** Asks for the pieces around [fileByteOffset] to be fetched next. */
    fun prioritiseFrom(fileByteOffset: Long)

    /** Marks the source as recently used, so eviction leaves it alone. */
    fun touch()

    /**
     * The file being served right now, fixed for the length of one response.
     *
     * A season pack serves one episode's file and then another, from the same torrent. A response
     * that began on one file must keep reading that file's pieces even if the torrent has moved on to
     * the next episode meanwhile; reading the new file's offsets would hand the decoder bytes from the
     * wrong episode. A source whose file never changes is its own view.
     */
    fun view(): StreamSource = this
}

/** One file of a torrent: where it is, how big, and where its bytes start within the torrent. */
internal data class SelectedFile(
    val index: Int,
    val name: String,
    val sizeBytes: Long,
    val path: File,
    /** Byte offset of the file within the torrent's concatenated data. */
    val offset: Long,
)

/**
 * One torrent being streamed, plus the piece bookkeeping that makes seeking work.
 *
 * The file it serves can change: a season pack plays one episode's file and then the next from the
 * same torrent ([select]). The pieces already downloaded stay on disk and keep seeding.
 */
internal class StreamedTorrent(
    override val infoHash: String,
    val handle: TorrentHandle,
    file: SelectedFile,
    val pieceLength: Int,
    val numPieces: Int,
    var lastTouchedMs: Long,
    /**
     * What the viewer knows this as — "The Twilight Zone · S1 E3" — for the sharing figures and the
     * contribution record. Null falls back to the release's file name.
     */
    @Volatile var displayName: String? = null,
) : StreamSource {

    /** The file being served now. */
    @Volatile
    var selected: SelectedFile = file
        private set

    override val fileIndex: Int get() = selected.index
    override val fileName: String get() = selected.name
    override val fileSizeBytes: Long get() = selected.sizeBytes
    override val filePath: File get() = selected.path
    val fileOffset: Long get() = selected.offset

    /** Eviction orders by last use; streaming a byte counts as use. */
    override fun touch() {
        lastTouchedMs = System.currentTimeMillis()
    }

    /**
     * False once playback has stopped but the torrent is still in the session, seeding.
     *
     * Kept in the same map as streaming torrents so seeding shows up in the sharing stats and is
     * still governed by the storage budget — a torrent that seeds invisibly is one nobody can
     * manage.
     */
    @Volatile
    var isStreaming: Boolean = true

    /**
     * Serves [file] from now on: it alone is wanted, the old file's outstanding deadlines are dropped,
     * and its start is fetched first. What was already downloaded of the old file stays on disk and
     * keeps seeding; nothing is deleted.
     *
     * @param fileCount files in the torrent, for the priority array libtorrent expects.
     */
    fun select(file: SelectedFile, fileCount: Int) {
        if (file.index == selected.index) return
        runCatching {
            handle.clearPieceDeadlines()
            val priorities = Array(fileCount) { Priority.IGNORE }
            priorities[file.index] = Priority.DEFAULT
            handle.prioritizeFiles(priorities)
        }.onFailure { TorfilxLog.w(TAG, "Could not switch $infoHash to file ${file.index}", it) }
        selected = file
        prioritiseFrom(0L)
    }

    override fun view(): StreamSource = FileView(selected)

    /**
     * The loopback URL for this torrent's selected file.
     *
     * `/<infoHash>/<fileIndex>/<name>`. The server keys off the info hash and refuses a request whose
     * file index is no longer the one selected, so a player still asking for the previous episode is
     * told so instead of being fed the next one. The name is last purely so the URL carries the real
     * extension (`.mkv`, `.avi`, …): ExoPlayer uses it to order its extractors, which speeds up the
     * first open and stops a mis-sniffed container from being handed to the wrong parser.
     */
    fun toStream(port: Int): TorrentStream {
        val file = selected
        return TorrentStream(
            infoHash = infoHash,
            url = "http://127.0.0.1:$port/$infoHash/${file.index}/${file.name.toUrlPathSegment()}",
            fileName = file.name,
            fileSizeBytes = file.sizeBytes,
        )
    }

    override fun isReadableAt(fileByteOffset: Long): Boolean = isReadableAt(selected, fileByteOffset)

    /** Piece index containing [fileByteOffset] of the selected file. */
    fun pieceOf(fileByteOffset: Long): Int = pieceOf(selected, fileByteOffset)

    fun hasByte(fileByteOffset: Long): Boolean = hasByte(selected, fileByteOffset)

    /**
     * Bytes from [fileByteOffset] to the end of the piece that contains it.
     *
     * A read is capped to this so it never spans into the next piece: `havePiece` is verified for
     * the piece at the read position, but the following piece may still be a hole in the sparse file
     * that would read back as zeros and feed corrupt data to the decoder.
     */
    override fun bytesUntilPieceEnd(fileByteOffset: Long): Long =
        bytesUntilPieceEnd(selected.offset, pieceLength, fileByteOffset)

    override fun prioritiseFrom(fileByteOffset: Long) = prioritiseFrom(selected, fileByteOffset)

    private fun pieceOf(file: SelectedFile, fileByteOffset: Long): Int =
        pieceIndexOf(file.offset, pieceLength, numPieces, fileByteOffset)

    private fun hasByte(file: SelectedFile, fileByteOffset: Long): Boolean =
        runCatching { handle.havePiece(pieceOf(file, fileByteOffset)) }.getOrDefault(false)

    private fun isReadableAt(file: SelectedFile, fileByteOffset: Long): Boolean =
        file.path.exists() && hasByte(file, fileByteOffset)

    /**
     * Prioritises the pieces just after [fileByteOffset] of [file].
     *
     * This is what turns BitTorrent (which normally fetches rarest-first, in any order) into
     * something streamable: a deadline on the next few pieces and descending priority after that.
     */
    private fun prioritiseFrom(file: SelectedFile, fileByteOffset: Long) {
        runCatching {
            val first = pieceOf(file, fileByteOffset)
            val last = min(first + READ_AHEAD_PIECES, numPieces - 1)
            for (piece in first..last) {
                if (handle.havePiece(piece)) continue
                val distance = piece - first
                handle.piecePriority(
                    piece,
                    when {
                        distance < URGENT_PIECES -> Priority.TOP_PRIORITY
                        distance < URGENT_PIECES * 2 -> Priority.SIX
                        else -> Priority.FIVE
                    },
                )
                // A deadline tells libtorrent to fetch this piece within N ms or complain, which is
                // what keeps playback ahead of the download on a slow swarm.
                handle.setPieceDeadline(piece, distance * DEADLINE_STEP_MS)
            }
        }.onFailure { TorfilxLog.w(TAG, "Could not prioritise pieces", it) }
    }

    /**
     * One response's view of the torrent: always the file it started on. It never re-prioritises a
     * file that is no longer selected — that would pull the swarm back to an episode nobody is
     * watching — it only reads what has already arrived.
     */
    private inner class FileView(private val file: SelectedFile) : StreamSource {
        override val infoHash: String get() = this@StreamedTorrent.infoHash
        override val fileIndex: Int get() = file.index
        override val fileName: String get() = file.name
        override val fileSizeBytes: Long get() = file.sizeBytes
        override val filePath: File get() = file.path

        override fun isReadableAt(fileByteOffset: Long): Boolean = isReadableAt(file, fileByteOffset)

        override fun bytesUntilPieceEnd(fileByteOffset: Long): Long =
            bytesUntilPieceEnd(file.offset, pieceLength, fileByteOffset)

        override fun prioritiseFrom(fileByteOffset: Long) {
            if (selected.index == file.index) prioritiseFrom(file, fileByteOffset)
        }

        override fun touch() = this@StreamedTorrent.touch()
    }

    private companion object {
        const val READ_AHEAD_PIECES = 40
        const val URGENT_PIECES = 6
        const val DEADLINE_STEP_MS = 500
    }
}

/**
 * A minimal loopback HTTP server that serves a torrent's video file *while it downloads*.
 *
 * ExoPlayer speaks HTTP with byte ranges; BitTorrent delivers pieces. This bridges the two: a read
 * that lands on a missing piece re-prioritises around it and waits, rather than failing, so seeking
 * into an undownloaded part of the film works instead of erroring out.
 *
 * It binds to 127.0.0.1 only — nothing outside the device can reach it.
 */
internal class TorrentStreamServer(
    /** How long the first byte of a response may take before the request is refused with a 503. */
    private val firstByteTimeoutMs: Long = FIRST_BYTE_TIMEOUT_MS,
    /** How long a mid-body read waits for the next piece before treating the swarm as stalled. */
    private val pieceTimeoutMs: Long = PIECE_TIMEOUT_MS,
) {

    private val streams = ConcurrentHashMap<String, StreamSource>()
    private val executor = Executors.newCachedThreadPool()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var port: Int = 0
        private set

    fun start() {
        if (serverSocket != null) return
        val socket = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        thread(name = "torfilx-torrent-http", isDaemon = true) { acceptLoop(socket) }
        TorfilxLog.i(TAG, "Loopback stream server on 127.0.0.1:$port")
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        streams.clear()
        executor.shutdownNow()
    }

    fun register(streamed: StreamSource) {
        streams[streamed.infoHash] = streamed
    }

    fun unregister(infoHash: String) {
        streams.remove(infoHash)
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (closed: SocketException) {
                return
            } catch (error: IOException) {
                TorfilxLog.w(TAG, "Accept failed", error)
                continue
            }
            executor.execute { runCatching { handle(client) }.onFailure { logClientFailure(it) } }
        }
    }

    private fun logClientFailure(error: Throwable) {
        // A player closing the connection mid-stream is normal (seek, exit) and not worth an error.
        if (error is SocketException || error is IOException) {
            TorfilxLog.d(TAG, "Client disconnected: ${error.message}")
        } else {
            TorfilxLog.w(TAG, "Stream request failed", error)
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = socket.getInputStream().bufferedReader()
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].trimStart('/')

            var rangeStart = 0L
            var rangeEnd = -1L
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    // Only byte ranges are understood; any other unit is ignored (served as full).
                    val value = line.substringAfter(':').trim()
                    if (value.startsWith("bytes=", ignoreCase = true)) {
                        val spec = value.substringAfter('=').trim()
                        rangeStart = (spec.substringBefore('-').toLongOrNull() ?: 0L).coerceAtLeast(0L)
                        rangeEnd = spec.substringAfter('-').toLongOrNull() ?: -1L
                    }
                }
            }

            // The key is the first path segment. A numeric second segment is the file index, and a
            // request for a file the torrent no longer serves (the previous episode of a season pack)
            // is refused rather than answered with the new file's bytes. Anything else after the key —
            // the file name, a query string — is decoration that must not affect the lookup.
            val segments = path.substringBefore('?').split('/')
            val key = segments.first()
            val requestedFile = segments.getOrNull(1)?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
            // One view for the whole response, so it keeps reading the file it started on.
            val streamed = streams[key]?.view()?.takeIf { requestedFile == null || requestedFile == it.fileIndex }
            val output = BufferedOutputStream(socket.getOutputStream())
            if (streamed == null) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                output.flush()
                return
            }

            val total = streamed.fileSizeBytes
            val rangeRequested = rangeStart > 0 || rangeEnd >= 0

            // A range starting at or past the end of the file is unsatisfiable. Answer 416 rather
            // than a technically-invalid 0-length 206, which some ExoPlayer data sources treat as a
            // hard error.
            if (rangeRequested && rangeStart >= total) {
                output.write(
                    (
                        "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                            "Content-Range: bytes */$total\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n"
                        ).toByteArray(),
                )
                output.flush()
                return
            }

            val end = if (rangeEnd in 0 until total) rangeEnd else total - 1
            val length = (end - rangeStart + 1).coerceAtLeast(0)

            // A HEAD is only ever a probe for the length and range support, so it needs no data and
            // must not be made to wait for a piece.
            val isHead = method.equals("HEAD", ignoreCase = true)

            // Nothing is committed to the socket until the first byte can actually be produced.
            //
            // This is the fix for "opening a title says network error, and works on the third try".
            // Previously the headers — including a Content-Length — went out immediately and the body
            // loop then discovered that libtorrent had not yet created the file on disk, returned,
            // and closed the socket. A body shorter than its declared Content-Length is a hard I/O
            // failure to ExoPlayer, surfaced as "Connection lost". It happened on the *first* request
            // for every new title, because that is exactly when the file does not exist yet.
            //
            // Failing *before* the status line means a failure can be an honest 503, which is
            // retryable and diagnosable, instead of a corrupt response.
            if (!isHead) {
                streamed.prioritiseFrom(rangeStart)
                if (!awaitReadable(streamed, rangeStart, firstByteTimeoutMs)) {
                    TorfilxLog.w(
                        TAG,
                        "No data for ${streamed.fileName} at $rangeStart after " +
                            "${firstByteTimeoutMs / 1000}s; answering 503",
                    )
                    output.write(
                        (
                            "HTTP/1.1 503 Service Unavailable\r\n" +
                                "Content-Length: 0\r\nConnection: close\r\n\r\n"
                            ).toByteArray(),
                    )
                    output.flush()
                    return
                }
            }

            val headers = buildString {
                append(if (rangeRequested) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: ${contentTypeFor(streamed.fileName)}\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $length\r\n")
                if (rangeRequested) {
                    append("Content-Range: bytes $rangeStart-$end/$total\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            output.write(headers.toByteArray())
            if (isHead) {
                output.flush()
                return
            }

            serveBytes(streamed, rangeStart, length, output)
        }
    }

    /**
     * Writes the body.
     *
     * The contract with the client is absolute: exactly [length] bytes must be written, because that
     * is what the `Content-Length` header promised. Every path out of the loop other than "wrote it
     * all" is therefore a broken response, and the only honest way to end early is to close the
     * socket — which is what happens when this throws. That case is now reserved for a swarm that has
     * genuinely stalled, not for the routine "the next piece has not landed yet".
     */
    private fun serveBytes(
        streamed: StreamSource,
        start: Long,
        length: Long,
        output: BufferedOutputStream,
    ) {
        val buffer = ByteArray(CHUNK_BYTES)
        var position = start
        var remaining = length

        // One handle for the whole response: re-opening it for every 64 KB chunk was hundreds of
        // thousands of syscalls per movie on a weak Fire TV CPU. The file is known to exist — the
        // caller waited for the first byte before sending headers — but it can still be evicted or
        // moved underneath us, so a failure to open is handled rather than thrown blind.
        val raf = try {
            RandomAccessFile(streamed.filePath, "r")
        } catch (error: IOException) {
            TorfilxLog.w(TAG, "Cannot open ${streamed.filePath.name} for streaming", error)
            throw error
        }

        raf.use {
            while (remaining > 0) {
                if (!awaitReadable(streamed, position, pieceTimeoutMs)) {
                    // Truly stalled. The response is now unavoidably short; say so plainly in the log
                    // so the cause is not mistaken for a decoder problem, and let the closed socket
                    // tell the player.
                    TorfilxLog.w(
                        TAG,
                        "Swarm stalled at byte $position of ${streamed.fileName} " +
                            "(${remaining / 1024} KB of the response undelivered)",
                    )
                    return
                }
                // Re-prioritise as playback advances so the swarm stays ahead of the read head.
                if ((position - start) % PRIORITISE_EVERY_BYTES < CHUNK_BYTES) {
                    streamed.prioritiseFrom(position)
                }

                // Never read past the end of the piece just confirmed present: a read that crossed
                // into the next (possibly not-yet-downloaded) piece would return zero-filled holes
                // from the sparse file and hand corrupt bytes to the decoder.
                val toRead = minOf(remaining, CHUNK_BYTES.toLong(), streamed.bytesUntilPieceEnd(position))
                val fileLen = raf.length()
                if (fileLen <= position) {
                    // Data verified present but not yet flushed to the file; wait briefly and retry.
                    Thread.sleep(WAIT_STEP_MS)
                    continue
                }
                val readable = min(toRead, fileLen - position).toInt()
                raf.seek(position)
                val read = raf.read(buffer, 0, readable)
                if (read <= 0) {
                    Thread.sleep(WAIT_STEP_MS)
                    continue
                }
                output.write(buffer, 0, read)
                output.flush()
                position += read
                remaining -= read
                streamed.touch()
            }
        }
    }

    /**
     * Blocks until the byte at [position] can be read, or [timeoutMs] elapses.
     *
     * "Can be read" means both that libtorrent reports the piece present *and* that the file exists
     * on disk — the file is created lazily, so on the first request of a new title it is missing for
     * a moment even though the download is healthy. Treating that as a failure is what made a fresh
     * title fail on its first attempt and succeed on a later one.
     *
     * The wait is re-prioritised periodically rather than once: on a slow swarm, the piece the reader
     * needs can otherwise sit behind a queue that was ordered before the read head moved.
     */
    private fun awaitReadable(streamed: StreamSource, position: Long, timeoutMs: Long): Boolean {
        if (streamed.isReadableAt(position)) return true
        streamed.prioritiseFrom(position)
        var waited = 0L
        while (waited < timeoutMs) {
            Thread.sleep(WAIT_STEP_MS)
            waited += WAIT_STEP_MS
            if (streamed.isReadableAt(position)) return true
            if (waited % REPRIORITISE_EVERY_MS < WAIT_STEP_MS) streamed.prioritiseFrom(position)
        }
        return false
    }

    private companion object {
        const val BACKLOG = 8
        const val CHUNK_BYTES = 64 * 1024
        const val SOCKET_TIMEOUT_MS = 30_000
        const val WAIT_STEP_MS = 100L
        const val PIECE_TIMEOUT_MS = 120_000L

        /**
         * How long the *first* byte of a response may take before the request is refused with a 503.
         *
         * Shorter than the mid-stream piece timeout on purpose: nothing has been committed to the
         * socket yet, so giving up cheaply and letting the caller retry is better than holding the
         * player on a blank screen. Long enough that a cold swarm still usually makes it.
         */
        const val FIRST_BYTE_TIMEOUT_MS = 30_000L
        const val REPRIORITISE_EVERY_MS = 3_000L
        const val PRIORITISE_EVERY_BYTES = 4L * 1024 * 1024
    }
}
