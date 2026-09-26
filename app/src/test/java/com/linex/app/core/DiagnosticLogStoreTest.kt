package com.linex.app.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DiagnosticLogStoreTest {
    @Test fun restartRestoresInstanceIdentityAndMultilineErrors() = withStore { directory ->
        val first = LogEntry("2026-09-26 12:00:00.000", "a", "Download", "failed\nconnection reset")
        DiagnosticLogStore(directory).append(first)
        DiagnosticLogStore(directory).append(LogEntry("later", "b", "Download", "other instance"))
        val restored = DiagnosticLogStore(directory).load()
        assertEquals(listOf(first), restored.filter { it.instanceId == "a" })
    }

    @Test fun clearSurvivesRestartAndKeepsOtherInstances() = withStore { directory ->
        val store = DiagnosticLogStore(directory)
        store.append(LogEntry("now", "a", "Test", "remove"))
        store.append(LogEntry("now", "b", "Test", "keep"))
        store.clear("a")
        assertEquals(listOf("b"), DiagnosticLogStore(directory).load().map { it.instanceId })
        store.clear(null)
        assertTrue(DiagnosticLogStore(directory).load().isEmpty())
    }

    @Test fun legacyInstanceLogDoesNotBecomeGlobalOrDuplicate() = withStore { directory ->
        directory.resolve("instance_a.log").writeText("[12:00:00.000] [Inst:a] [Download] failed\n")
        directory.resolve("linex_global.log").writeText("[12:00:00.000] [Inst:a] [Download] failed\n[12:00:00.001] [App] ready\n")
        val restored = DiagnosticLogStore(directory).load()
        assertEquals(1, restored.count { it.instanceId == "a" })
        assertEquals("ready", restored.single { it.instanceId == null }.message)
    }

    @Test fun historyIsBoundedAndUnsafeIdsCannotEscapeDirectory() = withStore { directory ->
        val store = DiagnosticLogStore(directory, maxEntries = 3, maxBytes = 180)
        repeat(20) { store.append(LogEntry("now", "../../escape", "Test", "entry $it")) }
        val restored = store.load()
        assertTrue(restored.size <= 3)
        assertEquals("entry 19", restored.last().message)
        assertTrue(directory.listFiles()!!.all { it.canonicalFile.parentFile == directory.canonicalFile })
    }

    private fun withStore(block: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("linex-logs-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
