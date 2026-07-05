package com.amolpurohit.tesla.vcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProtoCodecTest {
    @Test
    fun `varint round-trip zero`() {
        val encoded = ProtoWriter().varint(field = 1, value = 0).toByteArray()
        // field 1, wire type 0 = (1 shl 3) or 0 = 0x08
        assertEquals(listOf(0x08, 0x00), encoded.map { it.toInt() and 0xFF })

        val reader = ProtoReader(encoded)
        var visited = false
        reader.forEachField { field, wireType, value ->
            visited = true
            assertEquals(1, field)
            assertEquals(0, wireType)
            assertEquals(0L, value)
        }
        assertEquals(true, visited)
    }

    @Test
    fun `varint round-trip one`() {
        val encoded = ProtoWriter().varint(field = 1, value = 1).toByteArray()
        assertEquals(listOf(0x08, 0x01), encoded.map { it.toInt() and 0xFF })

        val reader = ProtoReader(encoded)
        var visited = false
        reader.forEachField { field, wireType, value ->
            visited = true
            assertEquals(1, field)
            assertEquals(0, wireType)
            assertEquals(1L, value)
        }
        assertEquals(true, visited)
    }

    @Test
    fun `varint round-trip 127`() {
        val encoded = ProtoWriter().varint(field = 1, value = 127).toByteArray()
        assertEquals(listOf(0x08, 0x7F), encoded.map { it.toInt() and 0xFF })

        val reader = ProtoReader(encoded)
        var visited = false
        reader.forEachField { field, wireType, value ->
            visited = true
            assertEquals(1, field)
            assertEquals(0, wireType)
            assertEquals(127L, value)
        }
        assertEquals(true, visited)
    }

    @Test
    fun `varint round-trip 128`() {
        val encoded = ProtoWriter().varint(field = 1, value = 128).toByteArray()
        // 128 = 0x80 0x01
        assertEquals(listOf(0x08, 0x80, 0x01), encoded.map { it.toInt() and 0xFF })

        val reader = ProtoReader(encoded)
        var visited = false
        reader.forEachField { field, wireType, value ->
            visited = true
            assertEquals(1, field)
            assertEquals(0, wireType)
            assertEquals(128L, value)
        }
        assertEquals(true, visited)
    }

    @Test
    fun `varint round-trip 300`() {
        val encoded = ProtoWriter().varint(field = 1, value = 300).toByteArray()
        // 300 = 0xAC 0x02
        assertEquals(listOf(0x08, 0xAC, 0x02), encoded.map { it.toInt() and 0xFF })

        val reader = ProtoReader(encoded)
        var visited = false
        reader.forEachField { field, wireType, value ->
            visited = true
            assertEquals(1, field)
            assertEquals(0, wireType)
            assertEquals(300L, value)
        }
        assertEquals(true, visited)
    }

    @Test
    fun `varint round-trip Int MAX_VALUE`() {
        val max = Int.MAX_VALUE.toLong()
        val encoded = ProtoWriter().varint(field = 1, value = max).toByteArray()

        val reader = ProtoReader(encoded)
        var visited = false
        reader.forEachField { field, wireType, value ->
            visited = true
            assertEquals(1, field)
            assertEquals(0, wireType)
            assertEquals(max, value)
        }
        assertEquals(true, visited)
    }

    @Test
    fun `varint round-trip negative int as 10-byte varint`() {
        // -1L encodes as 10 bytes: 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0x01
        val encoded = ProtoWriter().varint(field = 1, value = -1L).toByteArray()
        val bytes = encoded.map { it.toInt() and 0xFF }
        assertEquals(11, bytes.size) // tag + 10 varint bytes
        assertEquals(0x08, bytes[0]) // tag
        assertEquals(listOf(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01),
            bytes.drop(1))

        val reader = ProtoReader(encoded)
        var visited = false
        reader.forEachField { field, wireType, value ->
            visited = true
            assertEquals(1, field)
            assertEquals(0, wireType)
            assertEquals(-1L, value)
        }
        assertEquals(true, visited)
    }

    @Test
    fun `raw varint 300 encodes to 0xAC 0x02`() {
        // Directly test writeVarintRaw equivalent
        val writer = ProtoWriter()
        val encoded = writer.varint(field = 1, value = 300).toByteArray()
        // Skip tag, check value bytes
        assertEquals(listOf(0xAC, 0x02), encoded.drop(1).map { it.toInt() and 0xFF })
    }

    @Test
    fun `length-delimited bytes round-trip`() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val encoded = ProtoWriter().bytes(field = 2, value = data).toByteArray()
        // field 2, wire type 2 = (2 shl 3) or 2 = 0x12, then length 3 (0x03), then bytes
        assertEquals(listOf(0x12, 0x03, 0x01, 0x02, 0x03), encoded.map { it.toInt() and 0xFF })

        val reader = ProtoReader(encoded)
        var visited = false
        reader.forEachField { field, wireType, value ->
            visited = true
            assertEquals(2, field)
            assertEquals(2, wireType)
            assertEquals(data.toList(), (value as ByteArray).toList())
        }
        assertEquals(true, visited)
    }

    @Test
    fun `length-delimited string round-trip`() {
        val text = "hello"
        val encoded = ProtoWriter().string(field = 3, value = text).toByteArray()
        // field 3, wire type 2 = (3 shl 3) or 2 = 0x1A, then length 5, then "hello" bytes
        val bytes = encoded.map { it.toInt() and 0xFF }
        assertEquals(0x1A, bytes[0])
        assertEquals(5, bytes[1])
        assertEquals("hello".toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF },
            bytes.drop(2))

        val reader = ProtoReader(encoded)
        var visited = false
        reader.forEachField { field, wireType, value ->
            visited = true
            assertEquals(3, field)
            assertEquals(2, wireType)
            assertEquals(text, String((value as ByteArray), Charsets.UTF_8))
        }
        assertEquals(true, visited)
    }

    @Test
    fun `embedded message round-trip`() {
        val inner = ProtoWriter().varint(field = 1, value = 42)
        val outer = ProtoWriter().message(field = 4, m = inner)
        val encoded = outer.toByteArray()

        val reader = ProtoReader(encoded)
        var visitedOuter = false
        reader.forEachField { field, wireType, value ->
            visitedOuter = true
            assertEquals(4, field)
            assertEquals(2, wireType)
            val innerBytes = value as ByteArray
            val innerReader = ProtoReader(innerBytes)
            var visitedInner = false
            innerReader.forEachField { innerField, innerWireType, innerValue ->
                visitedInner = true
                assertEquals(1, innerField)
                assertEquals(0, innerWireType)
                assertEquals(42L, innerValue)
            }
            assertEquals(true, visitedInner)
        }
        assertEquals(true, visitedOuter)
    }

    @Test
    fun `fixed32 round-trip`() {
        val value = 0x12345678
        val encoded = ProtoWriter().fixed32(field = 5, value = value).toByteArray()
        // field 5, wire type 5 = (5 shl 3) or 5 = 0x2D, then 4 bytes little-endian
        val bytes = encoded.map { it.toInt() and 0xFF }
        assertEquals(0x2D, bytes[0])
        // little-endian: 0x78 0x56 0x34 0x12
        assertEquals(listOf(0x78, 0x56, 0x34, 0x12), bytes.drop(1))

        val reader = ProtoReader(encoded)
        var visited = false
        reader.forEachField { field, wireType, value ->
            visited = true
            assertEquals(5, field)
            assertEquals(5, wireType)
            // read back as Long (4 bytes little-endian)
            assertEquals(0x12345678L, value)
        }
        assertEquals(true, visited)
    }

    @Test
    fun `skip unknown wire type 64-bit correctly`() {
        // Mix of wire types that reader must skip correctly
        // field 10, wire type 1 = (10 << 3) | 1 = 81 = 0x51
        val encoded = byteArrayOf(
            0x51.toByte(), 0x88.toByte(), 0x77.toByte(), 0x66.toByte(),
            0x55.toByte(), 0x44.toByte(), 0x33.toByte(), 0x22.toByte(), 0x11.toByte(),
            // field 1, wire type 0 = (1 << 3) | 0 = 8 = 0x08, then varint 42 = 0x2A
            0x08.toByte(), 0x2A.toByte(),
            // field 2, wire type 2 = (2 << 3) | 2 = 18 = 0x12, then length 2, then 0xAA 0xBB
            0x12.toByte(), 0x02.toByte(), 0xAA.toByte(), 0xBB.toByte()
        )

        val reader = ProtoReader(encoded)
        val results = mutableListOf<Pair<Int, Any>>()
        reader.forEachField { field, wireType, value ->
            results.add(field to value)
        }

        assertEquals(3, results.size)
        // First: field 10, wire type 1 (64-bit)
        assertEquals(10, results[0].first)
        assertEquals(0x1122334455667788L, results[0].second as Long)
        // Second: field 1, varint 42
        assertEquals(1, results[1].first)
        assertEquals(42L, results[1].second as Long)
        // Third: field 2, length-delimited
        assertEquals(2, results[2].first)
        assertEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()).toList(),
            (results[2].second as ByteArray).toList())
    }

    @Test
    fun `caller ignores unknown wire types but reader still advances correctly`() {
        // Build: varint(field=1, 100), fixed32(field=2, 0x12345678), varint(field=3, 200)
        val encoded = byteArrayOf(
            // field 1, wire type 0, varint 100
            0x08.toByte(), 0x64.toByte(),
            // field 2, wire type 5, fixed32
            0x15.toByte(), 0x78.toByte(), 0x56.toByte(), 0x34.toByte(), 0x12.toByte(),
            // field 3, wire type 0, varint 200
            0x18.toByte(), 0xC8.toByte(), 0x01.toByte(),
        )

        val reader = ProtoReader(encoded)
        val varints = mutableListOf<Long>()
        reader.forEachField { field, wireType, value ->
            if (wireType == 0) { // only process varints
                varints.add(value as Long)
            }
        }

        // Should have collected both varints in order
        assertEquals(listOf(100L, 200L), varints)
    }

    @Test
    fun `malformed wire type throws exception`() {
        val encoded = byteArrayOf(
            // field 1, wire type 3 (invalid)
            0x0B.toByte(), 0x42.toByte(),
        )

        val reader = ProtoReader(encoded)
        assertFailsWith<IllegalArgumentException> {
            reader.forEachField { _, _, _ -> }
        }
    }

    @Test
    fun `truncated length-delimited throws exception`() {
        // field 1, wire type 2 = 0x0A, length varint 10, but only 2 payload bytes
        val encoded = byteArrayOf(
            0x0A.toByte(), 0x0A.toByte(), 0x01.toByte(), 0x02.toByte()
        )

        val reader = ProtoReader(encoded)
        assertFailsWith<IllegalArgumentException> {
            reader.forEachField { _, _, _ -> }
        }
    }

    @Test
    fun `truncated fixed32 throws exception`() {
        // field 1, wire type 5 = 0x0D, then only 2 bytes (need 4)
        val encoded = byteArrayOf(
            0x0D.toByte(), 0x78.toByte(), 0x56.toByte()
        )

        val reader = ProtoReader(encoded)
        assertFailsWith<IllegalArgumentException> {
            reader.forEachField { _, _, _ -> }
        }
    }

    @Test
    fun `truncated fixed64 throws exception`() {
        // field 1, wire type 1 = 0x09, then only 3 bytes (need 8)
        val encoded = byteArrayOf(
            0x09.toByte(), 0x11.toByte(), 0x22.toByte(), 0x33.toByte()
        )

        val reader = ProtoReader(encoded)
        assertFailsWith<IllegalArgumentException> {
            reader.forEachField { _, _, _ -> }
        }
    }
}
