package com.torfilx.core.catalogue.swarm

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.Hex
import com.torfilx.core.catalogue.crypto.Ed25519Keys
import com.torfilx.core.catalogue.crypto.Ed25519Verifier
import com.torfilx.core.catalogue.testing.CatalogueTestKeys
import org.junit.Before
import org.junit.Test
import org.libtorrent4j.Ed25519

/**
 * libtorrent's native Ed25519 and the pure-Java one the app verifies with must be the same algorithm.
 *
 * The publisher signs the DHT pointer with libtorrent and the manifest with the pure-Java code, from
 * one seed, and the app trusts one public key for both. These tests are what make that sound.
 */
class Ed25519InteropTest {

    @Before
    fun nativeLibrary() = NativeSupport.require()

    private val seed = Hex.decode("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
    private val publicKey = Hex.decode("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
    private val message = Hex.decode("72")
    private val signature = Hex.decode(
        "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
            "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
    )

    @Test
    fun `libtorrent derives the RFC 8032 public key from a seed, as the pure-Java code does`() {
        val pair = Ed25519.createKeypair(seed)
        assertThat(pair.first).isEqualTo(publicKey)
        assertThat(pair.first).isEqualTo(Ed25519Keys.publicKeyOf(seed))
        assertThat(pair.second).hasLength(Ed25519.SECRET_KEY_SIZE)
    }

    @Test
    fun `libtorrent signs exactly what the pure-Java code signs`() {
        val pair = Ed25519.createKeypair(seed)
        val native = Ed25519.sign(message, pair.first, pair.second)
        assertThat(native).isEqualTo(signature)
        assertThat(native).isEqualTo(Ed25519Keys.sign(seed, message))
    }

    @Test
    fun `each implementation verifies the other's signatures`() {
        val pair = Ed25519.createKeypair(CatalogueTestKeys.SEED)
        val manifest = "{\"catalogVersion\": 7}".encodeToByteArray()

        val nativeSignature = Ed25519.sign(manifest, pair.first, pair.second)
        assertThat(Ed25519Verifier(listOf(CatalogueTestKeys.PUBLIC_KEY)).verify(manifest, nativeSignature)).isTrue()

        val javaSignature = Ed25519Keys.sign(CatalogueTestKeys.SEED, manifest)
        assertThat(Ed25519.verify(javaSignature, manifest, pair.first)).isTrue()
    }

    @Test
    fun `the key a publisher puts pointers under is the key the app trusts`() {
        assertThat(Ed25519.createKeypair(CatalogueTestKeys.SEED).first).isEqualTo(CatalogueTestKeys.PUBLIC_KEY)
    }
}
