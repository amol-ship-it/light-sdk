package com.amolpurohit.tesla.vcp

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Low-level Vehicle Command Protocol (VCP) crypto primitives for the Fleet
 * API HMAC-SHA256 signing path. See scripts/tesla/vcp-fixtures/README.md
 * for the authoritative protocol spec these are verified against.
 *
 * Fleet API commands are signed with HMAC-SHA256, NOT AES-GCM (that scheme
 * belongs to the BLE transport). AES-GCM is exposed here only as an
 * isolated, independently-tested primitive for future BLE use; it plays no
 * part in Fleet API command signing (Task 23).
 */
object VcpCrypto {

    /** Label for deriving the command-auth HMAC subkey from the session key. */
    const val LABEL_AUTHENTICATED_COMMAND = "authenticated command"

    /** Label for deriving the session-info-verification HMAC subkey from the session key. */
    const val LABEL_SESSION_INFO = "session info"

    private const val UNCOMPRESSED_POINT_PREFIX: Byte = 0x04
    private const val COORDINATE_SIZE = 32

    /**
     * ECDH(clientPrivate, vehiclePublic).x as a 32-byte big-endian value.
     *
     * clientPrivatePkcs8 is a PKCS#8-encoded EC private key. vehiclePublicUncompressed
     * is an uncompressed P-256 point: 0x04 || X(32) || Y(32) (65 bytes).
     */
    fun ecdhSharedSecretX(clientPrivatePkcs8: ByteArray, vehiclePublicUncompressed: ByteArray): ByteArray {
        val keyFactory = KeyFactory.getInstance("EC")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(clientPrivatePkcs8))

        val curveParams = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)

        val publicKey = keyFactory.generatePublic(
            ECPublicKeySpec(decodeUncompressedPoint(vehiclePublicUncompressed), curveParams)
        )

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(publicKey, true)
        return keyAgreement.generateSecret()
    }

    /** Parses an uncompressed EC point (0x04 || X(32) || Y(32)) into an ECPoint. */
    private fun decodeUncompressedPoint(uncompressed: ByteArray): ECPoint {
        require(uncompressed.size == 1 + 2 * COORDINATE_SIZE) {
            "expected a 65-byte uncompressed EC point, got ${uncompressed.size} bytes"
        }
        require(uncompressed[0] == UNCOMPRESSED_POINT_PREFIX) {
            "expected uncompressed point prefix 0x04, got 0x%02x".format(uncompressed[0])
        }
        val x = BigInteger(1, uncompressed.copyOfRange(1, 1 + COORDINATE_SIZE))
        val y = BigInteger(1, uncompressed.copyOfRange(1 + COORDINATE_SIZE, 1 + 2 * COORDINATE_SIZE))
        return ECPoint(x, y)
    }

    /** K = SHA1(sharedX)[:16]. */
    fun sessionKey(sharedX: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1").digest(sharedX)
        return digest.copyOf(16)
    }

    /** Per-purpose subkey: HMAC-SHA256(sessionKey, label), label as ASCII bytes. */
    fun subkey(sessionKey: ByteArray, label: String): ByteArray {
        return hmacSha256(sessionKey, label.toByteArray(Charsets.US_ASCII))
    }

    /** HMAC-SHA256(key, data). */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /**
     * AES-GCM encrypt. Returns (ciphertext, tag) with the 16-byte GCM tag
     * split off from the end of Java's combined Cipher output.
     *
     * Isolated primitive only — not used in the Fleet API HMAC signing path.
     * Reserved for future BLE transport use.
     */
    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val combined = cipher.doFinal(plaintext)
        val tagSize = 16
        val ciphertext = combined.copyOfRange(0, combined.size - tagSize)
        val tag = combined.copyOfRange(combined.size - tagSize, combined.size)
        return ciphertext to tag
    }

    /**
     * AES-GCM decrypt. Takes the ciphertext and tag separately (mirroring
     * aesGcmEncrypt's split output) and recombines them for Cipher.
     */
    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray, tag: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext + tag)
    }
}
