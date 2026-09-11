package com.torfilx.core.catalogue.swarm

import org.libtorrent4j.Entry
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import java.io.File

/**
 * Keeps the DHT routing table between runs.
 *
 * A DHT node that starts from nothing has to find its way in through a handful of public routers,
 * which takes tens of seconds and is what made the first play of every session race an empty DHT.
 * A node that starts from the nodes it knew last time is back in within seconds. That matters twice
 * over for the catalogue: its update check runs soon after the session starts.
 *
 * Only DHT state is saved: node ids and known nodes. Nothing about what was watched or shared.
 */
object DhtStateStore {

    /** A saved routing table is a few kilobytes; anything far larger is not one. */
    private const val MAX_STATE_BYTES = 256L * 1024
    private const val STATE_KEY = "dht state"

    /**
     * Writes the session's DHT state to [file], atomically.
     *
     * @return the bytes written, or null when there was nothing to save or the write failed.
     */
    fun save(session: SessionManager, file: File): Int? = runCatching {
        val bytes = SessionHandle(session.swig()).saveState(SessionHandle.SAVE_DHT_STATE)
        if (bytes == null || nodeCount(bytes) == null) return null
        val directory = file.absoluteFile.parentFile
        if (directory != null && !directory.isDirectory && !directory.mkdirs()) return null
        val temporary = File(directory, file.name + ".tmp")
        temporary.writeBytes(bytes)
        // Rename replaces the target atomically on Android and Linux. Windows refuses to rename over an
        // existing file, so there the old file is removed first.
        if (!temporary.renameTo(file)) {
            file.delete()
            if (!temporary.renameTo(file)) {
                temporary.delete()
                return null
            }
        }
        bytes.size
    }.getOrNull()

    /**
     * Seeds the session's DHT with the state saved in [file].
     *
     * A file that is not a DHT state is deleted rather than handed to the native library.
     *
     * @return true when a state was loaded.
     */
    fun load(session: SessionManager, file: File): Boolean {
        if (!file.isFile) return false
        if (file.length() == 0L || file.length() > MAX_STATE_BYTES) {
            file.delete()
            return false
        }
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return false
        if (nodeCount(bytes) == null) {
            file.delete()
            return false
        }
        return runCatching { SessionHandle(session.swig()).loadState(bytes, SessionHandle.SAVE_DHT_STATE) }.isSuccess
    }

    /** How many nodes [bytes] records, or null when the bytes are not a saved DHT state. */
    fun nodeCount(bytes: ByteArray): Int? = runCatching {
        val root = Entry.bdecode(bytes)
        try {
            countNodes(root)
        } finally {
            // Every entry dictionary() and list() hand out points into root's native memory.
            NativeReachability.fence(root)
        }
    }.getOrNull()

    private fun countNodes(root: Entry): Int? {
        if (root.swig().type() != org.libtorrent4j.swig.entry.data_type.dictionary_t) return null
        val state = root.dictionary()[STATE_KEY] ?: return null
        if (state.swig().type() != org.libtorrent4j.swig.entry.data_type.dictionary_t) return null
        val entries = state.dictionary()
        return listOf("nodes", "nodes6").sumOf { key ->
            val list = entries[key] ?: return@sumOf 0
            if (list.swig().type() == org.libtorrent4j.swig.entry.data_type.list_t) list.list().size else 0
        }
    }
}
