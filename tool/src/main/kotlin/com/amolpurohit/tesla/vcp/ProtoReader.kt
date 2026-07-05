package com.amolpurohit.tesla.vcp

class ProtoReader(private val bytes: ByteArray) {
    private var pos: Int = 0

    fun forEachField(block: (field: Int, wireType: Int, value: Any) -> Unit) {
        while (pos < bytes.size) {
            val tag = readVarint()
            val wireType = (tag and 0x07).toInt()
            val field = (tag shr 3).toInt()

            val value: Any = when (wireType) {
                0 -> {
                    // varint
                    readVarint()
                }
                1 -> {
                    // 64-bit fixed
                    read64Bit()
                }
                2 -> {
                    // length-delimited
                    val length = readVarint().toInt()
                    if (length < 0 || pos + length > bytes.size) {
                        throw IllegalArgumentException("Malformed: length-delimited field exceeds message bounds")
                    }
                    val data = ByteArray(length)
                    for (i in 0 until length) {
                        data[i] = bytes[pos++]
                    }
                    data
                }
                5 -> {
                    // 32-bit fixed
                    read32Bit()
                }
                else -> {
                    throw IllegalArgumentException("Malformed: unknown wire type $wireType")
                }
            }

            block(field, wireType, value)
        }
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (pos < bytes.size) {
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) {
                return result
            }
            shift += 7
        }
        throw IllegalArgumentException("Malformed: incomplete varint at end of message")
    }

    private fun read64Bit(): Long {
        if (pos + 8 > bytes.size) {
            throw IllegalArgumentException("Malformed: truncated 64-bit field")
        }
        var result = 0L
        for (i in 0 until 8) {
            result = result or ((bytes[pos++].toInt() and 0xFF).toLong() shl (8 * i))
        }
        return result
    }

    private fun read32Bit(): Long {
        if (pos + 4 > bytes.size) {
            throw IllegalArgumentException("Malformed: truncated 32-bit field")
        }
        var result = 0L
        for (i in 0 until 4) {
            result = result or ((bytes[pos++].toInt() and 0xFF).toLong() shl (8 * i))
        }
        return result
    }
}
