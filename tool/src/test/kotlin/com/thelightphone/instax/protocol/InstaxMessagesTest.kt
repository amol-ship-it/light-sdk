package com.thelightphone.instax.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstaxMessagesTest {

    private fun payloadOf(name: String): ByteArray =
        assertNotNull(InstaxPacket.decode(Fixtures.bytes(name)), name).payload

    // --- request builders, byte-equal to fixtures ---

    @Test
    fun `info queries match fixtures`() {
        assertContentEquals(
            Fixtures.bytes("support_function_info_image_support"),
            InstaxMessages.infoQuery(InfoType.IMAGE_SUPPORT),
        )
        assertContentEquals(
            Fixtures.bytes("support_function_info_battery"),
            InstaxMessages.infoQuery(InfoType.BATTERY),
        )
        assertContentEquals(
            Fixtures.bytes("support_function_info_printer_function"),
            InstaxMessages.infoQuery(InfoType.PRINTER_FUNCTION),
        )
    }

    @Test
    fun `print start carries type prefix and BE size`() {
        assertContentEquals(
            Fixtures.bytes("print_start_1800_bytes"),
            InstaxMessages.printStart(1800),
        )
    }

    @Test
    fun `print data chunks match fixtures including index and padding`() {
        val img = ByteArray(1800) { (it % 251).toByte() }
        val chunkSize = Fixtures.wideChunkSize()
        assertContentEquals(
            Fixtures.bytes("print_data_chunk0"),
            InstaxMessages.printData(0, img.copyOfRange(0, chunkSize), chunkSize),
        )
        assertContentEquals(
            Fixtures.bytes("print_data_chunk1"),
            InstaxMessages.printData(1, img.copyOfRange(chunkSize, 1800), chunkSize),
        )
        // padded tail chunk: 100 data bytes from the 1900-byte image, zero-padded to 900
        val img1900 = ByteArray(1900) { (it % 251).toByte() }
        assertContentEquals(
            Fixtures.bytes("print_data_last_chunk_padded"),
            InstaxMessages.printData(2, img1900.copyOfRange(1800, 1900), chunkSize),
        )
    }

    @Test
    fun `print end, print image and cancel match fixtures`() {
        assertContentEquals(Fixtures.bytes("print_end"), InstaxMessages.printEnd())
        assertContentEquals(Fixtures.bytes("print_image"), InstaxMessages.printImage())
        // cancel appears in fixtures as checksum_case_0
        assertContentEquals(Fixtures.bytes("checksum_case_0"), InstaxMessages.printCancel())
    }

    // --- response parsers, offsets per fixture meanings ---

    @Test
    fun `parses image support response`() {
        val support = assertNotNull(InstaxMessages.parseImageSupport(payloadOf("response_image_support_wide")))
        assertEquals(1260, support.width)
        assertEquals(840, support.height)
    }

    @Test
    fun `parses battery response`() {
        val battery = assertNotNull(InstaxMessages.parseBattery(payloadOf("response_battery")))
        assertEquals(82, battery.percent)
    }

    @Test
    fun `parses printer function response`() {
        val status = assertNotNull(
            InstaxMessages.parsePrinterFunction(payloadOf("response_printer_function_7_left_charging"))
        )
        assertEquals(7, status.printsRemaining)
        assertTrue(status.charging)

        val empty = assertNotNull(
            InstaxMessages.parsePrinterFunction(payloadOf("response_printer_function_0_left_not_charging"))
        )
        assertEquals(0, empty.printsRemaining)
        assertFalse(empty.charging)
    }

    @Test
    fun `parsers reject truncated payloads`() {
        assertEquals(null, InstaxMessages.parseImageSupport(byteArrayOf(0, 0)))
        assertEquals(null, InstaxMessages.parseBattery(byteArrayOf(0)))
        assertEquals(null, InstaxMessages.parsePrinterFunction(ByteArray(0)))
    }

    @Test
    fun `ack matching mirrors the request opcode`() {
        val ack = assertNotNull(InstaxPacket.decode(Fixtures.bytes("ack_print_start")))
        assertTrue(InstaxMessages.isAckFor(ack, Opcode.PRINT_IMAGE_DOWNLOAD_START))
        assertFalse(InstaxMessages.isAckFor(ack, Opcode.PRINT_IMAGE))
    }
}
