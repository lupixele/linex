package com.linex.app.core

/** Per-launch credentials for a private desktop connection. Never log. */
enum class DisplayBackend { RFB, NATIVE_X11, VM_RFB }

class DisplayEndpoint(
    val port: Int,
    val password: String,
    val backend: DisplayBackend = DisplayBackend.RFB,
    val sessionId: String? = null,
    val width: Int = 0,
    val height: Int = 0
)
