package com.torfilx.core.catalogue.format

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class CataloguePointerTest {

    private val hash = "0697bc07ebc5914085c2a3bce646509086bf6265"

    @Test
    fun `a pointer survives the round trip through its DHT value`() {
        val pointer = CataloguePointer(CataloguePointer.FORMAT, hash, 42)
        assertThat(CataloguePointer.fromMap(pointer.toMap())).isEqualTo(pointer)
    }

    @Test
    fun `the DHT value holds integers as longs and the info hash as text`() {
        val map = CataloguePointer(CataloguePointer.FORMAT, hash, 42).toMap()
        assertThat(map).containsExactly("cv", 42L, "ih", hash, "v", 1L)
    }

    @Test
    fun `an upper-case info hash read back from the DHT is normalised`() {
        val pointer = CataloguePointer.fromMap(mapOf("v" to 1L, "ih" to hash.uppercase(), "cv" to 3L))
        assertThat(pointer?.infoHash).isEqualTo(hash)
    }

    @Test
    fun `values that are not a well-formed pointer read as nothing`() {
        listOf(
            emptyMap(),
            mapOf("ih" to hash, "cv" to 3L),
            mapOf("v" to 1L, "cv" to 3L),
            mapOf("v" to 1L, "ih" to hash),
            mapOf("v" to 1L, "ih" to hash, "cv" to "3"),
            mapOf("v" to 1L, "ih" to hash, "cv" to 0L),
            mapOf("v" to 1L, "ih" to hash, "cv" to -5L),
            mapOf("v" to 1L, "ih" to hash.take(39), "cv" to 3L),
            mapOf("v" to 1L, "ih" to "z".repeat(40), "cv" to 3L),
            mapOf<String, Any?>("v" to null, "ih" to hash, "cv" to 3L),
        ).forEach { values -> assertThat(CataloguePointer.fromMap(values)).isNull() }
    }

    @Test
    fun `a pointer cannot be built with a bad hash or version`() {
        assertThrows(IllegalArgumentException::class.java) { CataloguePointer(1, hash.uppercase(), 1) }
        assertThrows(IllegalArgumentException::class.java) { CataloguePointer(1, hash, 0) }
    }

    @Test
    fun `the bencoded value stays far below the DHT item size limit`() {
        // BEP 44 limits an item to 1000 bytes. Bencode the largest possible pointer by hand.
        val map = CataloguePointer(CataloguePointer.FORMAT, hash, Long.MAX_VALUE).toMap().toSortedMap()
        val bencoded = buildString {
            append('d')
            map.forEach { (key, value) ->
                append(key.length).append(':').append(key)
                when (value) {
                    is Long -> append('i').append(value).append('e')
                    is String -> append(value.length).append(':').append(value)
                    else -> error("unexpected value type ${value::class}")
                }
            }
            append('e')
        }
        assertThat(bencoded.length).isLessThan(100)
    }
}
