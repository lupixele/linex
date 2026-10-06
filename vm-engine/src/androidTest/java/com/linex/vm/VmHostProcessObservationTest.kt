package com.linex.vm

import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A real host child in the test harness, never in the managed VM/JNI process. */
class VmHostProcessObservationTest {
    @Test fun pidStatFallbackDetectsAndReapsKnownInstrumentationChild() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var child: java.lang.Process? = null
        var before: VmHostProcessSnapshot? = null
        var during: VmHostProcessSnapshot? = null
        var after: VmHostProcessSnapshot? = null
        var failure: Throwable? = null
        var passed = false
        val dumpabilityChecks = mutableListOf<Int?>()
        val sampler = VmHostProcessStats(
            readChildren = { throw IOException("Positive control forces the optional children capability unavailable") },
            readDumpability = {
                Os.prctl(OsConstants.PR_GET_DUMPABLE, 0L, 0L, 0L, 0L).also {
                    if (dumpabilityChecks.size < 64) dumpabilityChecks += it
                }
            },
        )
        try {
            before = awaitCount(sampler, 0)
            // Fixed system executable and fixed arguments; no user command or
            // shell. NativeVm is not loaded in this instrumentation process.
            child = ProcessBuilder("/system/bin/toybox", "sleep", "5").start()
            assertTrue("Known instrumentation child exited before observation", child.isAlive)
            during = awaitCount(sampler, 1)
            assertTrue("Known child must still be alive at the positive observation", child.isAlive)
            child.destroy()
            if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly()
            assertTrue("Known child could not be reaped", child.waitFor(2, TimeUnit.SECONDS))
            after = awaitCount(sampler, 0)
            passed = true
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanupComplete = child?.let { known ->
                runCatching {
                    if (known.isAlive) known.destroyForcibly()
                    known.waitFor(2, TimeUnit.SECONDS)
                }.getOrDefault(false)
            } ?: true
            child?.let { known ->
                runCatching { known.outputStream.close() }
                runCatching { known.inputStream.close() }
                runCatching { known.errorStream.close() }
            }
            val evidence = buildJsonObject {
                put("scope", "instrumentation_process_positive_control_outside_vm")
                put("pid", Process.myPid()); put("androidSdk", android.os.Build.VERSION.SDK_INT)
                put("forcedMissingThreadChildren", true)
                put("fixedCommand", "/system/bin/toybox sleep 5")
                put("passed", passed && cleanupComplete); put("cleanupComplete", cleanupComplete)
                put("dumpabilityChecks", JsonArray(dumpabilityChecks.map { JsonPrimitive(it) }))
                for ((phase, observation) in listOf("before" to before, "during" to during, "after" to after)) {
                    observation?.let {
                        put(phase, buildJsonObject {
                            put("method", it.method.wireValue); put("complete", it.complete)
                            put("children", it.childCount); put("threads", it.threadCount)
                            put("detail", it.detail)
                        })
                    }
                }
                failure?.let { put("failureClass", it.javaClass.name.take(160)) }
            }
            try {
                File(context.filesDir, "vm-proof-host-control.json").writeText(evidence.toString())
            } catch (writeError: Throwable) {
                failure?.addSuppressed(writeError) ?: throw writeError
            }
            if (!cleanupComplete && failure == null) throw AssertionError("Known instrumentation child cleanup failed")
        }
    }

    private fun awaitCount(sampler: VmHostProcessStats, expected: Int): VmHostProcessSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var last: VmHostProcessSnapshot
        do {
            last = sampler.sample()
            assertEquals(VmHostObservationMethod.PROC_PID_STAT, last.method)
            if (last.complete && last.childCount == expected) return last
            Thread.sleep(50)
        } while (System.nanoTime() < deadline)
        throw AssertionError("Expected $expected verified host children: $last")
    }
}
