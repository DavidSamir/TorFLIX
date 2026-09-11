package com.torfilx.core.catalogue.crypto

import com.google.common.truth.Truth.assertThat
import com.torfilx.core.catalogue.Hex
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the pure-Java Ed25519 against the test vectors published in RFC 8032, section 7.1.
 *
 * Every conforming implementation reproduces these, libtorrent's included, which is what lets a
 * signature made on the publisher's machine verify on the television.
 */
class Ed25519VerifierTest {

    private class Vector(seed: String, publicKey: String, message: String, signature: String) {
        val seed: ByteArray = Hex.decode(seed)
        val publicKey: ByteArray = Hex.decode(publicKey)
        val message: ByteArray = Hex.decode(message)
        val signature: ByteArray = Hex.decode(signature)
    }

    private val rfc8032 = listOf(
        Vector(
            seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
            publicKey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            message = "",
            signature = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        ),
        Vector(
            seed = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
            publicKey = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
            message = "72",
            signature = "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        ),
        Vector(
            seed = "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
            publicKey = "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
            message = "af82",
            signature = "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
        ),
    )

    @Test
    fun `public keys derived from seeds match RFC 8032`() {
        rfc8032.forEach { vector ->
            assertThat(Ed25519Keys.publicKeyOf(vector.seed)).isEqualTo(vector.publicKey)
        }
    }

    @Test
    fun `signatures match RFC 8032 byte for byte`() {
        rfc8032.forEach { vector ->
            assertThat(Ed25519Keys.sign(vector.seed, vector.message)).isEqualTo(vector.signature)
        }
    }

    @Test
    fun `RFC 8032 signatures verify against their public keys`() {
        rfc8032.forEach { vector ->
            assertThat(Ed25519Verifier(listOf(vector.publicKey)).verify(vector.message, vector.signature)).isTrue()
        }
    }

    @Test
    fun `a changed message does not verify`() {
        val vector = rfc8032[2]
        val tampered = vector.message.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThat(Ed25519Verifier(listOf(vector.publicKey)).verify(tampered, vector.signature)).isFalse()
    }

    @Test
    fun `a changed signature does not verify`() {
        val vector = rfc8032[1]
        val tampered = vector.signature.copyOf().also { it[10] = (it[10].toInt() xor 0x40).toByte() }
        assertThat(Ed25519Verifier(listOf(vector.publicKey)).verify(vector.message, tampered)).isFalse()
    }

    @Test
    fun `a signature from an untrusted key does not verify`() {
        val trustsOnlyTheFirst = Ed25519Verifier(listOf(rfc8032[0].publicKey))
        assertThat(trustsOnlyTheFirst.verify(rfc8032[1].message, rfc8032[1].signature)).isFalse()
    }

    @Test
    fun `the trusted key that signed is identified among several`() {
        val verifier = Ed25519Verifier(rfc8032.map { it.publicKey })
        assertThat(verifier.keyCount).isEqualTo(3)
        assertThat(verifier.trustedKeyIndex(rfc8032[1].message, rfc8032[1].signature)).isEqualTo(1)
        assertThat(verifier.trustedKeyIndex(rfc8032[2].message, rfc8032[1].signature)).isEqualTo(-1)
    }

    @Test
    fun `a signature of the wrong length is refused without throwing`() {
        val verifier = Ed25519Verifier(listOf(rfc8032[1].publicKey))
        assertThat(verifier.verify(rfc8032[1].message, rfc8032[1].signature.copyOf(63))).isFalse()
        assertThat(verifier.verify(rfc8032[1].message, ByteArray(0))).isFalse()
    }

    @Test
    fun `a verifier with no keys trusts nothing`() {
        val verifier = Ed25519Verifier(emptyList())
        assertThat(verifier.keyCount).isEqualTo(0)
        assertThat(verifier.verify(rfc8032[1].message, rfc8032[1].signature)).isFalse()
    }

    @Test
    fun `keys and seeds of the wrong length are refused`() {
        assertThrows(IllegalArgumentException::class.java) { Ed25519Verifier(listOf(ByteArray(31))) }
        assertThrows(IllegalArgumentException::class.java) { Ed25519Keys.publicKeyOf(ByteArray(33)) }
        assertThrows(IllegalArgumentException::class.java) { Ed25519Keys.sign(ByteArray(16), byteArrayOf(1)) }
    }
}
