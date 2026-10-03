package com.linex.app.core

/** Private same-UID Binder protocol. No display secrets cross an exported endpoint. */
object NativeX11Protocol {
    const val DESCRIPTOR = "com.linex.app.NativeX11"
    const val CONNECTION = android.os.IBinder.FIRST_CALL_TRANSACTION
    const val STOP = CONNECTION + 1
    const val ERROR = CONNECTION + 2
    const val START = CONNECTION + 3
}
