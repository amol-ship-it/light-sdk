package com.thelightphone.instax.protocol

/** SUPPORT_FUNCTION_INFO query types (reference Types.py InfoType). */
enum class InfoType(val value: Int) {
    IMAGE_SUPPORT(0),
    BATTERY(1),
    PRINTER_FUNCTION(2),
}

/** Printer-reported error conditions. Byte values are G2-provisional: the
 *  pinned reference never inspects error payloads, so anything we can't
 *  positively identify maps to UNKNOWN until verified on real hardware. */
enum class InstaxError { NO_FILM, COVER_OPEN, BUSY, UNKNOWN }

class BatteryInfo(val percent: Int)
class PrinterStatus(val printsRemaining: Int, val charging: Boolean)
class ImageSupport(val width: Int, val height: Int)

/**
 * Typed requests and response parsers over InstaxPacket. All payload layouts
 * and offsets are fixture-verified (see instax-fixtures/fixtures.json meanings):
 * info responses are payload = [status, infoTypeEcho, ...data].
 */
object InstaxMessages {

    fun infoQuery(type: InfoType): ByteArray =
        InstaxPacket.encode(Opcode.SUPPORT_FUNCTION_INFO, byteArrayOf(type.value.toByte()))

    /** Payload = 02 00 00 00 (pictureType + print options, fixed) + 4-byte BE size. */
    fun printStart(imageSize: Int): ByteArray {
        val payload = ByteArray(8)
        payload[0] = 0x02
        payload[4] = (imageSize ushr 24).toByte()
        payload[5] = (imageSize ushr 16).toByte()
        payload[6] = (imageSize ushr 8).toByte()
        payload[7] = imageSize.toByte()
        return InstaxPacket.encode(Opcode.PRINT_IMAGE_DOWNLOAD_START, payload)
    }

    /** Payload = 4-byte BE chunk index + chunk, zero-padded to chunkSize. */
    fun printData(index: Int, chunk: ByteArray, chunkSize: Int): ByteArray {
        require(chunk.size <= chunkSize) { "chunk larger than chunkSize" }
        val payload = ByteArray(4 + chunkSize)
        payload[0] = (index ushr 24).toByte()
        payload[1] = (index ushr 16).toByte()
        payload[2] = (index ushr 8).toByte()
        payload[3] = index.toByte()
        chunk.copyInto(payload, 4)
        return InstaxPacket.encode(Opcode.PRINT_IMAGE_DOWNLOAD_DATA, payload)
    }

    fun printEnd(): ByteArray = InstaxPacket.encode(Opcode.PRINT_IMAGE_DOWNLOAD_END)
    fun printImage(): ByteArray = InstaxPacket.encode(Opcode.PRINT_IMAGE)
    fun printCancel(): ByteArray = InstaxPacket.encode(Opcode.PRINT_IMAGE_DOWNLOAD_CANCEL)

    /** The printer acks by echoing the request opcode. */
    fun isAckFor(decoded: InstaxPacket.Decoded, request: Opcode): Boolean =
        decoded.opcode == request

    // Info response payload: [0]=status, [1]=InfoType echo, data from [2].

    fun parseImageSupport(payload: ByteArray): ImageSupport? {
        if (payload.size < 6 || payload[1].toInt() != InfoType.IMAGE_SUPPORT.value) return null
        val w = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        val h = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
        return ImageSupport(w, h)
    }

    fun parseBattery(payload: ByteArray): BatteryInfo? {
        if (payload.size < 4 || payload[1].toInt() != InfoType.BATTERY.value) return null
        return BatteryInfo(percent = payload[3].toInt() and 0xFF)
    }

    fun parsePrinterFunction(payload: ByteArray): PrinterStatus? {
        if (payload.size < 3 || payload[1].toInt() != InfoType.PRINTER_FUNCTION.value) return null
        val statusByte = payload[2].toInt() and 0xFF
        return PrinterStatus(
            printsRemaining = statusByte and 0x0F,
            charging = statusByte and 0x80 != 0,
        )
    }
}
