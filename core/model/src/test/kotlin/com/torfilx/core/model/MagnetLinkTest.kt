package com.torfilx.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MagnetLinkTest {

    @Test
    fun `valid hex magnet yields a lowercase info hash`() {
        val magnet = "magnet:?xt=urn:btih:${HASH.uppercase()}&dn=The+Kid&tr=udp%3A%2F%2Ftracker"
        assertThat(MagnetLink.infoHashOf(magnet)).isEqualTo(HASH)
        assertThat(MagnetLink.isValid(magnet)).isTrue()
    }

    @Test
    fun `display name is url decoded`() {
        val magnet = "magnet:?xt=urn:btih:$HASH&dn=The+Kid+%281921%29"
        assertThat(MagnetLink.displayName(magnet)).isEqualTo("The Kid (1921)")
    }

    @Test
    fun `base32 info hashes are accepted`() {
        val base32 = "A2BCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val magnet = "magnet:?xt=urn:btih:${base32.take(32)}"
        assertThat(MagnetLink.isValid(magnet)).isTrue()
    }

    @Test
    fun `placeholder and malformed magnets are rejected rather than half-accepted`() {
        // This is the exact shape of a broken catalogue entry: the hash slot holds junk.
        val placeholder = "magnet:?xt=urn:btih:%29+%5B720p%5D+%=udp%3A%2Fannounce&tr=udp%3A%2F%2Ftracker"
        assertThat(MagnetLink.isValid(placeholder)).isFalse()

        assertThat(MagnetLink.isValid("")).isFalse()
        assertThat(MagnetLink.isValid("http://example.com/file.torrent")).isFalse()
        assertThat(MagnetLink.isValid("magnet:?dn=No+Hash")).isFalse()
        assertThat(MagnetLink.isValid("magnet:?xt=urn:btih:tooshort")).isFalse()
        assertThat(MagnetLink.isValid("magnet:?xt=urn:sha1:$HASH")).isFalse()
    }

    private companion object {
        const val HASH = "0697bc07ebc5914085c2a3bce646509086bf6265"
    }
}
