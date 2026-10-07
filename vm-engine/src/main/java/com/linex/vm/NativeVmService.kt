package com.linex.vm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Genuine emulator worker inside a disposable Android-managed process, never a shell child. */
class NativeVmService : Service() {
    private val used = AtomicBoolean()
    private val terminating = AtomicBoolean()
    private val startLock = Any()
    @Volatile private var token: String? = null
    @Volatile private var state = "IDLE"
    @Volatile private var failure: String? = null
    private var sessionDirectory: File? = null

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(DESCRIPTOR)
                return true
            }
            if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("VM engine requires the same Android UID")
            data.enforceInterface(DESCRIPTOR)
            when (code) {
                START -> {
                    val directory = acceptStart(data)
                    reply?.writeNoException()
                    reply?.writeInt(Process.myPid())
                    reply?.writeString(File(directory, "qmp.sock").absolutePath)
                }
                STATUS -> {
                    requireSession(data.readString())
                    val observation = sampleHostProcesses()
                    reply?.writeNoException()
                    reply?.writeString(state)
                    reply?.writeString(failure)
                    reply?.writeInt(Process.myPid())
                    reply?.writeInt(observation.threadCount)
                    reply?.writeInt(observation.childCount)
                    reply?.writeInt(if (observation.complete) 1 else 0)
                    reply?.writeString(observation.method.wireValue)
                    reply?.writeString(observation.detail)
                }
                FORCE_STOP -> {
                    requireSession(data.readString())
                    val accepted = terminating.compareAndSet(false, true)
                    if (accepted) state = "STOPPING"
                    reply?.writeNoException()
                    reply?.writeInt(if (accepted) 1 else 0)
                    if (accepted) Handler(Looper.getMainLooper()).postDelayed({ terminate() }, 100)
                }
                HOST_OBSERVE -> {
                    require(data.dataAvail() == 0) { "Unexpected host observation payload" }
                    val observation = sampleHostProcesses()
                    reply?.writeNoException()
                    reply?.writeInt(Process.myPid())
                    reply?.writeInt(observation.threadCount)
                    reply?.writeInt(observation.childCount)
                    reply?.writeInt(if (observation.complete) 1 else 0)
                    reply?.writeString(observation.method.wireValue)
                    reply?.writeString(observation.detail)
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }
    }

    private fun sampleHostProcesses(): VmHostProcessSnapshot {
        var dumpability: Int? = null
        var dumpabilityErrno: Int? = null
        val observation = VmHostProcessStats(readDumpability = {
            try {
                Os.prctl(OsConstants.PR_GET_DUMPABLE, 0L, 0L, 0L, 0L).also { dumpability = it }
            } catch (error: ErrnoException) {
                dumpabilityErrno = error.errno
                throw error
            }
        }).sample()
        val childrenErrno = try {
            Os.lstat("/proc/self/task/${Process.myTid()}/children"); 0
        } catch (error: ErrnoException) { error.errno }
        Log.i("LinexVmHost", "pid=${Process.myPid()} method=${observation.method.wireValue} " +
            "complete=${observation.complete} threads=${observation.threadCount} children=${observation.childCount} " +
            "detail=${observation.detail} threadChildrenErrno=$childrenErrno " +
            "dumpability=$dumpability dumpabilityErrno=$dumpabilityErrno")
        return observation
    }

    private fun requireSession(supplied: String?) {
        require(token != null && supplied == token) { "Stale or unknown VM session" }
    }

    private fun acceptStart(data: Parcel): File = synchronized(startLock) {
        // A malformed second request must never tear down the already owned VM.
        check(!used.get() && !terminating.get()) { "A VM process accepts one launch; bind a fresh process" }
        try {
            val request = VmBootRequest(
                requireNotNull(data.readString()), requireNotNull(data.readString()),
                requireNotNull(data.readString()), requireNotNull(data.readString()),
                requireNotNull(data.readString()), requireNotNull(data.readString()),
                requireNotNull(data.readString()), data.readInt(), data.readInt(),
                VmNetworkMode.fromWire(data.readInt()),
            )
            require(data.dataAvail() == 0) { "Unexpected VM launch payload" }
            prepare(request)
        } catch (error: Exception) {
            failure = "VM launch rejected: ${error.javaClass.simpleName}"
            state = "FAILED"
            terminating.set(true)
            // Give Binder the opportunity to return the actual rejection first.
            Handler(Looper.getMainLooper()).postDelayed({ terminate() }, 250)
            throw error
        }
    }

    private fun prepare(request: VmBootRequest): File {
        check(!used.get()) { "A VM process accepts one launch; bind a fresh process" }
        val root = File(filesDir, "vm-proof").canonicalFile
        request.validated(listOf(root))
        require(request.serialPath.toByteArray(Charsets.UTF_8).size <= 100) { "Serial socket path is too long" }
        val serial = Os.lstat(request.serialPath)
        require(OsConstants.S_ISSOCK(serial.st_mode) && serial.st_uid == Process.myUid()) {
            "Serial endpoint must be a private socket owned by this application"
        }
        require(serial.st_mode and 0x3f == 0) { "Serial socket must deny access to other users" }
        NativeVm.ensureLoaded() // Missing native engine is an explicit launch failure.
        check(used.compareAndSet(false, true)) { "A VM process accepts one launch; bind a fresh process" }
        token = request.sessionToken
        state = "STARTING"
        val directory = File(root, "session-${request.sessionToken}")
        check(!directory.exists() && directory.mkdirs()) { "VM session directory already exists or cannot be created" }
        Os.chmod(directory.absolutePath, 0x1c0)
        sessionDirectory = directory
        val qmp = File(directory, "qmp.sock").absolutePath
        require(qmp.toByteArray(Charsets.UTF_8).size <= 100) { "QMP socket path is too long" }
        val arguments = arrayOf(
            "linex-qemu", "-no-user-config", "-nodefaults", "-machine", "virt", "-cpu", "cortex-a53",
            "-accel", "tcg,thread=multi", "-smp", request.vcpuCount.toString(), "-m", request.memoryMiB.toString(),
            "-kernel", request.kernelPath, "-initrd", request.initramfsPath,
            "-append", "console=ttyAMA0 rdinit=/init panic=-1", "-display", "none", "-monitor", "none",
            *request.network.qemuArguments(), "-no-reboot",
            "-chardev", "socket,id=linexserial,path=${optionPath(request.serialPath)},server=off",
            "-serial", "chardev:linexserial", "-qmp", "unix:${optionPath(qmp)},server=on,wait=off",
        )
        Handler(Looper.getMainLooper()).post {
            if (terminating.get()) return@post
            try {
                publishNotification("Booting verified Linux fixture")
            } catch (error: RuntimeException) {
                failure = "Could not start VM foreground execution: ${error.javaClass.simpleName}"
                state = "FAILED"
                Handler(Looper.getMainLooper()).postDelayed({ terminate() }, 250)
                return@post
            }
            Thread({
                try {
                    if (terminating.get()) return@Thread
                    state = "EMULATING" // Only the parent fixture handshake proves guest readiness.
                    val result = when (request.network) {
                        VmNetworkMode.DISABLED -> NativeVm.run(arguments, -1, 0)
                        VmNetworkMode.SLIRP_V4 -> VmDnsChannel.start(this@NativeVmService).use { channel ->
                            channel.withNativeEndpoint { fd, generation -> NativeVm.run(arguments, fd, generation) }
                        }
                    }
                    state = "EXITED"
                    if (result != 0) failure = "VM engine exited with code $result"
                } catch (error: Throwable) {
                    failure = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(512)}"
                    state = "FAILED"
                }
                Handler(Looper.getMainLooper()).postDelayed({ terminate() }, 250)
            }, "linex-vm-engine").start()
        }
        return directory
    }

    private fun optionPath(path: String) = path.replace(",", ",,")

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Linex virtual machine", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            publishNotification("Preparing VM engine")
        } catch (error: RuntimeException) {
            failure = "Could not start VM foreground execution: ${error.javaClass.simpleName}"
            state = "FAILED"
            terminating.set(true)
            Handler(Looper.getMainLooper()).postDelayed({ terminate() }, 100)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun publishNotification(message: String) {
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage).setContentTitle("Linex virtual machine")
            .setContentText(message).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun terminate() {
        state = "STOPPING"
        sessionDirectory?.let { directory ->
            runCatching { File(directory, "qmp.sock").delete() }
            runCatching { directory.delete() }
        }
        stopSelf()
        Process.killProcess(Process.myPid())
    }

    override fun onDestroy() {
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    companion object {
        const val DESCRIPTOR = "com.linex.vm.Engine"
        const val START = IBinder.FIRST_CALL_TRANSACTION
        const val STATUS = START + 1
        const val FORCE_STOP = START + 2
        const val HOST_OBSERVE = START + 3
        private const val CHANNEL = "linex-vm-engine"
        private const val NOTIFICATION_ID = 1102
    }
}
