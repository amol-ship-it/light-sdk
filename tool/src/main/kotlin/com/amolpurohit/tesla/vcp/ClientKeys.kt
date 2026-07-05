package com.amolpurohit.tesla.vcp

import java.util.Base64

/**
 * The client's own EC key pair (P-256), as needed by the VCP signing path:
 * [privatePkcs8] for ECDH with the vehicle's public key (session key
 * derivation, [VcpCrypto.ecdhSharedSecretX]), and [publicUncompressed] — the
 * 65-byte `0x04‖X‖Y` point — for the signed envelope's
 * `signature_data.signer_identity.public_key` field
 * ([CommandSigner.sign] via [com.amolpurohit.tesla.vcp.Session.clientPublicKey]).
 */
data class ClientKeys(
    val privatePkcs8: ByteArray,
    val publicUncompressed: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClientKeys) return false
        return privatePkcs8.contentEquals(other.privatePkcs8) &&
            publicUncompressed.contentEquals(other.publicUncompressed)
    }

    override fun hashCode(): Int {
        var result = privatePkcs8.contentHashCode()
        result = 31 * result + publicUncompressed.contentHashCode()
        return result
    }

    companion object {
        /** Test/fixture factory: both fields provided directly (raw bytes). */
        fun of(privatePkcs8: ByteArray, publicUncompressed: ByteArray): ClientKeys =
            ClientKeys(privatePkcs8, publicUncompressed)

        /**
         * Production factory: parses [privateKeyPem], the PEM string carried in
         * `SetupPayload.privateKey`, into PKCS#8 DER, then derives the client's
         * public uncompressed point directly from the DER — no BouncyCastle, no
         * reflection, no EC point multiplication.
         *
         * Accepts either PEM form the setup tooling might hand us:
         * - `-----BEGIN PRIVATE KEY-----` (PKCS#8 — openssl `pkcs8 -topk8` output)
         * - `-----BEGIN EC PRIVATE KEY-----` (SEC1, as `openssl ecparam -genkey` emits
         *   directly) is NOT unwrapped here; callers must supply PKCS#8 PEM.
         *   (The setup script is expected to emit PKCS#8 — see Task 27.)
         *
         * Public point derivation: an openssl-generated EC private key's PKCS#8
         * `PrivateKeyInfo.privateKey` OCTET STRING contains a SEC1 `ECPrivateKey`
         * (RFC 5915 §3): `SEQUENCE { version, privateKey OCTET STRING,
         * [0] parameters OPTIONAL, [1] publicKey BIT STRING OPTIONAL }`. Non-
         * import-restricted openssl output always populates the optional `[1]`
         * publicKey field, so we walk the DER TLV structure (a handful of SEQUENCE/
         * OCTET STRING/context-tag/BIT STRING tags — no ASN.1 library needed) and
         * lift that BIT STRING's content directly: it IS the 65-byte uncompressed
         * point, byte-for-byte (verified against `openssl ec -text` output during
         * investigation for this task). If a key is ever supplied without the
         * optional public key (e.g. re-encoded by a tool that strips it), this
         * throws rather than silently guessing.
         */
        fun fromPem(privateKeyPem: String): ClientKeys {
            val pkcs8 = decodePkcs8Pem(privateKeyPem)
            val publicUncompressed = extractPublicKeyFromPkcs8(pkcs8)
            return ClientKeys(pkcs8, publicUncompressed)
        }

        private fun decodePkcs8Pem(pem: String): ByteArray {
            val body = pem.lineSequence()
                .filterNot { it.startsWith("-----") }
                .joinToString("")
                .trim()
            require(body.isNotEmpty()) { "empty PEM body" }
            return Base64.getMimeDecoder().decode(body)
        }

        // ---- Minimal DER TLV reader: just enough to walk PrivateKeyInfo -> SEC1 ECPrivateKey -> [1] publicKey. ----

        private const val TAG_SEQUENCE = 0x30
        private const val TAG_INTEGER = 0x02
        private const val TAG_OCTET_STRING = 0x04
        private const val TAG_BIT_STRING = 0x03
        private const val TAG_CONTEXT_1 = 0xA1 // SEC1 ECPrivateKey's [1] publicKey (constructed, context class, tag 1)

        /** Returns (length, offset of first content byte). */
        private fun readLength(bytes: ByteArray, pos: Int): Pair<Int, Int> {
            val first = bytes[pos].toInt() and 0xFF
            if (first and 0x80 == 0) return first to (pos + 1)
            val numBytes = first and 0x7F
            require(numBytes in 1..4) { "unsupported DER length encoding" }
            var value = 0
            for (i in 0 until numBytes) {
                value = (value shl 8) or (bytes[pos + 1 + i].toInt() and 0xFF)
            }
            return value to (pos + 1 + numBytes)
        }

        private fun expectTag(bytes: ByteArray, pos: Int, tag: Int, what: String): Int {
            val actual = bytes[pos].toInt() and 0xFF
            require(actual == tag) { "expected $what (tag 0x%02x), got 0x%02x".format(tag, actual) }
            return pos + 1
        }

        /**
         * Walks a PKCS#8 `PrivateKeyInfo` to its inner `privateKey` OCTET STRING
         * (the SEC1 `ECPrivateKey` blob), then walks THAT to find the optional
         * `[1] publicKey BIT STRING` and returns its content (the raw uncompressed
         * point, including the leading unused-bits byte which must be 0 and is
         * dropped).
         */
        private fun extractPublicKeyFromPkcs8(pkcs8: ByteArray): ByteArray {
            var pos = expectTag(pkcs8, 0, TAG_SEQUENCE, "PrivateKeyInfo SEQUENCE")
            var (_, contentPos) = readLength(pkcs8, pos)
            pos = contentPos

            // version INTEGER — skip.
            pos = expectTag(pkcs8, pos, TAG_INTEGER, "version INTEGER")
            var (len, valStart) = readLength(pkcs8, pos)
            pos = valStart + len

            // algorithm AlgorithmIdentifier SEQUENCE — skip.
            pos = expectTag(pkcs8, pos, TAG_SEQUENCE, "AlgorithmIdentifier SEQUENCE")
            readLength(pkcs8, pos).let { (l, v) -> len = l; valStart = v }
            pos = valStart + len

            // privateKey OCTET STRING — this wraps the SEC1 ECPrivateKey.
            pos = expectTag(pkcs8, pos, TAG_OCTET_STRING, "privateKey OCTET STRING")
            readLength(pkcs8, pos).let { (l, v) -> len = l; valStart = v }
            val sec1 = pkcs8.copyOfRange(valStart, valStart + len)

            return extractPublicKeyFromSec1(sec1)
        }

        private fun extractPublicKeyFromSec1(sec1: ByteArray): ByteArray {
            var pos = expectTag(sec1, 0, TAG_SEQUENCE, "ECPrivateKey SEQUENCE")
            val (seqLen, seqContentStart) = readLength(sec1, pos)
            val seqEnd = seqContentStart + seqLen
            pos = seqContentStart

            while (pos < seqEnd) {
                val tag = sec1[pos].toInt() and 0xFF
                pos++
                val (len, valStart) = readLength(sec1, pos)
                if (tag == TAG_CONTEXT_1) {
                    var bp = expectTag(sec1, valStart, TAG_BIT_STRING, "publicKey BIT STRING")
                    val (bitLen, bitValStart) = readLength(sec1, bp)
                    bp = bitValStart
                    val unusedBits = sec1[bp].toInt() and 0xFF
                    require(unusedBits == 0) { "unexpected unused bits in publicKey BIT STRING: $unusedBits" }
                    return sec1.copyOfRange(bp + 1, bp + bitLen)
                }
                pos = valStart + len
            }
            error(
                "SEC1 ECPrivateKey has no optional [1] publicKey field — cannot derive the " +
                    "client public point JDK-only from this key. Add a client_public field to " +
                    "the setup payload instead of hand-rolling EC point multiplication here.",
            )
        }
    }
}
