package com.linex.vm.console

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

class LoopbackRfbTransportTest {
    @Test fun exchangesBytesAndBoundsReadThenClosesItsOwnedSocket() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            LoopbackRfbTransport(server.localPort).use { transport ->
                transport.connect(1000)
                server.accept().use { peer ->
                    transport.outputStream.write(42)
                    assertEquals(42, peer.getInputStream().read())
                    peer.getOutputStream().write(91)
                    assertEquals(91, transport.inputStream.read())
                    transport.setReadTimeout(100)
                    val before = System.nanoTime()
                    assertThrows(SocketTimeoutException::class.java) { transport.inputStream.read() }
                    assertTrue(System.nanoTime() - before < 2_000_000_000L)
                    assertThrows(IllegalStateException::class.java) { transport.connect(1000) }
                    transport.close()
                    assertEquals(-1, peer.getInputStream().read())
                }
            }
        }
    }
}
