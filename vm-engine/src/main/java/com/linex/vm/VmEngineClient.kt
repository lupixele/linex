package com.linex.vm

import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class VmEngineLaunch(val pid: Int, val qmpSocket: File)
data class VmHostObservation(val pid: Int, val processes: VmHostProcessSnapshot)
data class VmEngineStatus(
    val state: String, val failure: String?, val pid: Int,
    val hostThreads: Int, val hostChildren: Int, val observationComplete: Boolean,
    val observationMethod: VmHostObservationMethod, val observationDetail: String?,
)

/** Blocking typed Binder commands. Each client belongs to exactly one launch. */
class VmEngineClient(private val binder: IBinder, private val privateRoot: File) : Closeable {
    private val closed = AtomicBoolean(false)
    private val exited = CountDownLatch(1)
    private var sessionToken: String? = null
    private var ownedSocket: File? = null
    private val deathRecipient = IBinder.DeathRecipient { exited.countDown() }

    init { binder.linkToDeath(deathRecipient, 0) }

    fun observeHost(): VmHostObservation = transact(NativeVmService.HOST_OBSERVE, {}) {
        VmHostObservation(it.readInt(), VmHostProcessSnapshot(it.readInt(), it.readInt(), it.readInt() == 1,
            VmHostObservationMethod.fromWire(it.readString()), it.readString()))
    }

    @Synchronized fun start(request: VmBootRequest): VmEngineLaunch {
        check(sessionToken == null) { "This VM client already owns a launch" }
        val launch = transact(NativeVmService.START, { data ->
            data.writeString(request.sessionToken)
            data.writeString(request.instanceId)
            data.writeString(request.kernelPath)
            data.writeString(request.kernelSha256)
            data.writeString(request.initramfsPath)
            data.writeString(request.initramfsSha256)
            data.writeString(request.serialPath)
            data.writeInt(request.memoryMiB)
            data.writeInt(request.vcpuCount)
        }) { reply -> VmEngineLaunch(reply.readInt(), File(requireNotNull(reply.readString()))) }
        sessionToken = request.sessionToken
        val expected = File(privateRoot.canonicalFile, "session-${request.sessionToken}/qmp.sock")
        check(launch.qmpSocket.canonicalFile == expected) { "Unexpected VM control endpoint" }
        ownedSocket = expected
        return launch
    }

    @Synchronized fun status(): VmEngineStatus = transact(NativeVmService.STATUS, {
        it.writeString(requireNotNull(sessionToken) { "No VM launch" })
    }) {
        VmEngineStatus(requireNotNull(it.readString()), it.readString(), it.readInt(),
            it.readInt(), it.readInt(), it.readInt() == 1,
            VmHostObservationMethod.fromWire(it.readString()), it.readString())
    }

    @Synchronized fun forceStop(): Boolean = transact(NativeVmService.FORCE_STOP, {
        it.writeString(requireNotNull(sessionToken) { "No VM launch" })
    }) { it.readInt() == 1 }

    fun isAlive(): Boolean = binder.isBinderAlive

    fun awaitExit(timeoutMillis: Long): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "VM exit waits must run off the main thread" }
        require(timeoutMillis in 1..10000)
        if (!binder.isBinderAlive) return true
        exited.await(timeoutMillis, TimeUnit.MILLISECONDS)
        return !binder.isBinderAlive
    }

    private fun <T> transact(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "VM commands must run off the main thread" }
        check(!closed.get()) { "VM client is closed" }
        check(isAlive()) { "VM service has exited" }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NativeVmService.DESCRIPTOR)
            write(data)
            check(binder.transact(code, data, reply, 0)) { "VM service rejected the command" }
            reply.readException()
            return read(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Removes observation only; session stop is explicit so UI detach can keep running. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        binder.unlinkToDeath(deathRecipient, 0)
        // Native abort/exit can bypass Java service cleanup. Remove only this
        // positively owned socket and empty directory after actual Binder death.
        if (!binder.isBinderAlive) ownedSocket?.let { socket ->
            val expectedParent = File(privateRoot.canonicalFile, "session-$sessionToken")
            if (socket.parentFile?.canonicalFile == expectedParent && socket.name == "qmp.sock") {
                runCatching { socket.delete() }
                runCatching { expectedParent.delete() }
            }
        }
    }
}
