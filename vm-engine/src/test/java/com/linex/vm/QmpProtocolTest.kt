package com.linex.vm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class QmpProtocolTest {
    private fun protocol(messages: String, output: ByteArrayOutputStream = ByteArrayOutputStream()) =
        QmpProtocol(ByteArrayInputStream(messages.toByteArray()), output)

    @Test fun negotiatesCapabilitiesAndIgnoresEvents() {
        val output = ByteArrayOutputStream()
        val client = protocol("""
            {"QMP":{"version":{}}}
            {"event":"RESUME"}
            {"return":{},"id":1}
            {"return":{"status":"running"},"id":2}
        """.trimIndent() + "\n", output)
        client.negotiate()
        assertEquals("running", (client.execute("query-status") as JsonObject)["status"]?.jsonPrimitive?.content)
        val commands = output.toString("UTF-8").lines().filter { it.isNotBlank() }
        assertEquals(2, commands.size)
        assertEquals(true, commands[0].contains("qmp_capabilities"))
    }

    @Test fun rejectsRepliesForAnotherCommand() {
        val client = protocol("{\"QMP\":{}}\n{\"return\":{},\"id\":99}\n")
        assertThrows(QmpException::class.java) { client.negotiate() }
    }

    @Test fun reportsQmpErrors() {
        val client = protocol("{\"QMP\":{}}\n{\"error\":{\"class\":\"CommandNotFound\",\"desc\":\"missing\"},\"id\":1}\n")
        val error = assertThrows(QmpException::class.java) { client.negotiate() }
        assertEquals(true, error.message!!.contains("CommandNotFound"))
    }

    @Test fun rejectsOversizedAndTruncatedMessages() {
        for (input in listOf("x".repeat(65537) + "\n", "{\"QMP\":{}}", "[]\n")) {
            assertThrows(QmpException::class.java) { protocol(input).negotiate() }
        }
    }

    @Test fun boundsUnsolicitedEventFlood() {
        val flood = "{\"QMP\":{}}\n" + "{\"event\":\"STOP\"}\n".repeat(129)
        assertThrows(QmpException::class.java) { protocol(flood).negotiate() }
    }

    @Test fun requiresNegotiationBeforeCommands() {
        assertThrows(QmpException::class.java) { protocol("").execute("quit") }
    }

    @Test fun rejectsMalformedUtf8AndBlankGreeting() {
        assertThrows(QmpException::class.java) {
            QmpProtocol(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28, 10)),
                ByteArrayOutputStream()).negotiate()
        }
        assertThrows(QmpException::class.java) { protocol("\n").negotiate() }
    }

    @Test fun boundsDeadlineInsidePartialMessages() {
        var now = 0L
        var readBytes = 0
        val input = object : InputStream() {
            override fun read(): Int {
                readBytes++
                now += 1_000_000_000L
                return 'x'.code
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                bytes[offset] = read().toByte()
                return 1
            }
        }
        assertThrows(QmpException::class.java) {
            QmpProtocol(input, ByteArrayOutputStream(), { now }).negotiate()
        }
        assertEquals(true, readBytes <= 5)
    }
}
