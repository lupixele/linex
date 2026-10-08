package com.linex.vm.console

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Only the fixed, service-generated session path is accepted; never a caller supplied endpoint. */
class PrivateUnixRfbTransport(
    private val filesDirectory: File,
    private val sessionId: String,
    private val expectedPeerUid: Int = Process.myUid()
) : RfbTransport {
    private val socket = LocalSocket()
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()

    override fun connect(timeoutMillis: Int) {
        check(started.compareAndSet(false, true)) { "Transport already started" }
        require(timeoutMillis in 1..60_000)
        check(!closed.get()) { "Transport closed" }
        try {
            val path = validateEndpoint()
            // Creating the public stream initializes LocalSocket's owned descriptor.
            // setSoTimeout sets SO_SNDTIMEO as well as SO_RCVTIMEO; Linux applies
            // the former to connect's AF_UNIX backlog wait. The two-argument
            // LocalSocket.connect overload is unsupported, even on Android 13.
            socket.outputStream
            socket.soTimeout = timeoutMillis
            socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            if (closed.get()) throw IOException("Transport closed")
            if (socket.peerCredentials.uid != expectedPeerUid) throw IOException("Console peer UID mismatch")
            validateEndpoint()
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun validateEndpoint(): String {
        require(expectedPeerUid == Process.myUid()) { "Console must share app UID" }
        require(sessionId.matches(Regex("[0-9a-f]{32}"))) { "Invalid console session" }
        val suppliedRoot = filesDirectory.absoluteFile
        val rootStat = Os.lstat(suppliedRoot.path)
        if (!OsConstants.S_ISDIR(rootStat.st_mode) || rootStat.st_uid != expectedPeerUid)
            throw IOException("Console files directory is unsafe")
        // Android's /data/user/0 ancestor can alias /data/data. Trust only the
        // caller's app files root; reject aliases in the private child chain.
        val root = suppliedRoot.canonicalFile
        val base = File(root, "vmc")
        val session = File(base, sessionId)
        val endpoint = File(session, "c")
        val path = endpoint.path
        require(path.toByteArray(Charsets.UTF_8).size <= 107 && '-' !in path && ',' !in path) {
            "Unsupported console path"
        }
        for (directory in listOf(base, session)) {
            val stat = Os.lstat(directory.path)
            if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != expectedPeerUid ||
                stat.st_mode and 511 != 448 || directory.canonicalFile != directory)
                throw IOException("Console directory must be private and owned")
        }
        val stat = Os.lstat(path)
        if (!OsConstants.S_ISSOCK(stat.st_mode) || stat.st_uid != expectedPeerUid ||
            stat.st_mode and 511 != 384) throw IOException("Console socket must be private and owned")
        return path
    }

    override val inputStream: InputStream get() = socket.inputStream
    override val outputStream: OutputStream get() = socket.outputStream
    override fun setReadTimeout(timeoutMillis: Int) {
        require(timeoutMillis in 0..60_000)
        socket.soTimeout = timeoutMillis
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // LocalSocket.close only closes the descriptor. A blocking native read
        // can keep the Unix socket alive until data arrives from the guest.
        // Shutdown wakes both the reader and a blocked input-event writer first.
        try { socket.shutdownInput() } catch (_: IOException) { }
        try { socket.shutdownOutput() } catch (_: IOException) { }
        socket.close()
    }
}
