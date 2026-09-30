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
    @Test fun pausedDisplayStopsRequestsAndResumesWithoutDisconnecting() {
        ServerSocket(0).use { server ->
            val error = AtomicReference<Throwable>()
            val client = RfbClient(server.localPort, "password", { _, _, _ -> }, {})
            val worker = thread { try { client.run() } catch (t: Throwable) { error.set(t) } }
            try {
                server.accept().use { socket ->
                    val (input, output) = handshake(socket)
                    input.readFully(ByteArray(42))
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
                    input.readFully(ByteArray(42))
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
                    input.readFully(ByteArray(42))
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
                val setup = ByteArray(42).also(input::readFully)
                assertEquals(0, setup[0].toInt())
                assertEquals(32, setup[4].toInt())
                assertEquals(2, setup[20].toInt())
                assertEquals(3, setup[32].toInt())
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
                input.readFully(ByteArray(42))
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
                input.readFully(ByteArray(42))
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
                input.readFully(ByteArray(42))
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
