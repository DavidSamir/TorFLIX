package com.torfilx.core.model

/**
 * Parses and validates magnet links before anything touches the network.
 *
 * Lives in the pure model module because two things with no Android in them depend on it: the
 * catalogue id rules shared with the publisher tool, and the torrent engine.
 */
object MagnetLink {

    private val INFO_HASH_HEX = Regex("^[a-fA-F0-9]{40}$")
    private val INFO_HASH_BASE32 = Regex("^[A-Z2-7]{32}$")

    /** The 40-hex (or base32) info hash, or null when the magnet is malformed. */
    fun infoHashOf(magnet: String): String? {
        if (!magnet.startsWith("magnet:?", ignoreCase = true)) return null
        val xt = magnet.removePrefix("magnet:?")
            .split('&')
            .firstOrNull { it.startsWith("xt=urn:btih:", ignoreCase = true) }
            ?: return null
        val hash = xt.substringAfter("urn:btih:", "").trim()
        return when {
            INFO_HASH_HEX.matches(hash) -> hash.lowercase()
            INFO_HASH_BASE32.matches(hash.uppercase()) -> hash.uppercase()
            else -> null
        }
    }

    fun isValid(magnet: String): Boolean = infoHashOf(magnet) != null

    /** Display name from the `dn` parameter, if present. */
    fun displayName(magnet: String): String? = magnet
        .substringAfter("magnet:?", "")
        .split('&')
        .firstOrNull { it.startsWith("dn=") }
        ?.removePrefix("dn=")
        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        ?.takeIf { it.isNotBlank() }
}
