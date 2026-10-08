package com.linex.vm.console

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@RunWith(AndroidJUnit4::class)
class PrivateUnixRfbTransportTest {
    @Test fun closeWakesBlockedReadWithoutPeerSendingOrClosing() = fixture { root, id, listener, _ ->
        LocalServerSocket(listener.fileDescriptor).use { server ->
            PrivateUnixRfbTransport(root, id).use { transport ->
                transport.connect(1000)
                server.accept().use { peer ->
                    transport.setReadTimeout(0)
                    val started = CountDownLatch(1)
                    val executor = Executors.newSingleThreadExecutor { task ->
                        Thread(task, "console-close-proof").apply { isDaemon = true }
                    }
                    val reader = executor.submit<Int> {
                        started.countDown()
                        try { transport.inputStream.read() } catch (_: IOException) { -1 }
                    }
                    try {
                        assertTrue(started.await(1, TimeUnit.SECONDS))
                        assertThrows(TimeoutException::class.java) { reader.get(150, TimeUnit.MILLISECONDS) }
                        transport.close()
                        assertEquals(-1, reader.get(2, TimeUnit.SECONDS).toInt())
                        // The peer stayed open and silent throughout cancellation.
                        assertTrue(peer.fileDescriptor.valid())
                        transport.close()
                    } finally {
                        try { peer.shutdownOutput() } catch (_: IOException) { }
                        executor.shutdownNow()
                    }
                }
            }
        }
    }

    @Test fun acceptsAndroidManagedFilesDirectoryWithoutChangingItsMode() {
        val root = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        val originalMode = Os.lstat(root.path).st_mode
        val base = File(root, "vmc")
        val createdBase = base.mkdir()
        if (createdBase) Os.chmod(base.path, 448)
        val id = UUID.randomUUID().toString().replace("-", "")
        val session = File(base, id)
        check(session.mkdir())
        Os.chmod(session.path, 448)
        val endpoint = File(session, "c")
        try {
            LocalSocket().use { listener ->
                listener.bind(LocalSocketAddress(endpoint.canonicalPath, LocalSocketAddress.Namespace.FILESYSTEM))
                Os.chmod(endpoint.path, 384)
                LocalServerSocket(listener.fileDescriptor).use { server ->
                    PrivateUnixRfbTransport(root, id).use { transport ->
                        transport.connect(1000)
                        server.accept().use { peer ->
                            peer.outputStream.write(13)
                            assertEquals(13, transport.inputStream.read())
                        }
                    }
                }
            }
            assertEquals(originalMode, Os.lstat(root.path).st_mode)
        } finally {
            check(session.parentFile == base && session.name == id)
            session.deleteRecursively()
            if (createdBase) base.delete()
        }
    }

    private fun fixture(action: (File, String, LocalSocket, File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "ct" + UUID.randomUUID().toString().take(8)).canonicalFile
        val sessionId = UUID.randomUUID().toString().replace("-", "")
        val session = File(root, "vmc/$sessionId")
        check(session.mkdirs())
        listOf(root, File(root, "vmc"), session).forEach { Os.chmod(it.path, 448) }
        val endpoint = File(session, "c")
        try {
            LocalSocket().use { listener ->
                listener.bind(LocalSocketAddress(endpoint.path, LocalSocketAddress.Namespace.FILESYSTEM))
                Os.chmod(endpoint.path, 384)
                action(root, sessionId, listener, endpoint)
            }
        } finally {
            // Only the fresh UUID test directory is removed, never a real instance.
            check(root.parentFile == InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }

    @Test fun exchangesPrivateFilesystemBytesAndClosesOwnedConnection() = fixture { root, id, listener, _ ->
        LocalServerSocket(listener.fileDescriptor).use { server ->
            PrivateUnixRfbTransport(root, id).use { transport ->
                transport.connect(1000)
                server.accept().use { peer ->
                    assertEquals(Process.myUid(), peer.peerCredentials.uid)
                    transport.outputStream.write(42)
                    assertEquals(42, peer.inputStream.read())
                    peer.outputStream.write(91)
                    assertEquals(91, transport.inputStream.read())
                    transport.setReadTimeout(150)
                    val before = System.nanoTime()
                    assertThrows(IOException::class.java) { transport.inputStream.read() }
                    assertTrue(System.nanoTime() - before < 2_000_000_000L)
                    transport.close()
                    assertEquals(-1, peer.inputStream.read())
                }
            }
        }
    }

    @Test fun saturatedUnixBacklogDoesNotHangConnect() = fixture { root, id, listener, _ ->
        Os.listen(listener.fileDescriptor, 0)
        PrivateUnixRfbTransport(root, id).use { first ->
            first.connect(1000)
            PrivateUnixRfbTransport(root, id).use { blocked ->
                val before = System.nanoTime()
                assertThrows(IOException::class.java) { blocked.connect(150) }
                val elapsed = System.nanoTime() - before
                assertTrue("Unix connect exceeded deadline: $elapsed", elapsed < 2_000_000_000L)
                assertTrue("Listener was not saturated", elapsed >= 50_000_000L)
            }
        }
    }

    @Test fun rejectsLooseSocketPermissionsBeforeConnecting() = fixture { root, id, listener, endpoint ->
        Os.listen(listener.fileDescriptor, 1)
        Os.chmod(endpoint.path, 438)
        PrivateUnixRfbTransport(root, id).use { transport ->
            assertThrows(IOException::class.java) { transport.connect(150) }
        }
    }

    @Test fun rejectsSymlinkSessionAndForeignUid() = fixture { root, id, listener, _ ->
        Os.listen(listener.fileDescriptor, 1)
        val alias = "a".repeat(32).takeIf { it != id } ?: "b".repeat(32)
        Os.symlink(File(root, "vmc/$id").path, File(root, "vmc/$alias").path)
        PrivateUnixRfbTransport(root, alias).use { transport ->
            assertThrows(IOException::class.java) { transport.connect(150) }
        }
        PrivateUnixRfbTransport(root, id, Process.myUid() + 1).use { transport ->
            assertThrows(IllegalArgumentException::class.java) { transport.connect(150) }
        }
    }
}
