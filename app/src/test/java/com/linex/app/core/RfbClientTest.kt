package com.linex.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class RfbClientTest {
    @Test fun copyRectPreservesOverlappingScrollsAndOwnedSnapshots() {
        // Right, left, down and up cover both overlap directions on each axis.
        val moves = listOf(intArrayOf(1, 0, 2, 3, 0, 0), intArrayOf(0, 0, 2, 3, 1, 0),
            intArrayOf(0, 1, 3, 2, 0, 0), intArrayOf(0, 0, 3, 2, 0, 1))
        for (move in moves) {
            withCopyServer { input, output, frames, _ ->
                val original = IntArray(9) { 0xff000000.toInt() or (it + 1) }
                output.writeByte(0); output.writeByte(0); output.writeShort(1)
                rectangle(output, 0, 0, 3, 3, 0)
                original.forEach { output.writeInt(Integer.reverseBytes(it)) }
                val owned = frames.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("No initial frame")
                input.readFully(ByteArray(10))
                output.writeByte(0); output.writeByte(0); output.writeShort(1)
                rectangle(output, move[0], move[1], move[2], move[3], 1)
                output.writeShort(move[4]); output.writeShort(move[5])
                val copied = frames.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("CopyRect was not decoded")
                val expected = original.copyOf()
                for (row in 0 until move[3]) for (column in 0 until move[2])
                    expected[(move[1] + row) * 3 + move[0] + column] = original[(move[5] + row) * 3 + move[4] + column]
                assertArrayEquals(expected, copied)
                assertArrayEquals("Consumer-owned frame must stay immutable", original, owned)
            }
        }
    }

    @Test fun copyRectPublishesOnlyAfterEveryRectangleArrives() {
        withCopyServer { _, output, frames, _ ->
            output.writeByte(0); output.writeByte(0); output.writeShort(2)
            rectangle(output, 0, 0, 1, 1, 0); output.writeInt(0x01000000)
            assertNull("Partial update must not become visible", frames.poll(150, TimeUnit.MILLISECONDS))
            rectangle(output, 1, 0, 1, 1, 1); output.writeShort(0); output.writeShort(0)
            val result = frames.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("No complete frame")
            assertEquals(0xff000001.toInt(), result[0]); assertEquals(result[0], result[1])
            assertNull(frames.poll(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun copyRectRejectsOutOfBoundsSourceAndDestination() {
        for (sourceInvalid in listOf(true, false)) {
            withCopyServer { _, output, frames, error ->
                output.writeByte(0); output.writeByte(0); output.writeShort(1)
                rectangle(output, if (sourceInvalid) 0 else 2, 0, 2, 1, 1)
                output.writeShort(if (sourceInvalid) 2 else 0); output.writeShort(0)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (error.get() == null && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue("Invalid CopyRect bounds must be rejected", error.get()?.message?.contains("exceeds framebuffer") == true)
                assertTrue(frames.isEmpty())
            }
        }
    }

    private fun rectangle(out: DataOutputStream, x: Int, y: Int, w: Int, h: Int, encoding: Int) {
        out.writeShort(x); out.writeShort(y); out.writeShort(w); out.writeShort(h); out.writeInt(encoding)
    }

    private fun withCopyServer(block: (DataInputStream, DataOutputStream,
        java.util.concurrent.LinkedBlockingQueue<IntArray>, AtomicReference<Throwable>) -> Unit) {
        ServerSocket(0).use { server ->
            val frames = java.util.concurrent.LinkedBlockingQueue<IntArray>()
            val error = AtomicReference<Throwable>()
            val client = RfbClient(server.localPort, "password", { _, _, pixels -> frames.add(pixels) }, {})
            val worker = thread { try { client.run() } catch (t: Throwable) { error.set(t) } }
            try {
                server.accept().use { socket ->
                    val (input, output) = handshake(socket, 3, 3)
                    input.readFully(ByteArray(20)) // SetPixelFormat
                    assertEquals(2, input.readUnsignedByte()); input.readUnsignedByte()
                    repeat(input.readUnsignedShort()) { input.readInt() }
                    input.readFully(ByteArray(10))
                    block(input, output, frames, error)
                }
            } finally { client.close(); worker.join(2000) }
        }
    }

    @Test fun pausedDisplayStopsRequestsAndResumesWithoutDisconnecting() {
        ServerSocket(0).use { server ->
            val error = AtomicReference<Throwable>()
            val client = RfbClient(server.localPort, "password", { _, _, _ -> }, {})
            val worker = thread { try { client.run() } catch (t: Throwable) { error.set(t) } }
            try {
                server.accept().use { socket ->
                    val (input, output) = handshake(socket)
                    input.readFully(ByteArray(46))
                    client.pauseUpdates(true)
                    output.writeByte(0); output.writeByte(0); output.writeShort(0)
                    socket.soTimeout = 200
                    try { input.readUnsignedByte(); fail("Hidden display requested a frame") }
                    catch (_: java.net.SocketTimeoutException) { }
                    client.pauseUpdates(false)
                    socket.soTimeout = 3000
                    assertEquals(3, input.readUnsignedByte())
                    input.readFully(ByteArray(9))
                    client.pauseUpdates(true)
                    output.writeByte(0); output.writeByte(0); output.writeShort(0)
                    client.close()
                    worker.join(2000)
                    assertFalse("Closing must unblock a paused reader", worker.isAlive)
                    assertNull(error.get())
                }
            } finally { client.close(); worker.join(2000) }
        }
    }

    @Test fun snapshotsAreReusedOnlyAfterConsumerReturnsThem() {
        ServerSocket(0).use { server ->
            val frames = java.util.concurrent.LinkedBlockingQueue<IntArray>()
            val client = RfbClient(server.localPort, "password", { _, _, pixels -> frames.add(pixels) }, {})
            val worker = thread { runCatching { client.run() } }
            try {
                server.accept().use { socket ->
                    val (input, output) = handshake(socket)
                    input.readFully(ByteArray(46))
                    fun frame(red: Int): IntArray {
                        output.writeByte(0); output.writeByte(0); output.writeShort(1)
                        output.writeShort(0); output.writeShort(0); output.writeShort(2); output.writeShort(1); output.writeInt(0)
                        repeat(2) { output.write(byteArrayOf(0, 0, red.toByte(), 0)) }
                        return frames.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("No frame")
                    }
                    val first = frame(1)
                    input.readFully(ByteArray(10))
                    val second = frame(2)
                    assertNotSame(first, second)
                    assertEquals(0xff010000.toInt(), first[0])
                    client.recycleFrame(first)
                    input.readFully(ByteArray(10))
                    val third = frame(3)
                    assertSame("Released snapshot should avoid another full-frame allocation", first, third)
                    assertEquals(0xff030000.toInt(), third[0])
                    assertEquals(0xff020000.toInt(), second[0])
                }
            } finally { client.close(); worker.join(2000) }
        }
    }

    @Test fun emptyUpdatesArePacedWithoutPublishingFrames() {
        ServerSocket(0).use { server ->
            val error = AtomicReference<Throwable>()
            val client = RfbClient(server.localPort, "password", { _, _, _ -> fail("Empty updates must not allocate a frame") }, {})
            val worker = thread { try { client.run() } catch (t: Throwable) { error.set(t) } }
            try {
                server.accept().use { socket ->
                    val (input, output) = handshake(socket)
                    input.readFully(ByteArray(46))
                    // An immediately responding server must not create a busy polling loop.
                    output.writeByte(0); output.writeByte(0); output.writeShort(0)
                    input.readFully(ByteArray(10))
                    val start = System.nanoTime()
                    repeat(3) {
                        output.writeByte(0); output.writeByte(0); output.writeShort(0)
                        input.readFully(ByteArray(10))
                    }
                    assertTrue("Updates were requested faster than the 15 fps budget",
                        System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(150))
                    client.close()
                }
            } finally {
                client.close(); worker.join(2000)
            }
            assertFalse(worker.isAlive)
            assertNull(error.get())
        }
    }

    @Test fun authenticationMatchesIndependentDesVector() {
        // Independently calculated with .NET DES ECB and manually reversed ASCII password bytes.
        val expected = "b866924125c8eebb9debc1db61c538e2".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(expected, RfbClient.challengeResponse("password", ByteArray(16) { it.toByte() }))
    }
    private fun handshake(socket: Socket, width: Int = 2, height: Int = 1): Pair<DataInputStream, DataOutputStream> {
        socket.soTimeout = 3000
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        output.writeBytes("RFB 003.008\n")
        assertEquals("RFB 003.008\n", ByteArray(12).also(input::readFully).toString(Charsets.US_ASCII))
        output.write(byteArrayOf(2, 1, 2))
        assertEquals(2, input.readUnsignedByte())
        val challenge = ByteArray(16) { it.toByte() }
        output.write(challenge)
        assertArrayEquals(RfbClient.challengeResponse("password", challenge), ByteArray(16).also(input::readFully))
        output.writeInt(0)
        assertEquals(1, input.readUnsignedByte())
        output.writeShort(width); output.writeShort(height); output.write(ByteArray(16)); output.writeInt(0)
        return input to output
    }

    @Test fun authenticatesRendersAndSendsInput() {
        ServerSocket(0).use { server ->
            val frames = CountDownLatch(1)
            val frame = AtomicReference<IntArray>()
            val error = AtomicReference<Throwable>()
            val client = RfbClient(server.localPort, "password", { w, h, pixels ->
                assertEquals(2, w); assertEquals(1, h); frame.set(pixels); frames.countDown()
            }, {})
            val worker = thread { try { client.run() } catch (t: Throwable) { error.set(t) } }
            server.accept().use { socket ->
                val (input, output) = handshake(socket)
                val setup = ByteArray(46).also(input::readFully)
                assertEquals(0, setup[0].toInt())
                assertEquals(32, setup[4].toInt())
                assertEquals(2, setup[20].toInt())
                assertEquals(3, setup[23].toInt())
                val encodings = java.nio.ByteBuffer.wrap(setup)
                assertEquals(0, encodings.getInt(24))
                assertEquals(1, encodings.getInt(28))
                assertEquals(-223, encodings.getInt(32))
                assertEquals(3, setup[36].toInt())
                output.writeByte(0); output.writeByte(0); output.writeShort(1)
                output.writeShort(0); output.writeShort(0); output.writeShort(2); output.writeShort(1); output.writeInt(0)
                output.write(byteArrayOf(0, 0, -1, 0, 0, -1, 0, 0))
                assertTrue(frames.await(3, TimeUnit.SECONDS))
                assertArrayEquals(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt()), frame.get())
                input.readFully(ByteArray(10))
                client.pointer(100, -1, 1)
                assertEquals(5, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte())
                assertEquals(1, input.readUnsignedShort()); assertEquals(0, input.readUnsignedShort())
                client.key(0xff0d, true)
                assertEquals(4, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte())
                assertEquals(0, input.readUnsignedShort()); assertEquals(0xff0d, input.readInt())
                client.close(); worker.join(2000)
                assertFalse(worker.isAlive)
                assertNull(error.get())
            }
        }
    }

    @Test fun longTextCommitIsOneBatchAndPreservesUnicode() {
        ServerSocket(0).use { server ->
            val connected = CountDownLatch(1)
            val client = RfbClient(server.localPort, "password", { _, _, _ -> }, {
                if (it == "Desktop connected") connected.countDown()
            })
            val worker = thread { runCatching { client.run() } }
            server.accept().use { socket ->
                val (input, _) = handshake(socket)
                input.readFully(ByteArray(46))
                assertTrue(connected.await(3, TimeUnit.SECONDS))
                client.text("a".repeat(256) + "\n\t\uD83D\uDE00")
                val expected = List(256) { 97 } + listOf(0xff0d, 0xff09, 0x0101f600)
                for (symbol in expected) {
                    for (down in listOf(1, 0)) {
                        assertEquals(4, input.readUnsignedByte()); assertEquals(down, input.readUnsignedByte())
                        assertEquals(0, input.readUnsignedShort()); assertEquals(symbol, input.readInt())
                    }
                }
                client.close(); worker.join(2000); assertFalse(worker.isAlive)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedTextCommitIsRejectedBeforeQueueing() {
        RfbClient(1, "password", { _, _, _ -> }, {}).use { it.text("a".repeat(4097)) }
    }

    @Test fun refusesUnauthenticatedServer() {
        ServerSocket(0).use { server ->
            val status = AtomicReference("")
            val client = RfbClient(server.localPort, "password", { _, _, _ -> fail() }, { status.set(it) })
            val worker = thread { runCatching { client.run() } }
            server.accept().use { socket ->
                val input = DataInputStream(socket.getInputStream()); val out = DataOutputStream(socket.getOutputStream())
                out.writeBytes("RFB 003.008\n"); input.readFully(ByteArray(12)); out.write(byteArrayOf(1, 1))
                worker.join(2000)
                assertFalse(worker.isAlive)
                assertTrue(status.get().contains("does not offer password"))
            }
        }
    }

    @Test fun rejectsOversizedFramebufferBeforeAllocation() {
        ServerSocket(0).use { server ->
            val status = AtomicReference("")
            val client = RfbClient(server.localPort, "password", { _, _, _ -> fail() }, { status.set(it) })
            val worker = thread { runCatching { client.run() } }
            server.accept().use { handshake(it, 4096, 4096) }
            worker.join(2000)
            assertFalse(worker.isAlive)
            assertTrue(status.get().contains("Unsupported desktop size"))
        }
    }

    @Test fun rejectsRectangleOutsideFramebuffer() {
        ServerSocket(0).use { server ->
            val status = AtomicReference("")
            val client = RfbClient(server.localPort, "password", { _, _, _ -> fail() }, { status.set(it) })
            val worker = thread { runCatching { client.run() } }
            server.accept().use { socket ->
                val (input, output) = handshake(socket)
                input.readFully(ByteArray(46))
                output.writeByte(0); output.writeByte(0); output.writeShort(1)
                output.writeShort(1); output.writeShort(0); output.writeShort(2); output.writeShort(1); output.writeInt(0)
                worker.join(2000)
                assertFalse(worker.isAlive)
                assertTrue(status.get().contains("exceeds framebuffer"))
            }
        }
    }

    @Test fun requestsCompleteFrameAfterDesktopResize() {
        ServerSocket(0).use { server ->
            val resized = CountDownLatch(1)
            val client = RfbClient(server.localPort, "password", { w, h, pixels ->
                assertEquals(3, w); assertEquals(2, h); assertEquals(6, pixels.size); resized.countDown()
            }, {})
            val worker = thread { client.run() }
            server.accept().use { socket ->
                val (input, output) = handshake(socket)
                input.readFully(ByteArray(46))
                output.writeByte(0); output.writeByte(0); output.writeShort(1)
                output.writeShort(0); output.writeShort(0); output.writeShort(3); output.writeShort(2); output.writeInt(-223)
                assertTrue(resized.await(3, TimeUnit.SECONDS))
                assertEquals(3, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
                assertEquals(0, input.readInt()); assertEquals(3, input.readUnsignedShort()); assertEquals(2, input.readUnsignedShort())
                client.close(); worker.join(2000); assertFalse(worker.isAlive)
            }
        }
    }

    @Test fun closeInterruptsHandshakeRead() {
        ServerSocket(0).use { server ->
            val client = RfbClient(server.localPort, "password", { _, _, _ -> fail() }, {})
            val worker = thread { client.run() }
            server.accept().use {
                client.close(); worker.join(2000)
                assertFalse(worker.isAlive)
            }
        }
    }

    @Test fun closeBeforeRunPreventsConnection() {
        val client = RfbClient(1, "password", { _, _, _ -> fail() }, { fail() })
        client.close(); client.run()
    }
}
