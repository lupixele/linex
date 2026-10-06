package com.linex.vm.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class DnsTransport(val wireValue: Int) { UDP(1), TCP(2) }

enum class DnsBridgeError(val wireValue: Int) {
    MALFORMED(1), UNSUPPORTED(2), RESOLVER_FAILURE(3), TIMEOUT(4), CAPACITY(5),
    NETWORK_CHANGED(6), STOPPED(7)
}

/** A single SOCK_SEQPACKET datagram; the native boundary independently validates it. */
class DnsBridgeFrame private constructor(
    val generation: Long, val requestId: Long, val transport: DnsTransport,
    val kind: Int, val error: DnsBridgeError?, bytes: ByteArray
) {
    private val bytes = bytes.copyOf()
    val payloadSize: Int get() = bytes.size
    fun payload(): ByteArray = bytes.copyOf()

    fun encode(): ByteArray = ByteBuffer.allocate(HEADER_BYTES + bytes.size)
        .order(ByteOrder.BIG_ENDIAN).apply {
            putInt(MAGIC); put(VERSION.toByte()); put(kind.toByte())
            put(transport.wireValue.toByte()); put((error?.wireValue ?: 0).toByte())
            putLong(generation); putLong(requestId); putInt(bytes.size); putInt(0); put(bytes)
        }.array()

    companion object {
        const val HEADER_BYTES = 32
        const val MAX_FRAME_BYTES = 16 * 1024
        const val REQUEST = 1
        const val ANSWER = 2
        const val ERROR = 3
        const val CANCEL = 4
        private const val MAGIC = 0x4c58444e // LXDN
        private const val VERSION = 1

        fun request(generation: Long, requestId: Long, transport: DnsTransport, bytes: ByteArray) =
            validated(generation, requestId, transport, REQUEST, null, bytes)
        fun answer(generation: Long, requestId: Long, transport: DnsTransport, bytes: ByteArray) =
            validated(generation, requestId, transport, ANSWER, null, bytes)
        fun error(generation: Long, requestId: Long, transport: DnsTransport, error: DnsBridgeError) =
            validated(generation, requestId, transport, ERROR, error, byteArrayOf())
        fun cancel(generation: Long, requestId: Long, transport: DnsTransport, reason: DnsBridgeError) =
            validated(generation, requestId, transport, CANCEL, reason, byteArrayOf())

        fun decode(packet: ByteArray): DnsBridgeFrame {
            require(packet.size in HEADER_BYTES..MAX_FRAME_BYTES) { "Invalid DNS bridge frame size" }
            val header = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
            require(header.int == MAGIC && unsigned(header.get()) == VERSION) { "Invalid DNS bridge protocol" }
            val kind = unsigned(header.get())
            val transport = DnsTransport.entries.firstOrNull { it.wireValue == unsigned(packet[6]) }
                ?: throw IllegalArgumentException("Invalid DNS transport")
            header.get()
            val status = unsigned(header.get())
            val error = if (status == 0) null else DnsBridgeError.entries.firstOrNull { it.wireValue == status }
                ?: throw IllegalArgumentException("Invalid DNS error status")
            val generation = header.long
            val requestId = header.long
            val length = header.int
            require(header.int == 0 && length >= 0 && length == packet.size - HEADER_BYTES) {
                "Invalid DNS bridge payload length"
            }
            // Check the declared size before allocating/copying guest-controlled bytes.
            validate(generation, requestId, kind, error, length)
            return DnsBridgeFrame(generation, requestId, transport, kind, error,
                packet.copyOfRange(HEADER_BYTES, packet.size))
        }

        private fun validated(generation: Long, id: Long, transport: DnsTransport, kind: Int,
                              error: DnsBridgeError?, bytes: ByteArray): DnsBridgeFrame {
            validate(generation, id, kind, error, bytes.size)
            return DnsBridgeFrame(generation, id, transport, kind, error, bytes)
        }

        private fun validate(generation: Long, id: Long, kind: Int, error: DnsBridgeError?, length: Int) {
            require(generation > 0 && id > 0) { "Invalid DNS request identity" }
            require(when (kind) {
                REQUEST -> error == null && length in 12..DnsWire.MAX_QUERY_BYTES
                ANSWER -> error == null && length in 12..DnsWire.MAX_ANSWER_BYTES
                ERROR -> error != null && length == 0
                CANCEL -> error in listOf(DnsBridgeError.STOPPED, DnsBridgeError.TIMEOUT) && length == 0
                else -> false
            }) { "Invalid DNS bridge payload" }
        }

        private fun unsigned(byte: Byte) = byte.toInt() and 255
    }
}
