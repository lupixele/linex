package com.linex.app.core

import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Parent-owned pipe reader: retains only the last 64 KiB even if Xorg floods stderr. */
class NativeX11DiagnosticCapture(
    private val input: InputStream,
    private val file: File,
    private val flushIntervalMs: Long = 250
) {
    private val tail = ByteArray(64 * 1024)
    private var size = 0
    private var next = 0
    private val finished = CountDownLatch(1)
    init { require(flushIntervalMs >= 0) }

    fun start() {
        Thread({
            try {
                input.use { stream ->
                    val buffer = ByteArray(8192)
                    var lastFlush = System.nanoTime()
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        append(buffer, count)
                        val now = System.nanoTime()
                        if (now - lastFlush >= TimeUnit.MILLISECONDS.toNanos(flushIntervalMs)) {
                            persistIfPossible()
                            lastFlush = now
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                // Closing the pipe during teardown interrupts a blocked reader.
            } finally {
                persistIfPossible()
                finished.countDown()
            }
        }, "Linex-X11-diagnostics").apply { isDaemon = true; start() }
    }

    private fun append(bytes: ByteArray, count: Int) = synchronized(this) {
        val first = minOf(count, tail.size - next)
        bytes.copyInto(tail, next, 0, first)
        if (first < count) bytes.copyInto(tail, 0, first, count)
        next = (next + count) % tail.size
        size = minOf(size + count, tail.size)
    }

    private fun snapshotBytes(): ByteArray = synchronized(this) {
        val start = (next - size + tail.size) % tail.size
        ByteArray(size).also { output ->
            val first = minOf(size, tail.size - start)
            tail.copyInto(output, 0, start, start + first)
            if (first < size) tail.copyInto(output, first, 0, size - first)
        }
    }

    private fun persistIfPossible() {
        try {
            file.writeBytes(snapshotBytes())
        } catch (_: java.io.IOException) {
            // Diagnostics are optional. Keep draining the pipe into bounded RAM
            // even when disk writes fail, otherwise Xorg can die from SIGPIPE.
        } catch (_: SecurityException) {
            // Revoked file access likewise must not terminate the pipe reader.
        }
    }

    /** Available even when host storage is full or the log file cannot be opened. */
    fun lines(maxLines: Int = 40): List<String> {
        require(maxLines > 0)
        return snapshotBytes().toString(Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() }.toList().takeLast(maxLines)
    }

    /** Call after stopping the writer process, so queued fatal output reaches disk. */
    fun finish(timeoutMs: Long = 1000): Boolean {
        if (finished.await(timeoutMs, TimeUnit.MILLISECONDS)) return true
        runCatching { input.close() }
        return finished.await(100, TimeUnit.MILLISECONDS)
    }
}
