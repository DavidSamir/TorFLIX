package com.torfilx.core.data.catalog

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Parses the shipped catalogue through a stream that behaves like Android's, not like a desktop file.
 *
 * `CatalogFullLoadTest` reads the asset with a plain `FileInputStream`, which on a JVM hands back a
 * full buffer on every read. `AssetManager.open()` does not: it inflates a compressed entry in
 * chunks and `read(buf, off, len)` is free to return **fewer bytes than asked for**, which is legal
 * for any `InputStream` and is exactly what a compressed 2 MB asset does on a Fire TV.
 *
 * That difference matters because of how the parser fails: `parseCatalogStream` deliberately keeps
 * everything decoded before an error and stops quietly, so a reader that mistook a short read for
 * the end of input would produce a **partial catalogue** rather than a crash — a few dozen titles
 * out of 2000, with nothing anywhere to say so. That is indistinguishable from the reported symptom.
 *
 * These pin that the parser survives arbitrarily chopped-up input.
 */
class CatalogStreamChunkingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }

    /** Full size, so a short read has two thousand titles' worth of chances to truncate it. */
    private val catalogFile = FullSizeCatalogue.FILE

    /** An InputStream that never returns more than [chunk] bytes from a single read. */
    private class ChokedStream(private val data: ByteArray, private val chunk: Int) : InputStream() {
        private var position = 0

        override fun read(): Int =
            if (position >= data.size) -1 else data[position++].toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= data.size) return -1
            val count = minOf(chunk, len, data.size - position)
            System.arraycopy(data, position, b, off, count)
            position += count
            return count
        }

        override fun available(): Int = (data.size - position).coerceAtLeast(0)
    }

    private fun expectedCount(raw: String) = Regex("\"title\"\\s*:").findAll(raw).count()

    @Test
    fun `the whole catalogue survives a stream that returns one byte at a time`() {
        // The most hostile case there is. Slow, but it is the definitive answer to "does a short
        // read truncate the library".
        val raw = catalogFile.readText()
        val parsed = parseCatalogStream(ChokedStream(raw.toByteArray(), chunk = 1), json)
        assertThat(parsed).hasSize(expectedCount(raw))
    }

    @Test
    fun `the whole catalogue survives realistic inflater-sized chunks`() {
        val raw = catalogFile.readText()
        val bytes = raw.toByteArray()
        // Sizes around what a compressed asset actually hands back, including the 64 KB and 8 KB
        // boundaries that buffered readers use.
        for (chunk in listOf(512, 4096, 8192, 16384, 65536)) {
            val parsed = parseCatalogStream(ChokedStream(bytes, chunk), json)
            assertThat(parsed.size).isEqualTo(expectedCount(raw))
        }
    }

    @Test
    fun `a stream that ends early keeps everything before the cut and nothing after`() {
        // The resilience path doing its job on genuinely truncated input: this is the behaviour that
        // turns a read failure into a quietly short library, so it is worth pinning explicitly.
        val bytes = catalogFile.readText().toByteArray()
        val half = parseCatalogStream(ChokedStream(bytes.copyOf(bytes.size / 2), chunk = 8192), json)
        assertThat(half).isNotEmpty()
        assertThat(half.size).isLessThan(parseCatalogStream(ChokedStream(bytes, 8192), json).size)
    }
}
