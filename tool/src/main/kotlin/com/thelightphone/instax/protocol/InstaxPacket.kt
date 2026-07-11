package com.thelightphone.instax.protocol

/**
 * One Instax wire packet. Layout (both directions):
 *
 *   header(2) | totalLength(2, BE) | opcode(2) | payload(n) | checksum(1)
 *
 * totalLength counts the whole packet including header and checksum (7 + n).
 * Client->printer header = 0x41 0x62 ("Ab"); printer->client = 0x61 0x42 ("aB").
 * checksum = (255 - (sum of all preceding bytes & 255)) & 255, so a valid
 * packet sums to 255 (mod 256). Verified against fixtures cross-generated
 * from javl/InstaxBLE.
 */
object InstaxPacket {
    private const val HEADER_TO_PRINTER_0 = 0x41
    private const val HEADER_TO_PRINTER_1 = 0x62
    private const val HEADER_FROM_PRINTER_0 = 0x61
    private const val HEADER_FROM_PRINTER_1 = 0x42

    class Decoded(val opcode: Opcode, val payload: ByteArray)

    fun checksum(bytes: ByteArray, len: Int): Byte {
        var sum = 0
        for (i in 0 until len) sum += bytes[i].toInt() and 0xFF
        return ((255 - (sum and 0xFF)) and 0xFF).toByte()
    }

    fun encode(opcode: Opcode, payload: ByteArray = ByteArray(0)): ByteArray {
        val total = 7 + payload.size
        val out = ByteArray(total)
        out[0] = HEADER_TO_PRINTER_0.toByte()
        out[1] = HEADER_TO_PRINTER_1.toByte()
        out[2] = (total ushr 8).toByte()
        out[3] = total.toByte()
        out[4] = opcode.op1.toByte()
        out[5] = opcode.op2.toByte()
        payload.copyInto(out, 6)
        out[total - 1] = checksum(out, total - 1)
        return out
    }

    /** Decode a printer->client packet; null if header/length/checksum/opcode invalid. */
    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size < 7) return null
        if (bytes[0].toInt() and 0xFF != HEADER_FROM_PRINTER_0 ||
            bytes[1].toInt() and 0xFF != HEADER_FROM_PRINTER_1
        ) return null
        val total = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        if (total != bytes.size) return null
        if (bytes[total - 1] != checksum(bytes, total - 1)) return null
        val opcode = Opcode.from(bytes[4].toInt() and 0xFF, bytes[5].toInt() and 0xFF) ?: return null
        return Decoded(opcode, bytes.copyOfRange(6, total - 1))
    }
}
