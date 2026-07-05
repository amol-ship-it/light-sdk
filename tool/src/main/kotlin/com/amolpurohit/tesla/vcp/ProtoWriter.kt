package com.amolpurohit.tesla.vcp

import java.io.ByteArrayOutputStream

class ProtoWriter {
    private val buf = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        tag(field, 0)
        writeVarintRaw(value)
        return this
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, 2)
        writeVarintRaw(value.size.toLong())
        buf.write(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter {
        return bytes(field, value.toByteArray(Charsets.UTF_8))
    }

    fun message(field: Int, m: ProtoWriter): ProtoWriter {
        return bytes(field, m.toByteArray())
    }

    fun fixed32(field: Int, value: Int): ProtoWriter {
        tag(field, 5)
        repeat(4) { i ->
            buf.write((value ushr (8 * i)) and 0xFF)
        }
        return this
    }

    fun toByteArray(): ByteArray {
        return buf.toByteArray()
    }

    private fun tag(field: Int, wireType: Int) {
        writeVarintRaw(((field shl 3) or wireType).toLong())
    }

    private fun writeVarintRaw(v: Long) {
        var x = v
        while (true) {
            if (x and 0x7F.inv().toLong() == 0L) {
                buf.write(x.toInt())
                return
            }
            buf.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
    }
}
