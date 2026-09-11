package com.torfilx.core.catalogue.crypto

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest

/**
 * Verifies Ed25519 signatures against a fixed set of trusted public keys.
 *
 * Pure Java (`net.i2p.crypto:eddsa`) rather than libtorrent's native implementation, so verifying a
 * catalogue never depends on the native library having loaded, and every rule is testable on a plain
 * JVM. The signatures are standard RFC 8032 Ed25519, which is also what libtorrent produces; a test
 * with the real native library proves the two agree.
 *
 * More than one key is accepted so the publisher key can be rotated: ship an app that trusts both,
 * move publishing to the new key, then drop the old one.
 */
class Ed25519Verifier(publicKeys: List<ByteArray>) {

    private val keyBytes: List<ByteArray> = publicKeys.map { bytes ->
        require(bytes.size == Ed25519Keys.PUBLIC_KEY_BYTES) {
            "An Ed25519 public key is ${Ed25519Keys.PUBLIC_KEY_BYTES} bytes, got ${bytes.size}"
        }
        bytes.copyOf()
    }

    /**
     * Decoded on first use, not on construction. The first touch of the curve builds its precomputed
     * tables, which is real work on a Fire TV Stick, and the app constructs this object while its
     * dependency graph is being built on the main thread.
     */
    private val keys: List<EdDSAPublicKey> by lazy {
        keyBytes.map { EdDSAPublicKey(EdDSAPublicKeySpec(it, Ed25519Keys.curve)) }
    }

    val keyCount: Int get() = keyBytes.size

    /** True when [signature] over [message] was made by any trusted key. */
    fun verify(message: ByteArray, signature: ByteArray): Boolean = trustedKeyIndex(message, signature) >= 0

    /** Which trusted key made [signature], or -1. Useful in logs during a key rotation. */
    fun trustedKeyIndex(message: ByteArray, signature: ByteArray): Int {
        if (signature.size != Ed25519Keys.SIGNATURE_BYTES) return -1
        return keys.indexOfFirst { key -> verifyWith(key, message, signature) }
    }

    private fun verifyWith(key: EdDSAPublicKey, message: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            val engine = EdDSAEngine(MessageDigest.getInstance(Ed25519Keys.curve.hashAlgorithm))
            engine.initVerify(key)
            engine.verifyOneShot(message, signature)
        }.getOrDefault(false)
}

/** Key and signing helpers. Signing only ever happens in the publisher tool and in tests. */
object Ed25519Keys {
    const val SEED_BYTES = 32
    const val PUBLIC_KEY_BYTES = 32
    const val SIGNATURE_BYTES = 64

    internal val curve: EdDSAParameterSpec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)

    /** The public key for a 32-byte private seed. */
    fun publicKeyOf(seed: ByteArray): ByteArray = privateKey(seed).abyte

    /** A deterministic RFC 8032 signature of [message] with the key derived from [seed]. */
    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        val engine = EdDSAEngine(MessageDigest.getInstance(curve.hashAlgorithm))
        engine.initSign(privateKey(seed))
        return engine.signOneShot(message)
    }

    private fun privateKey(seed: ByteArray): EdDSAPrivateKey {
        require(seed.size == SEED_BYTES) { "An Ed25519 seed is $SEED_BYTES bytes, got ${seed.size}" }
        return EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, curve))
    }
}
