package com.linex.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeX11DiagnosticTailTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun preservesFatalErrorAfterProcessExit() {
        val file = directory.newFile("startup.log")
        file.writeText("Starting server\n\nFatal server error:\nCannot initialize keyboard\n")
        assertEquals(listOf("Fatal server error:", "Cannot initialize keyboard"),
            NativeX11DiagnosticTail.read(file, maxLines = 2))
    }

    @Test fun capsLargeOutputWithoutReturningPartialFirstLine() {
        val file = directory.newFile("startup.log")
        file.writeText("x".repeat(100_000) + "\nFatal error\n")
        assertEquals(listOf("Fatal error"), NativeX11DiagnosticTail.read(file, maxBytes = 64))
    }

    @Test fun newLaunchDoesNotReusePreviousFatalError() {
        val file = directory.newFile("startup.log")
        file.writeText("Previous fatal error\n")
        file.writeText("")
        assertTrue(NativeX11DiagnosticTail.read(file).isEmpty())
        assertTrue(NativeX11DiagnosticTail.read(java.io.File(file.parentFile, "missing")).isEmpty())
    }
}
