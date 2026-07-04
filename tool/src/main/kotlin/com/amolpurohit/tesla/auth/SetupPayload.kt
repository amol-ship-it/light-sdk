package com.amolpurohit.tesla.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.zip.Inflater

/**
 * Decoded contents of the Tesla setup QR code(s).
 *
 * Encoded on the wire as: JSON -> raw deflate (nowrap) -> base64url (no padding).
 * Oversized payloads are split across multiple QR codes, each prefixed with
 * `LTP/<i>/<n>/<data>` (1-based index `i` of `n` total parts), scannable in any order.
 */
// NEVER log raw — carries refresh_token/private_key
@Serializable
data class SetupPayload(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String? = null,
    @SerialName("region") val region: String,
    @SerialName("private_key") val privateKey: String,
    @SerialName("domain") val domain: String? = null,
    @SerialName("v") val version: Int = 1,
) {
    /** Redacted: never includes refreshToken/privateKey/clientSecret values. */
    override fun toString(): String =
        "SetupPayload(clientId=$clientId, region=$region, domain=$domain, " +
            "hasSecret=${clientSecret != null}, version=$version)"

    sealed interface ScanResult

    data class Complete(val payload: SetupPayload) : ScanResult

    data class NeedMore(val have: Set<Int>, val of: Int) : ScanResult

    data class Invalid(val reason: String) : ScanResult

    private class InflatedTooLargeException : Exception("inflated payload exceeds cap")

    companion object {
        // Per-scan cap only; does NOT bound total multi-part size — MAX_INFLATED_LENGTH is the real backstop.
        private const val MAX_SCAN_LENGTH = 8192
        // Generous ceiling on decompressed output (real payload is ~2 KB); guards against deflate bombs.
        private const val MAX_INFLATED_LENGTH = 64 * 1024
        private const val SUPPORTED_VERSION = 1
        private val partRegex = Regex("""^LTP/(\d+)/(\d+)/(.*)$""", RegexOption.DOT_MATCHES_ALL)
        private val json = Json { ignoreUnknownKeys = true }

        fun fromScans(scans: List<String>): ScanResult {
            if (scans.isEmpty()) return Invalid("no scans provided")
            for (scan in scans) {
                if (scan.length > MAX_SCAN_LENGTH) return Invalid("scan exceeds max length")
            }

            val parts = mutableMapOf<Int, String>()
            var totalParts: Int? = null
            val barePayloads = mutableListOf<String>()

            for (scan in scans) {
                val match = partRegex.matchEntire(scan)
                if (match == null) {
                    barePayloads += scan
                    continue
                }
                val index = match.groupValues[1].toIntOrNull()
                val total = match.groupValues[2].toIntOrNull()
                val data = match.groupValues[3]
                if (index == null || total == null || index < 1 || total < 1 || index > total) {
                    return Invalid("malformed multi-part header")
                }
                if (totalParts == null) {
                    totalParts = total
                } else if (totalParts != total) {
                    return Invalid("inconsistent part counts")
                }
                val existing = parts[index]
                if (existing != null && existing != data) {
                    return Invalid("duplicate part index with different content")
                }
                parts[index] = data
            }

            if (totalParts != null) {
                val of = totalParts
                if (parts.size < of) {
                    return NeedMore(have = parts.keys.toSet(), of = of)
                }
                val assembled = StringBuilder()
                for (i in 1..of) {
                    val data = parts[i] ?: return Invalid("missing part $i")
                    assembled.append(data)
                }
                return decode(assembled.toString())
            }

            // No multi-part parts seen; treat as bare single-payload scans.
            if (barePayloads.size != 1) {
                return Invalid("expected exactly one bare payload")
            }
            return decode(barePayloads[0])
        }

        private fun decode(encoded: String): ScanResult {
            // The two e.message interpolations below are safe: base64/inflate
            // exceptions carry format-level text only ("Illegal base64 character",
            // "invalid block type"), never decoded payload content.
            val deflated = try {
                java.util.Base64.getUrlDecoder().decode(encoded)
            } catch (e: IllegalArgumentException) {
                return Invalid("invalid base64: ${e.message}")
            }

            val inflated = try {
                inflate(deflated)
            } catch (e: InflatedTooLargeException) {
                return Invalid("payload too large")
            } catch (e: Exception) {
                return Invalid("inflate failed: ${e.message}")
            }

            // Static reasons from here on: exception messages for post-inflate stages
            // can echo decoded input fragments, which may contain secrets.
            val jsonString = try {
                inflated.toString(Charsets.UTF_8)
            } catch (e: Exception) {
                return Invalid("malformed payload")
            }

            val payload = try {
                json.decodeFromString<SetupPayload>(jsonString)
            } catch (e: Exception) {
                return Invalid("malformed json")
            }

            if (payload.version != SUPPORTED_VERSION) {
                return Invalid("unsupported version: ${payload.version}")
            }
            if (payload.refreshToken.isBlank() || payload.clientId.isBlank() ||
                payload.region.isBlank() || payload.privateKey.isBlank()
            ) {
                return Invalid("missing required field")
            }

            return Complete(payload)
        }

        private fun inflate(data: ByteArray): ByteArray {
            val inflater = Inflater(/* nowrap = */ true)
            inflater.setInput(data)
            val out = java.io.ByteArrayOutputStream(data.size * 4 + 64)
            val buf = ByteArray(4096)
            try {
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) {
                            throw IllegalStateException("truncated deflate stream")
                        }
                    }
                    out.write(buf, 0, n)
                    if (out.size() > MAX_INFLATED_LENGTH) {
                        throw InflatedTooLargeException()
                    }
                }
            } finally {
                inflater.end()
            }
            return out.toByteArray()
        }
    }
}
