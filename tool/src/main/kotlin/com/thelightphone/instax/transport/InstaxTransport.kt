package com.thelightphone.instax.transport

import kotlinx.coroutines.flow.Flow

/**
 * Abstract byte-frame pipe to a printer. Implementations:
 * [TcpBridgeTransport] (dev — Mac bridge relays BLE), AndroidBleTransport
 * (real hardware; compile-only this milestone, see the design spec).
 */
interface InstaxTransport {
    /** Discover printers. Emits found devices until the collecting coroutine is cancelled. */
    fun scan(): Flow<PrinterDevice>

    suspend fun connect(device: PrinterDevice)

    /** Send one whole protocol packet; the transport handles any lower-level write splitting. */
    suspend fun send(packet: ByteArray)

    /** Whole notification frames from the printer. Fails with [TransportException] on disconnect. */
    val notifications: Flow<ByteArray>

    suspend fun close()
}

data class PrinterDevice(val name: String, val address: String)

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
