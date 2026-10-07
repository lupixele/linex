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

    /**
     * Blocks until emulator termination. DNS is (-1,0) when disabled; otherwise
     * JNI borrows this descriptor and duplicates it with F_DUPFD_CLOEXEC.
     * It closes only its own duplicate, never the service's scoped descriptor.
     */
    @JvmStatic external fun run(arguments: Array<String>, borrowedDnsFd: Int, generation: Long): Int
}
