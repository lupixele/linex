package com.linex.app.core

import java.net.InetAddress

internal object GuestDns {
    fun configuration(servers: List<InetAddress>): String {
        val addresses = servers.filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }.distinct().take(3)
        require(addresses.isNotEmpty()) { "Active Android network has no usable DNS servers" }
        return addresses.joinToString("\n") { "nameserver $it" } + "\noptions timeout:2 attempts:2\n"
    }
}
