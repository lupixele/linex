package com.linex.app.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class VmGuestControlTest {
    private val token = "abcd".repeat(8)
    @Test fun encodesOneBoundedLineWithExactGuestFields() {
        val wire = VmGuestControl.launch(token, "Aa12_-Bb", 1280, 720, 60, 1_800_000_000)
        assertEquals(1, wire.count { it == '\n' })
        assertTrue(wire.toByteArray().size <= 512)
        val parsed = Json.parseToJsonElement(wire).jsonObject
        assertEquals(setOf("command", "session", "password", "width", "height", "fps", "epochSeconds"), parsed.keys)
        assertEquals("1800000000", parsed["epochSeconds"]!!.jsonPrimitive.content)
        assertEquals("Aa12_-Bb", parsed["password"]!!.jsonPrimitive.content)
        assertEquals(token, Json.parseToJsonElement(VmGuestControl.stop(token)).jsonObject["session"]!!.jsonPrimitive.content)
    }
    @Test fun rejectsCredentialsAndGeometryThatCouldBreakGuestProtocol() {
        for (password in listOf("short", "Aa12\n_Bb", "abcdefghi", "abcdef\"x")) {
            assertThrows(IllegalArgumentException::class.java) { VmGuestControl.launch(token, password, 1280, 720, 60) }
        }
        for ((w, h) in listOf(639 to 480, 1280 to 479, 4097 to 720, 4096 to 4096)) {
            assertThrows(IllegalArgumentException::class.java) { VmGuestControl.launch(token, "12345678", w, h, 60) }
        }
        assertThrows(IllegalArgumentException::class.java) { VmGuestControl.launch(token, "12345678", 1280, 720, 1000) }
        assertThrows(IllegalArgumentException::class.java) { VmGuestControl.stop("../session") }
        for (epoch in listOf(0L, 1_699_999_999L, 4_102_444_801L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { VmGuestControl.launch(token, "12345678", 1280, 720, 60, epoch) }
        }
    }
}
