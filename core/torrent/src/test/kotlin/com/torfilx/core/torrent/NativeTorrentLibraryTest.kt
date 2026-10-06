package com.torfilx.core.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativeTorrentLibraryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `loads the native binary directly from an APK without extracted libraries`() {
        val apk = temporary.newFile("base.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            for (abi in listOf("armeabi-v7a", "arm64-v8a")) {
                zip.putNextEntry(ZipEntry("lib/$abi/libtorrent4j-android11.so"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
        }
        assertEquals(
            "${apk.path}!/lib/armeabi-v7a/libtorrent4j-android11.so",
            NativeTorrentLibrary.modernLibraryPath(null, listOf(apk.path), listOf("armeabi-v7a")),
        )
        assertEquals(
            "${apk.path}!/lib/arm64-v8a/libtorrent4j-android11.so",
            NativeTorrentLibrary.modernLibraryPath(null, listOf(apk.path), listOf("arm64-v8a")),
        )
        assertNull(NativeTorrentLibrary.modernLibraryPath(null, listOf(apk.path), listOf("x86")))
    }

    @Test fun `supports devices that extract native libraries during installation`() {
        val directory = temporary.newFolder()
        val library = File(directory, "libtorrent4j-android11.so").apply { writeBytes(byteArrayOf(1)) }
        assertEquals(library.path, NativeTorrentLibrary.modernLibraryPath(directory, emptyList(), emptyList()))
    }
}
