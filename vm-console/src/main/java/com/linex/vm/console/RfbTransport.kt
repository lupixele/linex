package com.linex.vm.console

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Single-use connection owned by the viewer. Call connect on its worker thread. */
interface RfbTransport : AutoCloseable {
    fun connect(timeoutMillis: Int)
    val inputStream: InputStream
    val outputStream: OutputStream
    fun setReadTimeout(timeoutMillis: Int)
}

class LoopbackRfbTransport(private val port: Int) : RfbTransport {
    private val socket = Socket()
    private val started = AtomicBoolean()
    override fun connect(timeoutMillis: Int) {
        check(started.compareAndSet(false, true)) { "Transport already started" }
        require(port in 1..65535 && timeoutMillis in 1..60_000)
        socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMillis)
        socket.tcpNoDelay = true
    }
    override val inputStream: InputStream get() = socket.getInputStream()
    override val outputStream: OutputStream get() = socket.getOutputStream()
    override fun setReadTimeout(timeoutMillis: Int) {
        require(timeoutMillis in 0..60_000)
        socket.soTimeout = timeoutMillis
    }
    override fun close() = socket.close()
}
