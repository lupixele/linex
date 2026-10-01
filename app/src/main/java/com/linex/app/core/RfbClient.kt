package com.linex.app.core

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Minimal authenticated, loopback-only RFB 3.8 client (RFC 6143).
 * Run on a worker thread; callbacks run there. Input methods never block the UI.
 * Each frame is an owned ARGB snapshot. A client is single-use, including after close().
 */
class RfbClient(
    private val port: Int,
    private val password: String,
    private val onFrame: (width: Int, height: Int, pixels: IntArray) -> Unit,
    private val onStatus: (String) -> Unit,
    targetFps: Int = 15
) : AutoCloseable {
    private val frameIntervalNanos = DesktopFrameRate.intervalNanos(targetFps)
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val socket = Socket()
    private val writeLock = Any()
    private val visibilityLock = Object()
    @Volatile private var updatesPaused = false
    private val reusableFrames = ArrayBlockingQueue<IntArray>(2)
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var ready = false
    @Volatile private var width = 0
    @Volatile private var height = 0
    private val inputs = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(128), { task -> Thread(task, "linex-display-input").apply { isDaemon = true } })

    fun run() {
        check(started.compareAndSet(false, true)) { "Display client already started" }
        if (closed.get()) return
        try {
            require(port in 1..65535)
            onStatus("Connecting to desktop")
            socket.connect(InetSocketAddress("127.0.0.1", port), 5000)
            if (closed.get()) return
            socket.tcpNoDelay = true
            socket.soTimeout = 10000
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 65536))
            output = DataOutputStream(socket.getOutputStream())
            val version = ByteArray(12).also(input::readFully).toString(Charsets.US_ASCII)
            if (version != "RFB 003.008\n") throw IOException("Desktop requires RFB 3.8; received ${version.trim()}")
            write { writeBytes("RFB 003.008\n") }
            val count = input.readUnsignedByte()
            if (count == 0) throw IOException("Desktop rejected connection: ${readText(input)}")
            val types = ByteArray(count).also(input::readFully)
            if (!types.contains(2.toByte())) throw IOException("Desktop does not offer password authentication")
            write { writeByte(2) }
            val challenge = ByteArray(16).also(input::readFully)
            write { write(challengeResponse(password, challenge)) }
            if (input.readInt() != 0) throw IOException("Desktop authentication failed: ${readText(input)}")
            write { writeByte(1) }
            width = input.readUnsignedShort()
            height = input.readUnsignedShort()
            checkSize(width, height)
            input.readFully(ByteArray(16)) // Explicit pixel format below replaces the server default.
            readText(input)
            write {
                writeByte(0); write(ByteArray(3))
                writeByte(32); writeByte(24); writeByte(0); writeByte(1)
                repeat(3) { writeShort(255) }
                writeByte(16); writeByte(8); writeByte(0); write(ByteArray(3))
                writeByte(2); writeByte(0); writeShort(3); writeInt(0); writeInt(1); writeInt(-223)
            }
            var pixels = IntArray(width * height)
            ready = true
            socket.soTimeout = 0 // An idle desktop legitimately has no updates.
            if (!awaitVisible()) return
            requestUpdate(false)
            var lastUpdateRequest = System.nanoTime()
            onStatus("Desktop connected")
            while (!closed.get()) {
                when (val type = input.readUnsignedByte()) {
                    0 -> {
                        input.readUnsignedByte()
                        val rectangles = input.readUnsignedShort()
                        var resized = false
                        repeat(rectangles) {
                            val x = input.readUnsignedShort(); val y = input.readUnsignedShort()
                            val w = input.readUnsignedShort(); val h = input.readUnsignedShort()
                            when (val encoding = input.readInt()) {
                                -223 -> {
                                    if (x != 0 || y != 0 || it != rectangles - 1) throw IOException("Invalid desktop resize rectangle")
                                    checkSize(w, h)
                                    width = w; height = h; pixels = IntArray(w * h); resized = true
                                }
                                0 -> {
                                    if (x + w > width || y + h > height) throw IOException("Desktop rectangle exceeds framebuffer")
                                    val row = ByteArray(w * 4)
                                    repeat(h) { dy ->
                                        input.readFully(row)
                                        repeat(w) { dx ->
                                            val offset = dx * 4
                                            pixels[(y + dy) * width + x + dx] = (0xff shl 24) or
                                                ((row[offset + 2].toInt() and 255) shl 16) or
                                                ((row[offset + 1].toInt() and 255) shl 8) or (row[offset].toInt() and 255)
                                        }
                                    }
                                }
                                1 -> {
                                    val sourceX = input.readUnsignedShort()
                                    val sourceY = input.readUnsignedShort()
                                    if (x + w > width || y + h > height ||
                                        sourceX + w > width || sourceY + h > height)
                                        throw IOException("Desktop CopyRect exceeds framebuffer")
                                    // System.arraycopy (via copyInto) handles horizontal overlap.
                                    // Copy bottom-up when moving down, so later source rows survive.
                                    val rows = if (y > sourceY) h - 1 downTo 0 else 0 until h
                                    for (row in rows) {
                                        val sourceOffset = (sourceY + row) * width + sourceX
                                        pixels.copyInto(pixels, (y + row) * width + x,
                                            sourceOffset, sourceOffset + w)
                                    }
                                }
                                else -> throw IOException("Unsupported desktop encoding $encoding")
                            }
                        }
                        if (rectangles > 0) {
                            var snapshot = reusableFrames.poll()
                            if (snapshot == null || snapshot.size != pixels.size) snapshot = IntArray(pixels.size)
                            pixels.copyInto(snapshot)
                            onFrame(width, height, snapshot)
                        }
                        // Keep only one framebuffer request in flight and cap the raw
                        // decoder/snapshot allocation rate. Do not hold writeLock while
                        // waiting: keyboard and pointer events must remain responsive.
                        var remaining = frameIntervalNanos - (System.nanoTime() - lastUpdateRequest)
                        while (remaining > 0 && !closed.get()) {
                            TimeUnit.NANOSECONDS.sleep(remaining)
                            remaining = frameIntervalNanos - (System.nanoTime() - lastUpdateRequest)
                        }
                        if (!awaitVisible()) break
                        requestUpdate(!resized)
                        lastUpdateRequest = System.nanoTime()
                    }
                    2 -> Unit // Bell
                    3 -> { input.readFully(ByteArray(3)); readText(input) }
                    else -> throw IOException("Unsupported desktop message $type")
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) {
                onStatus("Desktop disconnected: ${e.message ?: e.javaClass.simpleName}")
                throw e
            }
        } finally {
            close()
        }
    }

    private fun requestUpdate(incremental: Boolean) = write {
        writeByte(3); writeByte(if (incremental) 1 else 0)
        writeShort(0); writeShort(0); writeShort(width); writeShort(height)
    }

    /** Return an owned snapshot only after the consumer has finished copying it. */
    fun recycleFrame(pixels: IntArray) {
        if (!closed.get()) reusableFrames.offer(pixels)
    }

    /** A hidden surface needs no new frames. At most one already-requested update can arrive. */
    fun pauseUpdates(paused: Boolean) = synchronized(visibilityLock) {
        updatesPaused = paused
        if (!paused) visibilityLock.notifyAll()
    }

    private fun awaitVisible(): Boolean = synchronized(visibilityLock) {
        while (updatesPaused && !closed.get()) visibilityLock.wait()
        !closed.get()
    }

    fun pointer(x: Int, y: Int, buttons: Int) = enqueue {
        writeByte(5); writeByte(buttons and 255)
        writeShort(x.coerceIn(0, (width - 1).coerceAtLeast(0)))
        writeShort(y.coerceIn(0, (height - 1).coerceAtLeast(0)))
    }

    fun key(keysym: Int, down: Boolean) = enqueue {
        writeByte(4); writeByte(if (down) 1 else 0); writeShort(0); writeInt(keysym)
    }

    /** Queues one bounded IME commit atomically, rather than two queue entries per character. */
    fun text(value: String) {
        require(value.codePointCount(0, value.length) <= 4096) { "Desktop text is limited to 4096 characters per commit" }
        val symbols = value.codePoints().toArray()
        enqueue {
            for (code in symbols) {
                val symbol = when (code) {
                    10, 13 -> 0xff0d
                    9 -> 0xff09
                    else -> if (code <= 255) code else 0x01000000 or code
                }
                writeByte(4); writeByte(1); writeShort(0); writeInt(symbol)
                writeByte(4); writeByte(0); writeShort(0); writeInt(symbol)
            }
        }
    }

    fun deleteText(before: Int, after: Int) = enqueue {
        fun tap(symbol: Int) {
            writeByte(4); writeByte(1); writeShort(0); writeInt(symbol)
            writeByte(4); writeByte(0); writeShort(0); writeInt(symbol)
        }
        repeat(before.coerceIn(0, 100)) { tap(0xff08) }
        repeat(after.coerceIn(0, 100)) { tap(0xffff) }
    }

    private fun enqueue(action: DataOutputStream.() -> Unit) {
        if (!ready || closed.get()) return
        try {
            inputs.execute {
                try { write(action) } catch (e: IOException) {
                    if (!closed.get()) onStatus("Desktop input disconnected: ${e.message}")
                    close()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Never silently drop a release event and leave a remote key/button held.
            if (!closed.get()) onStatus("Desktop disconnected: input queue overflow")
            close()
        }
    }

    private fun write(action: DataOutputStream.() -> Unit) = synchronized(writeLock) {
        if (!closed.get()) output?.run { action(); flush() }
    }

    override fun close() {
        closed.set(true)
        synchronized(visibilityLock) { visibilityLock.notifyAll() }
        reusableFrames.clear()
        ready = false
        // Socket exists before connect starts, so cancellation also interrupts a racing connect.
        try { socket.close() } catch (_: IOException) { }
        inputs.shutdownNow()
    }

    companion object {
        private fun checkSize(width: Int, height: Int) {
            if (width !in 1..4096 || height !in 1..4096 || width.toLong() * height > 8_000_000)
                throw IOException("Unsupported desktop size ${width}x$height")
        }
        private fun readText(input: DataInputStream): String {
            val length = input.readInt()
            if (length !in 0..1_048_576) throw IOException("Invalid desktop text length")
            return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
        }
        internal fun challengeResponse(password: String, challenge: ByteArray): ByteArray {
            require(challenge.size == 16)
            val bytes = password.toByteArray(Charsets.ISO_8859_1)
            val key = ByteArray(8) { index ->
                Integer.reverse(if (index < bytes.size) bytes[index].toInt() and 255 else 0).ushr(24).toByte()
            }
            return Cipher.getInstance("DES/ECB/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES")); doFinal(challenge)
            }
        }
    }
}
