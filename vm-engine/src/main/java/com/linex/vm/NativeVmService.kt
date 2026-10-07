package com.linex.vm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileLock
import java.security.SecureRandom
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
    private var desktopConsole: VmConsoleEndpoint? = null
    private var desktopLock: FileLock? = null
    private var desktopLockStream: FileOutputStream? = null

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
                START_DESKTOP -> {
                    val console = acceptDesktopStart(data)
                    reply?.writeNoException()
                    reply?.writeInt(Process.myPid())
                    reply?.writeString(console.generation)
                    reply?.writeString(console.socket.absolutePath)
                    reply?.writeString(console.qmpSocket.absolutePath)
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

    private fun acceptDesktopStart(data: Parcel): VmConsoleEndpoint = synchronized(startLock) {
        // Outside the rejection handler: stale/concurrent starts cannot stop an owned VM.
        check(!used.get() && !terminating.get()) { "A VM process accepts one launch; bind a fresh process" }
        try {
            val request = VmDesktopBootRequest(requireNotNull(data.readString()), requireNotNull(data.readString()),
                requireNotNull(data.readString()), requireNotNull(data.readString()),
                requireNotNull(data.readString()), requireNotNull(data.readString()),
                requireNotNull(data.readString()), data.readLong(), requireNotNull(data.readString()),
                data.readInt(), data.readInt())
            require(data.dataAvail() == 0) { "Unexpected desktop launch payload" }
            prepareDesktop(request)
        } catch (error: Exception) {
            failure = "Desktop VM launch rejected: ${error.javaClass.simpleName}"
            state = "FAILED"
            terminating.set(true)
            Handler(Looper.getMainLooper()).postDelayed({ terminate() }, 250)
            throw error
        }
    }

    private fun prepareDesktop(request: VmDesktopBootRequest): VmConsoleEndpoint {
        val storage = Os.lstat(filesDir.absolutePath)
        require(OsConstants.S_ISDIR(storage.st_mode) && storage.st_uid == Process.myUid()) { "Invalid application storage root" }
        val memory = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        request.validated(filesDir, memory.availMem, memory.lowMemory)
        verifySerialEndpoint(request.serialPath)
        val instance = File(filesDir, "vm-instances/${request.instanceId}")
        verifyPrivateDirectory(instance)
        for (path in listOf(request.kernelPath, request.initramfsPath, request.diskPath)) {
            val file = Os.lstat(path)
            require(OsConstants.S_ISREG(file.st_mode) && file.st_uid == Process.myUid() && file.st_mode and 0x12 == 0) {
                "Desktop assets must be owned regular files without other-user write access"
            }
        }
        require(Os.lstat(request.diskPath).st_nlink == 1L) { "Instance disk cannot have multiple hard links" }
        val lockFile = File(instance, "engine.lock")
        val descriptor = Os.open(lockFile.absolutePath,
            OsConstants.O_CREAT or OsConstants.O_RDWR or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 384)
        val stream = FileOutputStream(descriptor)
        try {
            val metadata = Os.fstat(descriptor)
            require(OsConstants.S_ISREG(metadata.st_mode) && metadata.st_uid == Process.myUid() && metadata.st_nlink == 1L &&
                metadata.st_mode and 0x3f == 0) { "Invalid instance lock file" }
            val lock = requireNotNull(stream.channel.tryLock()) { "Instance disk is already in use" }
            desktopLockStream = stream
            desktopLock = lock
        } catch (failure: Exception) { stream.close(); throw failure }
        val lockedDisk = Os.lstat(request.diskPath)
        require(OsConstants.S_ISREG(lockedDisk.st_mode) && lockedDisk.st_uid == Process.myUid() &&
            lockedDisk.st_nlink == 1L && lockedDisk.st_size == request.diskBytes) { "Instance disk changed before launch ownership" }
        NativeVm.ensureLoaded()
        val generation = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val console = VmConsoleEndpoint.create(filesDir, generation)
        val consoleRoot = File(filesDir, "vmc")
        if (!consoleRoot.exists()) {
            check(consoleRoot.mkdir()) { "Could not create private console storage" }
            Os.chmod(consoleRoot.absolutePath, 448)
        }
        verifyPrivateDirectory(consoleRoot)
        val directory = requireNotNull(console.socket.parentFile)
        check(directory.mkdir()) { "Console generation already exists" }
        Os.chmod(directory.absolutePath, 448)
        verifyPrivateDirectory(directory)
        desktopConsole = console
        check(used.compareAndSet(false, true)) { "A VM process accepts one launch" }
        token = request.sessionToken
        state = "STARTING"
        launchEngine(request.qemuArguments(console), VmNetworkMode.SLIRP_V4)
        // Return a usable endpoint only after QEMU creates and we restrict both listeners.
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            check(!terminating.get() && state != "FAILED" && state != "EXITED") { "Desktop engine failed before console readiness" }
            if (restrictSocketIfReady(console.socket) && restrictSocketIfReady(console.qmpSocket)) return console
            Thread.sleep(25)
        }
        error("Desktop engine did not create its private listeners")
    }

    private fun verifyPrivateDirectory(directory: File) {
        val metadata = Os.lstat(directory.absolutePath)
        require(OsConstants.S_ISDIR(metadata.st_mode) && metadata.st_uid == Process.myUid() && metadata.st_mode and 0x1ff == 448) {
            "Desktop directory must be private and application-owned"
        }
    }

    private fun restrictSocketIfReady(file: File): Boolean {
        val metadata = try { Os.lstat(file.absolutePath) } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) return false else throw error
        }
        require(OsConstants.S_ISSOCK(metadata.st_mode) && metadata.st_uid == Process.myUid()) { "Invalid desktop listener" }
        Os.chmod(file.absolutePath, 384)
        return true
    }

    private fun verifySerialEndpoint(path: String) {
        val serial = Os.lstat(path)
        require(OsConstants.S_ISSOCK(serial.st_mode) && serial.st_uid == Process.myUid()) {
            "Serial endpoint must be a private socket owned by this application"
        }
        require(serial.st_mode and 0x3f == 0) { "Serial socket must deny access to other users" }
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
        verifySerialEndpoint(request.serialPath)
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
        launchEngine(arguments, request.network)
        return directory
    }

    private fun launchEngine(arguments: Array<String>, network: VmNetworkMode) {
        Handler(Looper.getMainLooper()).post {
            if (terminating.get()) return@post
            try {
                publishNotification("Booting Linux")
            } catch (error: RuntimeException) {
                failure = "Could not start VM foreground execution: ${error.javaClass.simpleName}"
                state = "FAILED"
                Handler(Looper.getMainLooper()).postDelayed({ terminate() }, 250)
                return@post
            }
            Thread({
                try {
                    if (terminating.get()) return@Thread
                    state = "EMULATING" // Only a guest handshake proves readiness.
                    val result = when (network) {
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
                } finally {
                    runCatching { desktopLock?.release() }
                    runCatching { desktopLockStream?.close() }
                }
                Handler(Looper.getMainLooper()).postDelayed({ terminate() }, 250)
            }, "linex-vm-engine").start()
        }
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
        desktopConsole?.let { console ->
            runCatching { console.socket.delete() }
            runCatching { console.qmpSocket.delete() }
            runCatching { console.socket.parentFile?.delete() }
        }
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
        const val START_DESKTOP = START + 4
        private const val CHANNEL = "linex-vm-engine"
        private const val NOTIFICATION_ID = 1102
    }
}
