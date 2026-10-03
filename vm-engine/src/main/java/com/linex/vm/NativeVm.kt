package com.linex.vm

/** One full-system emulator per disposable Android-managed service process. */
object NativeVm {
    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        try {
            System.loadLibrary("linex_qemu_aarch64")
            loaded = true
        } catch (failure: UnsatisfiedLinkError) {
            throw IllegalStateException("Verified ARM64 VM engine is not installed for this Android ABI", failure)
        }
    }

    /** Blocks the worker until genuine emulator termination; never starts another executable. */
    @JvmStatic external fun run(arguments: Array<String>): Int
}
