package com.linex.vm

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.os.Looper
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable
import java.io.File

/** Blocking private control transport. Call off the Android main thread. */
class QmpClient private constructor(private val socket: LocalSocket) : Closeable {
    private val protocol = QmpProtocol(socket.inputStream, socket.outputStream)

    fun negotiate() = protocol.negotiate()
    fun pause() { protocol.execute("stop") }
    fun resume() { protocol.execute("cont") }
    fun status(): String {
        val status = ((protocol.execute("query-status") as? JsonObject)?.get("status") as? JsonPrimitive)
        if (status == null || !status.isString) throw QmpException("Missing QMP machine status")
        return status.content
    }
    fun powerDown() { protocol.execute("system_powerdown") }
    fun quit() { protocol.execute("quit") }
    override fun close() = socket.close()

    companion object {
        fun connect(path: File, privateRoot: File): QmpClient {
            check(Looper.myLooper() != Looper.getMainLooper()) { "QMP must run off the main thread" }
            require(path.canonicalFile.toPath().startsWith(privateRoot.canonicalFile.toPath())) {
                "QMP socket is outside private storage"
            }
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(path.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                // LocalSocket creates its native descriptor lazily on connect.
                // Options cannot be applied until that descriptor exists.
                socket.soTimeout = 2000
                require(socket.peerCredentials.uid == Process.myUid()) { "Unexpected QMP peer UID" }
                return QmpClient(socket)
            } catch (error: Throwable) {
                socket.close()
                throw error
            }
        }
    }
}
