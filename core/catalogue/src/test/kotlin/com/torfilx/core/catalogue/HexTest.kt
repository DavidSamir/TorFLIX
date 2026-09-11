package com.torfilx.core.catalogue

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HexTest {

    @Test
    fun `every byte value survives a round trip as lower-case hex`() {
        val bytes = ByteArray(256) { it.toByte() }
        val hex = Hex.encode(bytes)

        assertThat(hex).hasLength(512)
        assertThat(hex).isEqualTo(hex.lowercase())
        assertThat(Hex.decode(hex)).isEqualTo(bytes)
    }

    @Test
    fun `upper case and surrounding whitespace still decode`() {
        assertThat(Hex.decodeOrNull("  0A0b\n")).isEqualTo(byteArrayOf(0x0a, 0x0b))
    }

    @Test
    fun `malformed input is refused rather than half decoded`() {
        assertThat(Hex.decodeOrNull("abc")).isNull()
        assertThat(Hex.decodeOrNull("zz")).isNull()
        assertThat(Hex.decodeOrNull("0g")).isNull()
        assertThat(Hex.decodeOrNull("0 1")).isNull()
        // Arabic-Indic digit three: a digit to Character.digit, and not hex to a key parser.
        assertThat(Hex.decodeOrNull("0٣")).isNull()
    }

    @Test
    fun `empty input is an empty array`() {
        assertThat(Hex.decodeOrNull("")).isEqualTo(ByteArray(0))
    }
}
