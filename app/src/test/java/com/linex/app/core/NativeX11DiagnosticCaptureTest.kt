package com.linex.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicReference

class NativeX11DiagnosticCaptureTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun floodedOutputRetainsFinalFatalErrorWithinDiskLimit() {
        val file = directory.newFile("display.log")
        val message = "x".repeat(2 * 1024 * 1024) + "\nFatal X11 error\n"
        val capture = NativeX11DiagnosticCapture(ByteArrayInputStream(message.toByteArray()), file)
        capture.start()
        assertTrue(capture.finish(5000))
        assertEquals(64 * 1024L, file.length())
        assertEquals(listOf("Fatal X11 error"), NativeX11DiagnosticTail.read(file))
    }

    @Test fun parentDrainsFatalOutputWhenChildWriterCloses() {
        val file = directory.newFile("display.log")
        val input = PipedInputStream()
        val output = PipedOutputStream(input)
        val capture = NativeX11DiagnosticCapture(input, file)
        capture.start()
        output.use { it.write("Child process fatal error\n".toByteArray()) }
        assertTrue(capture.finish(1000))
        assertEquals("Child process fatal error\n", file.readText())
    }

    @Test fun failedDiskWritesDoNotBreakPipeAndRetainFatalTailInMemory() {
        val input = PipedInputStream(8192)
        val output = PipedOutputStream(input)
        // Opening a directory as a file fails on every periodic and EOF flush.
        val capture = NativeX11DiagnosticCapture(input, directory.root, flushIntervalMs = 0)
        val writerFailure = AtomicReference<Throwable?>()
        capture.start()
        val writer = Thread {
            try {
                output.use {
                    val chunk = ByteArray(8192) { 'x'.code.toByte() }
                    repeat(256) { _ -> it.write(chunk) }
                    it.write("\nFinal native fatal error\n".toByteArray())
                }
            } catch (error: Throwable) { writerFailure.set(error) }
        }.apply { isDaemon = true; start() }
        writer.join(5000)
        assertTrue("Child writer must finish despite failed disk writes", !writer.isAlive)
        assertTrue(capture.finish(1000))
        assertNull("Diagnostic disk errors must not close the native output pipe", writerFailure.get())
        assertEquals(listOf("Final native fatal error"), capture.lines(maxLines = 1))
    }
}
