package com.linex.vm.network

import java.io.ByteArrayOutputStream

class UnsupportedDnsQueryException : IllegalArgumentException("Unsupported DNS query")

/** Validated guest question. Wire bytes are never exposed by reference or written to logs. */
class DnsQuestion internal constructor(
    val transactionId: Int, val flags: Int, val type: Int, val udpPayloadLimit: Int,
    internal val canonicalName: List<Int>, private val question: ByteArray, bytes: ByteArray
) {
    private val bytes = bytes.copyOf()
    fun wire(): ByteArray = bytes.copyOf()
    internal fun questionWire() = question.copyOf()
}

/** Bounded DNS envelopes, not a second IP stack or an app-controlled DNS resolver. */
object DnsWire {
    const val MAX_QUERY_BYTES = 1232
    const val MAX_ANSWER_BYTES = 8192

    fun parseQuery(bytes: ByteArray): DnsQuestion {
        require(bytes.size in 12..MAX_QUERY_BYTES) { "Invalid DNS query size" }
        val flags = u16(bytes, 2)
        require(flags and 0xfecf == 0 && u16(bytes, 4) == 1 &&
            u16(bytes, 6) == 0 && u16(bytes, 8) == 0 && u16(bytes, 10) <= 1) {
            "Invalid ordinary DNS query"
        }
        val name = name(bytes, 12, allowPointers = false)
        require(name.end + 4 <= bytes.size) { "Incomplete DNS question" }
        val type = u16(bytes, name.end)
        val clazz = u16(bytes, name.end + 2)
        require(type != 0 && clazz != 0) { "Invalid DNS question type" }
        if (clazz != 1 || type in 251..252) throw UnsupportedDnsQueryException()
        var end = name.end + 4
        var udpLimit = 512
        if (u16(bytes, 10) == 1) {
            require(end + 11 <= bytes.size && bytes[end].toInt() == 0 && u16(bytes, end + 1) == 41) {
                "Invalid DNS additional record"
            }
            require(u16(bytes, end + 5) == 0 && u16(bytes, end + 7) and 0x7fff == 0) {
                "Invalid DNS EDNS version or flags"
            }
            udpLimit = u16(bytes, end + 3).coerceIn(512, MAX_QUERY_BYTES)
            val length = u16(bytes, end + 9)
            end += 11
            require(length == bytes.size - end) { "Invalid DNS EDNS length" }
            val optionsEnd = end + length
            while (end < optionsEnd) {
                require(end + 4 <= optionsEnd) { "Invalid DNS EDNS option" }
                val optionLength = u16(bytes, end + 2)
                end += 4
                require(optionLength <= optionsEnd - end) { "Invalid DNS EDNS option length" }
                end += optionLength
            }
        }
        require(end == bytes.size) { "Unexpected DNS query bytes" }
        val question = name.uncompressed + bytes.copyOfRange(name.end, name.end + 4)
        return DnsQuestion(u16(bytes, 0), flags, type, udpLimit, name.canonical, question, bytes)
    }

    fun failure(query: DnsQuestion, rcode: Int = 2): ByteArray {
        require(rcode in 0..15)
        return minimal(query, 0x8080 or (query.flags and 0x0110) or rcode)
    }

    /** API26–28 compatibility cannot preserve arbitrary wire DNS or DNSSEC. */
    fun legacyName(query: DnsQuestion): String {
        if (query.type != 1 && query.type != 28) throw UnsupportedDnsQueryException()
        val wire = query.questionWire()
        val labels = mutableListOf<String>()
        var cursor = 0
        while (wire[cursor].toInt() != 0) {
            val length = wire[cursor++].toInt() and 255
            val label = wire.copyOfRange(cursor, cursor + length)
            if (!label.all { byte ->
                    val value = byte.toInt() and 255
                    value in 65..90 || value in 97..122 || value in 48..57 || value == 45 || value == 95
                }) throw UnsupportedDnsQueryException()
            labels.add(String(label, Charsets.US_ASCII))
            cursor += length
        }
        return if (labels.isEmpty()) "." else labels.joinToString(".")
    }

    fun legacyAnswer(query: DnsQuestion, addresses: List<ByteArray>): ByteArray {
        legacyName(query)
        require(addresses.size <= 32) { "Too many legacy DNS addresses" }
        val width = if (query.type == 1) 4 else 16
        val selected = addresses.filter { it.size == width }
        val result = ByteArrayOutputStream(1024)
        val headerAndQuestion = failure(query, 0)
        put16(headerAndQuestion, 6, selected.size)
        result.write(headerAndQuestion)
        selected.forEach { address ->
            result.write(byteArrayOf(0xc0.toByte(), 12, (query.type ushr 8).toByte(), query.type.toByte(),
                0, 1, 0, 0, 0, 30, 0, width.toByte()))
            result.write(address)
        }
        return result.toByteArray()
    }

    fun answerFor(query: DnsQuestion, transport: DnsTransport, answer: ByteArray): ByteArray {
        require(answer.size in 12..MAX_ANSWER_BYTES) { "Invalid DNS answer size" }
        val flags = u16(answer, 2)
        require(u16(answer, 0) == query.transactionId && flags and 0xf800 == 0x8000 &&
            flags and 0x0040 == 0 && u16(answer, 4) == 1) { "Invalid DNS answer header" }
        val name = name(answer, 12, allowPointers = false)
        require(name.end + 4 <= answer.size && name.canonical == query.canonicalName &&
            u16(answer, name.end) == query.type && u16(answer, name.end + 2) == 1) {
            "Mismatched DNS answer question"
        }
        var end = name.end + 4
        val records = u16(answer, 6) + u16(answer, 8) + u16(answer, 10)
        require(records <= (answer.size - end) / 11) { "Invalid DNS answer record count" }
        repeat(records) {
            end = name(answer, end).end
            require(end + 10 <= answer.size) { "Incomplete DNS answer record" }
            val length = u16(answer, end + 8)
            end += 10
            require(length <= answer.size - end) { "Invalid DNS answer record length" }
            end += length
        }
        require(end == answer.size) { "Unexpected DNS answer bytes" }
        if (transport == DnsTransport.UDP && answer.size > query.udpPayloadLimit) {
            // A complete question and zero RRs is legal TC; never cut a record mid-wire.
            return minimal(query, (flags or 0x0200) and 0xffdf)
        }
        return answer.copyOf()
    }

    private fun minimal(query: DnsQuestion, flags: Int): ByteArray {
        val header = ByteArray(12)
        put16(header, 0, query.transactionId); put16(header, 2, flags); put16(header, 4, 1)
        return header + query.questionWire()
    }

    private data class Name(val end: Int, val canonical: List<Int>, val uncompressed: ByteArray)

    private fun name(bytes: ByteArray, start: Int, allowPointers: Boolean = true): Name {
        var cursor = start
        var end = -1
        var pointerJumps = 0
        val visited = HashSet<Int>()
        val output = ByteArrayOutputStream(64)
        while (true) {
            require(cursor in 12 until bytes.size && visited.add(cursor)) { "Invalid DNS name pointer" }
            val length = bytes[cursor].toInt() and 255
            when {
                length == 0 -> {
                    output.write(0)
                    if (end < 0) end = cursor + 1
                    break
                }
                length and 0xc0 == 0xc0 -> {
                    // With one first question, no earlier name exists before
                    // its QNAME. A pointer into a binary label/header is invalid.
                    require(allowPointers) { "Compressed first DNS question" }
                    require(++pointerJumps <= 128) { "DNS name pointer chain is too long" }
                    require(cursor + 1 < bytes.size) { "Incomplete DNS name pointer" }
                    val target = ((length and 63) shl 8) or (bytes[cursor + 1].toInt() and 255)
                    require(target in 12 until cursor) { "Invalid DNS name pointer target" }
                    if (end < 0) end = cursor + 2
                    cursor = target
                }
                else -> {
                    require(length in 1..63 && length <= bytes.size - cursor - 1 &&
                        output.size() + length + 2 <= 255) { "Invalid DNS label size" }
                    output.write(length)
                    output.write(bytes, cursor + 1, length)
                    cursor += length + 1
                }
            }
        }
        val wire = output.toByteArray()
        val canonical = wire.map { byte ->
            val value = byte.toInt() and 255
            if (value in 65..90) value + 32 else value
        }
        return Name(end, canonical, wire)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte(); bytes[offset + 1] = value.toByte()
    }
}
