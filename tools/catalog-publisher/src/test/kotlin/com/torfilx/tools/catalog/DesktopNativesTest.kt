package com.torfilx.tools.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test

class DesktopNativesTest {

    @Test
    fun `library names follow libtorrent4j's naming for each operating system`() {
        assertThat(DesktopNatives.libraryName("Windows 11")).isEqualTo("libtorrent4j-1.2.3.0.dll")
        assertThat(DesktopNatives.libraryName("Linux")).isEqualTo("libtorrent4j-1.2.3.0.so")
        assertThat(DesktopNatives.libraryName("Mac OS X")).isEqualTo("libtorrent4j-1.2.3.0.dylib")
    }

    @Test
    fun `the bundled desktop jar holds the library under the expected name`() {
        // Catches a libtorrent4j upgrade that forgets to update LIBTORRENT_VERSION: an installed tool
        // would otherwise fail to find its native library only when a maintainer runs it.
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        assumeTrue(arch == "amd64" || arch == "x86_64")
        assertThat(DesktopNatives::class.java.classLoader.getResource(DesktopNatives.resourcePath())).isNotNull()
    }
}
