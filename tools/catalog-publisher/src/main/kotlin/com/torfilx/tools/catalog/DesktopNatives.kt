package com.torfilx.tools.catalog

import java.io.File

/**
 * Points libtorrent4j at its desktop native library when nobody else has.
 *
 * Under Gradle the build passes `-Dlibtorrent4j.jni.path`. An installed copy of the tool
 * (`installDist`) is started by a plain script that does not, so the library is copied out of the
 * libtorrent4j desktop jar on the classpath into a per-version temporary directory instead. This must
 * run before the first libtorrent class is touched.
 */
object DesktopNatives {

    private const val PROPERTY = "libtorrent4j.jni.path"

    /** Must match `libtorrent4j` in gradle/libs.versions.toml; a test fails if the jar disagrees. */
    const val LIBTORRENT_VERSION = "1.2.3.0"

    /** The library file name libtorrent4j publishes for the current operating system. */
    fun libraryName(osName: String = System.getProperty("os.name").orEmpty()): String {
        val os = osName.lowercase()
        return when {
            "win" in os -> "libtorrent4j-$LIBTORRENT_VERSION.dll"
            "mac" in os || "darwin" in os -> "libtorrent4j-$LIBTORRENT_VERSION.dylib"
            else -> "libtorrent4j-$LIBTORRENT_VERSION.so"
        }
    }

    /** The classpath resource holding the 64-bit library for this operating system. */
    fun resourcePath(): String = "lib/x86_64/${libraryName()}"

    fun prepare() {
        if (!System.getProperty(PROPERTY).isNullOrBlank()) return
        val resource = DesktopNatives::class.java.classLoader.getResource(resourcePath()) ?: return
        val directory = File(System.getProperty("java.io.tmpdir"), "torfilx-catalog-publisher/$LIBTORRENT_VERSION")
        val target = File(directory, libraryName())
        if (!target.isFile) {
            directory.mkdirs()
            // Written beside the target and renamed, so two tools starting at once never load half a file.
            val partial = File.createTempFile(libraryName(), ".part", directory)
            resource.openStream().use { input -> partial.outputStream().use { output -> input.copyTo(output) } }
            if (!partial.renameTo(target)) partial.delete()
        }
        System.setProperty(PROPERTY, target.absolutePath)
    }
}
