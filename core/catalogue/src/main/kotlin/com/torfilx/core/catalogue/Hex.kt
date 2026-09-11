package com.torfilx.core.catalogue

/**
 * Lower-case hexadecimal, the only binary encoding the catalogue format uses.
 *
 * Hex rather than Base64 because `java.util.Base64` needs API 26 and the app runs on API 22, and
 * because a public key or a signature in hex can be compared by eye in a log line or a build file.
 */
object Hex {
    private const val DIGITS = "0123456789abcdef"
    private const val PREVIEW = 16

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xFF
            out[index * 2] = DIGITS[value ushr 4]
            out[index * 2 + 1] = DIGITS[value and 0x0F]
        }
        return String(out)
    }

    /** Decodes hex in either case, ignoring surrounding whitespace; null for anything else. */
    fun decodeOrNull(text: String): ByteArray? {
        val hex = text.trim()
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (index in out.indices) {
            val high = nibble(hex[index * 2])
            val low = nibble(hex[index * 2 + 1])
            if (high < 0 || low < 0) return null
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }

    fun decode(text: String): ByteArray =
        requireNotNull(decodeOrNull(text)) { "Not hexadecimal: \"${text.trim().take(PREVIEW)}\"" }

    // ASCII only. Character.digit would also accept non-Latin digits, which have no place in a key.
    private fun nibble(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> -1
    }
}
