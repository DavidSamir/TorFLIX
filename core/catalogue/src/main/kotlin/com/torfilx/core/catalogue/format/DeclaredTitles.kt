package com.torfilx.core.catalogue.format

/**
 * Counts `"title"` keys in raw catalogue bytes.
 *
 * Deliberately not JSON-aware: the point is a number the parser cannot influence, so that "the parser
 * stopped early" is detectable. Scans bytes rather than decoding to a string, which avoids a second
 * multi-megabyte allocation on a small heap.
 */
fun countDeclaredTitles(bytes: ByteArray): Int {
    val needle = "\"title\"".encodeToByteArray()
    var count = 0
    var index = 0
    outer@ while (index <= bytes.size - needle.size) {
        for (offset in needle.indices) {
            if (bytes[index + offset] != needle[offset]) {
                index++
                continue@outer
            }
        }
        count++
        index += needle.size
    }
    return count
}
