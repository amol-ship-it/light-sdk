package com.amolpurohit.tesla.vcp

import java.io.ByteArrayOutputStream

/**
 * Builds the HMAC-SHA256-signed `RoutableMessage` envelope for a Fleet API
 * command. See `scripts/tesla/vcp-fixtures/README.md` "HMAC command tag" and
 * "RoutableMessage envelope" — the authoritative spec this is verified
 * against (`tool/src/test/resources/vcp/commands.json`).
 *
 * Fleet API signs with HMAC-SHA256, not AES-GCM (that's the BLE transport's
 * scheme) — see the README's "Major correction to the design plan". The
 * command payload travels as plaintext protobuf
 * (`RoutableMessage.protobuf_message_as_bytes`); authenticity is carried
 * entirely by the `HMAC_Personalized_Signature_Data` tag.
 */
object CommandSigner {

    // Signatures.proto:8-21 `enum Tag` (metadata TLV tag numbers).
    private const val TAG_SIGNATURE_TYPE = 0 // signatures.proto:10
    private const val TAG_DOMAIN = 1 // signatures.proto:11
    private const val TAG_PERSONALIZATION = 2 // signatures.proto:12
    private const val TAG_EPOCH = 3 // signatures.proto:13
    private const val TAG_EXPIRES_AT = 4 // signatures.proto:14
    private const val TAG_COUNTER = 5 // signatures.proto:15
    private const val TAG_FLAGS = 7 // signatures.proto:17
    private const val TAG_END = 255 // signatures.proto:20

    // signatures.proto:23-31 `enum SignatureType`.
    private const val SIGNATURE_TYPE_HMAC_PERSONALIZED = 8 // signatures.proto:29

    // universal_message.proto:75-78 `enum Flags`.
    private const val FLAG_ENCRYPT_RESPONSE = 1 // universal_message.proto:77
    private const val FLAGS_VALUE = 1 shl FLAG_ENCRYPT_RESPONSE // always set in these fixtures

    // universal_message.proto:80-101 `message RoutableMessage`.
    private const val TO_DESTINATION = 6 // universal_message.proto:84
    private const val FROM_DESTINATION = 7 // universal_message.proto:85
    private const val PAYLOAD_PROTOBUF_MESSAGE_AS_BYTES = 10 // universal_message.proto:88
    private const val SIGNATURE_DATA = 13 // universal_message.proto:94
    private const val UUID = 51 // universal_message.proto:99
    private const val FLAGS = 52 // universal_message.proto:100
    private const val SIGNED_MESSAGE_STATUS = 12 // universal_message.proto:97

    // universal_message.proto:16-21 `message Destination`.
    private const val DESTINATION_DOMAIN = 1 // universal_message.proto:18
    private const val DESTINATION_ROUTING_ADDRESS = 2 // universal_message.proto:19

    // universal_message.proto:63-67 `message MessageStatus`.
    private const val MESSAGE_STATUS_SIGNED_MESSAGE_FAULT = 2 // universal_message.proto:66
    private const val MESSAGE_FAULT_NONE = 0 // universal_message.proto:32 MESSAGEFAULT_ERROR_NONE

    // signatures.proto:66-75 `message SignatureData`.
    private const val SIGNATURE_DATA_SIGNER_IDENTITY = 1 // signatures.proto:68
    private const val SIGNATURE_DATA_HMAC_PERSONALIZED_DATA = 8 // signatures.proto:72

    // signatures.proto:33-39 `message KeyIdentity`.
    private const val KEY_IDENTITY_PUBLIC_KEY = 1 // signatures.proto:36

    // signatures.proto:59-64 `message HMAC_Personalized_Signature_Data`.
    private const val HMAC_PERSONALIZED_EPOCH = 1 // signatures.proto:60
    private const val HMAC_PERSONALIZED_COUNTER = 2 // signatures.proto:61
    private const val HMAC_PERSONALIZED_EXPIRES_AT = 3 // signatures.proto:62 (fixed32)
    private const val HMAC_PERSONALIZED_TAG = 4 // signatures.proto:63

    /**
     * Builds the metadata TLV over the signing context: ascending tag order,
     * each entry `[tag:1][len:1][value:len]`, terminated by `TAG_END(255)`.
     * `expires_at` and `counter` are encoded **big-endian uint32** here — this
     * is distinct from (and must not be confused with) the protobuf
     * `HMAC_Personalized_Signature_Data.expires_at` field, which is a
     * **little-endian fixed32**. `TAG_FLAGS` is included only when flags != 0;
     * these fixtures always set `FLAG_ENCRYPT_RESPONSE`, so it always appears.
     */
    fun buildMetadata(
        domain: VcpDomain,
        vin: String,
        epoch: ByteArray,
        expiresAt: Int,
        counter: Int,
        flags: Int = FLAGS_VALUE,
    ): ByteArray {
        val vinBytes = vin.toByteArray(Charsets.US_ASCII)
        require(vinBytes.size == 17) { "VIN must be 17 ASCII bytes, got ${vinBytes.size}" }
        require(epoch.size == 16) { "epoch must be 16 bytes, got ${epoch.size}" }

        val out = ByteArrayOutputStream()
        tlv(out, TAG_SIGNATURE_TYPE, byteArrayOf(SIGNATURE_TYPE_HMAC_PERSONALIZED.toByte()))
        tlv(out, TAG_DOMAIN, byteArrayOf(domain.wireValue.toByte()))
        tlv(out, TAG_PERSONALIZATION, vinBytes)
        tlv(out, TAG_EPOCH, epoch)
        tlv(out, TAG_EXPIRES_AT, uint32BigEndian(expiresAt))
        tlv(out, TAG_COUNTER, uint32BigEndian(counter))
        if (flags != 0) {
            tlv(out, TAG_FLAGS, uint32BigEndian(flags))
        }
        out.write(TAG_END)
        return out.toByteArray()
    }

    private fun tlv(out: ByteArrayOutputStream, tag: Int, value: ByteArray) {
        out.write(tag)
        out.write(value.size)
        out.write(value)
    }

    private fun uint32BigEndian(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    /**
     * Signs [plaintextAction] and assembles the full signed `RoutableMessage`
     * envelope, byte-exact against `commands.json`'s `routable_message_b64`.
     *
     * Field write order (from Task 22's decode of the fixtures):
     * `to_destination`(6), `from_destination`(7), `uuid`(51), `flags`(52),
     * `protobuf_message_as_bytes`(10), `signature_data`(13) — the last nesting
     * `signer_identity`(1){`public_key`(1)} and
     * `HMAC_Personalized_data`(8){`epoch`(1), `counter`(2) varint,
     * `expires_at`(3) **fixed32 little-endian**, `tag`(4)}.
     */
    fun sign(
        session: Session,
        domain: VcpDomain,
        plaintextAction: ByteArray,
        counter: Long,
        expiresAt: Int,
        uuid: ByteArray,
    ): ByteArray {
        val metadata = buildMetadata(
            domain = domain,
            vin = session.vin,
            epoch = session.epoch,
            expiresAt = expiresAt,
            counter = counter.toInt(),
        )
        val commandKey = VcpCrypto.subkey(session.sessionKey, VcpCrypto.LABEL_AUTHENTICATED_COMMAND)
        val tag = VcpCrypto.hmacSha256(commandKey, metadata + plaintextAction)

        val keyIdentity = ProtoWriter()
            .bytes(KEY_IDENTITY_PUBLIC_KEY, session.clientPublicKey)

        val hmacPersonalizedData = ProtoWriter()
            .bytes(HMAC_PERSONALIZED_EPOCH, session.epoch)
            .varint(HMAC_PERSONALIZED_COUNTER, counter)
            .fixed32(HMAC_PERSONALIZED_EXPIRES_AT, expiresAt)
            .bytes(HMAC_PERSONALIZED_TAG, tag)

        val signatureData = ProtoWriter()
            .message(SIGNATURE_DATA_SIGNER_IDENTITY, keyIdentity)
            .message(SIGNATURE_DATA_HMAC_PERSONALIZED_DATA, hmacPersonalizedData)

        val destinationDomain = ProtoWriter().varint(DESTINATION_DOMAIN, domain.wireValue.toLong())
        val destinationRoutingAddress = ProtoWriter()
            .bytes(DESTINATION_ROUTING_ADDRESS, session.fromRoutingAddress)

        return ProtoWriter()
            .message(TO_DESTINATION, destinationDomain)
            .message(FROM_DESTINATION, destinationRoutingAddress)
            .bytes(UUID, uuid)
            .varint(FLAGS, FLAGS_VALUE.toLong())
            .bytes(PAYLOAD_PROTOBUF_MESSAGE_AS_BYTES, plaintextAction)
            .message(SIGNATURE_DATA, signatureData)
            .toByteArray()
    }

    /**
     * `isFault(msg) == (signed_message_status.signed_message_fault != MESSAGEFAULT_ERROR_NONE(0))`
     * (README "Fault classification"). Parses the top-level `RoutableMessage`,
     * looks at `signedMessageStatus`(12){`signed_message_fault`(2)}.
     */
    fun isFault(routableMessageBytes: ByteArray): Boolean {
        var statusBytes: ByteArray? = null
        ProtoReader(routableMessageBytes).forEachField { field, _, value ->
            if (field == SIGNED_MESSAGE_STATUS) {
                statusBytes = value as ByteArray
            }
        }
        val bytes = statusBytes ?: return false

        var fault = MESSAGE_FAULT_NONE
        ProtoReader(bytes).forEachField { field, _, value ->
            if (field == MESSAGE_STATUS_SIGNED_MESSAGE_FAULT) {
                fault = (value as Long).toInt()
            }
        }
        return fault != MESSAGE_FAULT_NONE
    }
}
