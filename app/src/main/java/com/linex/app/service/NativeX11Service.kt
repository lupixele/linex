package com.linex.app.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.system.Os
import com.linex.app.core.NativeX11Protocol
import com.termux.x11.CmdEntryPoint
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicBoolean

/** Xorg owns this disposable process. Its exit() cannot terminate Linex's UI. */
class NativeX11Service : Service() {
    private val started = AtomicBoolean()
    @Volatile private var entryPoint: CmdEntryPoint? = null
    @Volatile private var failure: String? = null
    private lateinit var directory: File
    private lateinit var arguments: Array<String>
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (Binder.getCallingUid() != android.os.Process.myUid()) throw SecurityException("Same UID required")
            data.enforceInterface(NativeX11Protocol.DESCRIPTOR)
            when (code) {
                NativeX11Protocol.START -> {
                    ParcelFileDescriptor.CREATOR.createFromParcel(data).use { output ->
                        check(started.compareAndSet(false, true)) { "Native X11 already started" }
                        // The parent owns the bounded recorder and its host-private
                        // file. Never open guest-controlled paths for native output.
                        Os.dup2(output.fileDescriptor, 1)
                        Os.dup2(output.fileDescriptor, 2)
                    }
                    Handler(Looper.getMainLooper()).post { startNative() }
                    reply?.writeNoException()
                    reply?.writeInt(android.os.Process.myPid())
                }
                NativeX11Protocol.CONNECTION -> {
                    val connection = if (failure == null && started.get()) runCatching { entryPoint?.getXConnection() }.getOrNull() else null
                    reply?.writeNoException()
                    reply?.writeInt(if (connection != null) 1 else 0)
                    connection?.use { if (reply != null) it.writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE) }
                }
                NativeX11Protocol.ERROR -> { reply?.writeNoException(); reply?.writeString(failure) }
                NativeX11Protocol.STOP -> {
                    reply?.writeNoException()
                    Handler(Looper.getMainLooper()).postDelayed({ stopSelf(); android.os.Process.killProcess(android.os.Process.myPid()) }, 100)
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }
    }

    override fun onBind(intent: Intent): IBinder {
        directory = File(requireNotNull(intent.getStringExtra("tmp"))).canonicalFile
        require(directory.toPath().startsWith(filesDir.canonicalFile.toPath())) { "Display directory outside app storage" }
        arguments = requireNotNull(intent.getStringArrayExtra("args"))
        // START IPC supplies the pipe before invoking JNI; service Intents cannot
        // carry file descriptors. Binding alone must not start the native server.
        return binder
    }

    private fun startNative() {
        // native.start obtains AChoreographer for its calling thread. A plain
        // worker Thread has no Looper and gives native code a null choreographer.
        // Upstream also starts on its main Handler; Xorg itself spawns a worker.
        try {
            writeDiagnostic("Starting native X11 on Android main Looper; PID=${android.os.Process.myPid()}")
            check(Looper.myLooper() === Looper.getMainLooper()) { "Native X11 requires the main Looper" }
            Os.setenv("TMPDIR", directory.absolutePath, true)
            Os.setenv("XKB_CONFIG_ROOT", File(directory.parentFile, "rootfs/usr/share/X11/xkb").absolutePath, true)
            val native = CmdEntryPoint { }
            entryPoint = native
            check(native.start(arguments)) { "Native X11 server rejected startup" }
        } catch (error: Throwable) {
            failure = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            writeDiagnostic(failure.orEmpty())
        }
    }

    private fun writeDiagnostic(message: String) {
        // Android's Java System.err is a logcat stream; write fd 2 explicitly
        // so Java startup errors join Xorg's native stderr in the parent pipe.
        val bytes = "$message\n".toByteArray(Charsets.UTF_8)
        runCatching { Os.write(FileDescriptor.err, bytes, 0, bytes.size) }
    }

    override fun onDestroy() {
        super.onDestroy()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
