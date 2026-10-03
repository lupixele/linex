package com.linex.vm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

class QmpException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Serialized command/reply stream. The transport must also enforce read timeouts. */
internal class QmpProtocol(
    input: InputStream, private val output: OutputStream,
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    private val input = BufferedInputStream(input)
    private var negotiated = false
    private var commandId = 0L

    @Synchronized fun negotiate() {
        if (negotiated) throw QmpException("QMP capabilities already negotiated")
        if (readMessage(deadline())["QMP"] !is JsonObject) throw QmpException("Invalid QMP greeting")
        request("qmp_capabilities")
        negotiated = true
    }

    @Synchronized fun execute(command: String): JsonElement {
        if (!negotiated) throw QmpException("QMP capabilities are not negotiated")
        return request(command)
    }

    private fun request(command: String): JsonElement {
        require(command.matches(Regex("[a-z][a-z0-9_-]{0,63}"))) { "Invalid QMP command" }
        val id = ++commandId
        val encoded = buildJsonObject { put("execute", command); put("id", id) }
        output.write((encoded.toString() + "\r\n").toByteArray(Charsets.UTF_8))
        output.flush()
        val deadline = deadline()
        repeat(128) {
            val message = readMessage(deadline)
            if (message["event"] is JsonPrimitive) return@repeat
            if ((message["id"] as? JsonPrimitive)?.longOrNull != id) {
                throw QmpException("QMP response has an unexpected command ID")
            }
            val error = message["error"] as? JsonObject
            if (error != null) {
                val errorClass = (error["class"] as? JsonPrimitive)?.contentOrNull?.take(128)
                throw QmpException("QMP command failed: ${errorClass ?: "unknown error"}")
            }
            return message["return"] ?: throw QmpException("QMP response has no result")
        }
        throw QmpException("Too many unsolicited QMP events")
    }

    private fun deadline() = monotonicNanos() + TimeUnit.SECONDS.toNanos(5)

    private fun readMessage(deadline: Long): JsonObject {
        val bytes = ByteArrayOutputStream()
        while (true) {
            if (monotonicNanos() >= deadline) throw QmpException("QMP response deadline exceeded")
            val next = input.read()
            if (next < 0) throw QmpException("QMP connection ended before a complete message")
            if (next == 10) break
            if (bytes.size() >= 65536) throw QmpException("QMP message exceeds 64KiB")
            bytes.write(next)
        }
        try {
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            return Json.parseToJsonElement(text) as? JsonObject
                ?: throw QmpException("QMP message must be an object")
        } catch (error: QmpException) {
            throw error
        } catch (error: Exception) {
            throw QmpException("Malformed QMP message", error)
        }
    }
}
