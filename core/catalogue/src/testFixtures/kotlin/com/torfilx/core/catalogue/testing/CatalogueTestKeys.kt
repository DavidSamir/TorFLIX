package com.torfilx.core.catalogue.testing

import com.torfilx.core.catalogue.Hex
import com.torfilx.core.catalogue.crypto.Ed25519Keys
import com.torfilx.core.catalogue.crypto.Ed25519Verifier
import com.torfilx.core.catalogue.release.CatalogueReleaseVerifier

/**
 * Publisher keys for tests only.
 *
 * No shipped build trusts these. The app's trusted key comes from its build file, and this source set
 * is invisible to every `src/main`.
 */
object CatalogueTestKeys {

    /** The trusted test publisher. The seed is 32 readable ASCII bytes, so it is obviously not real. */
    val SEED: ByteArray = "test seed-torfilx-cat-publisher!".encodeToByteArray()
    val PUBLIC_KEY: ByteArray = Ed25519Keys.publicKeyOf(SEED)
    val PUBLIC_KEY_HEX: String = Hex.encode(PUBLIC_KEY)

    /** A second publisher nobody trusts: what an impersonator would sign with. */
    val OTHER_SEED: ByteArray = "some other publisher, untrusted!".encodeToByteArray()
    val OTHER_PUBLIC_KEY: ByteArray = Ed25519Keys.publicKeyOf(OTHER_SEED)

    /** A verifier that trusts only [PUBLIC_KEY]. */
    fun verifier(): CatalogueReleaseVerifier = CatalogueReleaseVerifier(Ed25519Verifier(listOf(PUBLIC_KEY)))
}
