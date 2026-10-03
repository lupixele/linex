package com.linex.app.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.system.Os
import com.linex.app.core.NativeX11Protocol
import com.termux.x11.CmdEntryPoint
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Xorg owns this disposable process. Its exit() cannot terminate Linex's UI. */
class NativeX11Service : Service() {
    private val started = AtomicBoolean()
    @Volatile private var entryPoint: CmdEntryPoint? = null
    @Volatile private var failure: String? = null
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (Binder.getCallingUid() != android.os.Process.myUid()) throw SecurityException("Same UID required")
            data.enforceInterface(NativeX11Protocol.DESCRIPTOR)
            when (code) {
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
        if (started.compareAndSet(false, true)) {
            val tmp = requireNotNull(intent.getStringExtra("tmp"))
            val directory = File(tmp).canonicalFile
            require(directory.toPath().startsWith(filesDir.canonicalFile.toPath())) { "Display directory outside app storage" }
            val args = requireNotNull(intent.getStringArrayExtra("args"))
            Thread({
                try {
                    Os.setenv("TMPDIR", directory.absolutePath, true)
                    Os.setenv("XKB_CONFIG_ROOT", File(directory.parentFile, "rootfs/usr/share/X11/xkb").absolutePath, true)
                    val native = CmdEntryPoint { }
                    entryPoint = native
                    check(native.start(args)) { "Native X11 server rejected startup" }
                } catch (error: Throwable) { failure = error.message ?: error.javaClass.simpleName }
            }, "Linex-X11-start").start()
        }
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
