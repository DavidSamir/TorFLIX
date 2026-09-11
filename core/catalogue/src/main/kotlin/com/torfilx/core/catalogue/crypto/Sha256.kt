package com.torfilx.core.catalogue.crypto

import com.torfilx.core.catalogue.Hex
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** SHA-256 as lower-case hex. Files are streamed, so hashing never holds a release in memory. */
object Sha256 {
    private const val BUFFER_BYTES = 64 * 1024

    fun hex(bytes: ByteArray): String = Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return Hex.encode(digest.digest())
    }
}
