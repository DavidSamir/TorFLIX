package com.torfilx.core.torrent

import android.content.Context
import android.os.Build
import android.os.Process
import com.torfilx.core.common.log.TorfilxLog
import java.io.File
import java.util.zip.ZipFile

/** Selects the same 1.2 JNI API with Android 11 network restrictions backported. */
internal object NativeTorrentLibrary {
    private const val MODERN_LIBRARY = "libtorrent4j-android11.so"
    private var configured = false
    var variant: String = "legacy"
        private set

    @Synchronized
    fun configure(context: Context) {
        if (configured) return
        if (Build.VERSION.SDK_INT >= 30) {
            val info = context.applicationInfo
            val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
            val path = modernLibraryPath(
                info.nativeLibraryDir?.let(::File),
                listOf(info.sourceDir) + info.splitSourceDirs.orEmpty(),
                abis.toList(),
            ) ?: error("Android 11 torrent compatibility library is missing from this APK")
            // libtorrent4j reads this property before its first JNI call. Android's
            // linker can load an uncompressed, aligned library directly from an APK.
            System.setProperty("libtorrent4j.jni.path", path)
            variant = "android11"
        }
        configured = true
        TorfilxLog.i("Torrent", "Native torrent engine: $variant (API ${Build.VERSION.SDK_INT})")
    }

    internal fun modernLibraryPath(extractedDir: File?, apks: List<String>, abis: List<String>): String? {
        extractedDir?.let { File(it, MODERN_LIBRARY) }?.takeIf { it.isFile }?.let { return it.absolutePath }
        for (abi in abis) {
            val entry = "lib/$abi/$MODERN_LIBRARY"
            for (apk in apks) {
                val present = ZipFile(apk).use { it.getEntry(entry) != null }
                if (present) return "$apk!/$entry"
            }
        }
        return null
    }
}
