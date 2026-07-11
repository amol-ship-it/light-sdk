package com.thelightphone.instax.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstaxPacketTest {

    private val requestFixtures = Fixtures.all.filter {
        !it.name.startsWith("response_") && !it.name.startsWith("ack_")
    }
    private val responseFixtures = Fixtures.all.filter {
        it.name.startsWith("response_") || it.name.startsWith("ack_")
    }

    @Test
    fun `encodes every request fixture byte-for-byte`() {
        assertTrue(requestFixtures.isNotEmpty())
        for (f in requestFixtures) {
            val op1 = Fixtures.meaningInt(f, "op1")
            val op2 = Fixtures.meaningInt(f, "op2")
            val opcode = assertNotNull(Opcode.from(op1, op2), "unknown opcode in ${f.name}")
            val payload = Fixtures.meaningHex(f, "payload_hex")
            assertContentEquals(f.bytes, InstaxPacket.encode(opcode, payload), f.name)
        }
    }

    @Test
    fun `checksum matches fixture checksum cases`() {
        val cases = Fixtures.all.filter { it.name.startsWith("checksum_case_") }
        assertEquals(5, cases.size)
        for (f in cases) {
            val expected = Fixtures.meaningInt(f, "checksum").toByte()
            assertEquals(expected, f.bytes.last(), f.name)
            assertEquals(expected, InstaxPacket.checksum(f.bytes, f.bytes.size - 1), f.name)
        }
    }

    @Test
    fun `decodes response fixtures into opcode and payload`() {
        assertTrue(responseFixtures.isNotEmpty())
        for (f in responseFixtures) {
            val decoded = assertNotNull(InstaxPacket.decode(f.bytes), f.name)
            assertEquals(Fixtures.meaningInt(f, "op1"), decoded.opcode.op1, f.name)
            assertEquals(Fixtures.meaningInt(f, "op2"), decoded.opcode.op2, f.name)
            assertContentEquals(Fixtures.meaningHex(f, "payload_hex"), decoded.payload, f.name)
        }
    }

    @Test
    fun `decode rejects bad checksum`() {
        val bytes = Fixtures.bytes("response_battery").copyOf()
        bytes[bytes.size - 1] = (bytes.last() + 1).toByte()
        assertNull(InstaxPacket.decode(bytes))
    }

    @Test
    fun `decode rejects wrong direction header`() {
        // a request packet has the client->printer header; decode expects printer->client
        assertNull(InstaxPacket.decode(Fixtures.bytes("print_end")))
    }

    @Test
    fun `decode rejects truncated and length-mismatched packets`() {
        val bytes = Fixtures.bytes("response_battery")
        assertNull(InstaxPacket.decode(bytes.copyOfRange(0, 5)))
        assertNull(InstaxPacket.decode(bytes + byteArrayOf(0)))
    }
}
