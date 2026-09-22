package com.torfilx.core.torrent

/**
 * How many title torrents the session keeps, and which go first.
 *
 * With "keep seeding after playback" on, every title watched stays in the session. The storage budget
 * bounds the disk they use, but not their number, and an evening of episodes is a dozen torrents: each
 * one is peer connections, tracker announces and a handle for the status poll to walk on a stick with
 * very little memory or CPU to spare. Beyond [MAX_TORRENTS], the ones touched longest ago go.
 *
 * Nothing streaming is ever chosen — not the title playing, and not the next episode being fetched
 * during a countdown — even if that leaves the session over the cap for a while.
 */
internal object HandleCap {

    const val MAX_TORRENTS = 8

    data class Entry(val infoHash: String, val lastTouchedMs: Long, val isStreaming: Boolean)

    /** The info hashes to remove, oldest-touched first; empty when the session is within the cap. */
    fun surplus(entries: List<Entry>, max: Int = MAX_TORRENTS): List<String> {
        val over = entries.size - max
        if (over <= 0) return emptyList()
        return entries.asSequence()
            .filterNot { it.isStreaming }
            .sortedBy { it.lastTouchedMs }
            .take(over)
            .map { it.infoHash }
            .toList()
    }
}
