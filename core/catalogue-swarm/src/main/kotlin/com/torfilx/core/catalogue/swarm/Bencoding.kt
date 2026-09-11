package com.torfilx.core.catalogue.swarm

import org.libtorrent4j.Entry
import org.libtorrent4j.Vectors

/**
 * Conversions between the catalogue's plain maps and libtorrent's bencoded entries.
 *
 * Only the shapes the DHT pointer uses are supported, integers and strings in a flat dictionary, so
 * anything unexpected in a value read from the DHT is simply not a pointer.
 */
internal object Bencoding {

    /** Builds a bencodable dictionary. Values must be [Long], [Int] or [String]. */
    fun entryOf(values: Map<String, Any>): Entry {
        val converted = LinkedHashMap<String, Any>(values.size)
        values.forEach { (key, value) ->
            converted[key] = when (value) {
                is Long -> Entry(value)
                is Int -> Entry(value.toLong())
                is String -> Entry(value)
                else -> throw IllegalArgumentException(
                    "A DHT value holds only integers and strings, not ${value::class.java.simpleName}",
                )
            }
        }
        return Entry.fromMap(converted)
    }

    /**
     * Reads a bencoded dictionary of integers and strings; null when [entry] is not a dictionary.
     *
     * A value of any other type (a list, a nested dictionary) reads as null for its key rather than
     * failing, so a later pointer format with extra fields still reads in an older app.
     */
    fun dictionary(entry: org.libtorrent4j.swig.entry): Map<String, Any?>? {
        if (entry.type() != org.libtorrent4j.swig.entry.data_type.dictionary_t) return null
        val dict = entry.dict()
        val values = Vectors.string_vector2list(dict.keys()).associateWith { key ->
            val value = dict.get(key)
            when (value.type()) {
                org.libtorrent4j.swig.entry.data_type.int_t -> value.integer()
                org.libtorrent4j.swig.entry.data_type.string_t -> value.string()
                else -> null
            }
        }
        // The dictionary view and its values point into entry's native memory.
        NativeReachability.fence(entry)
        return values
    }
}
