package com.linex.app.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import android.os.Process
import android.system.Os
import com.linex.app.data.ContainerState
import com.linex.vm.NativeVmService
import com.linex.vm.QmpClient
import com.linex.vm.VmDesktopBootRequest
import com.linex.vm.VmDesktopLaunch
import com.linex.vm.VmEngineClient
import com.linex.vm.VmHostObservation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.SecureRandom

/** Service-owned desktop sessions survive activity detachment. All control runs off the UI thread. */
class VmSessionManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var closed = false
    private val mutableState = MutableStateFlow<Map<String, ContainerState>>(emptyMap())
    val state: StateFlow<Map<String, ContainerState>> = mutableState
    @Volatile private var active: Session? = null
    private class Session(val request: VmDesktopBootRequest, val password: String) {
        val cleanupLock = Mutex()
        var launchJob: Deferred<Boolean>? = null
        var connection: ServiceConnection? = null
        var bound = false
        var engine: VmEngineClient? = null
        var launch: VmDesktopLaunch? = null
        var listening: LocalSocket? = null
        var listener: LocalServerSocket? = null
        var serial: LocalSocket? = null
        var reader: Job? = null
        var monitor: Job? = null
        @Volatile var endpoint: DisplayEndpoint? = null
        val bootReady = CompletableDeferred<Unit>()
        val desktopReady = CompletableDeferred<Unit>()
        val cleanStop = CompletableDeferred<Unit>()
        val browserProof = CompletableDeferred<Unit>()
        val httpsProof = CompletableDeferred<Unit>()
        var proofRequested = false
        @Volatile var stopping = false
    }

    fun getEndpoint(instanceId: String): DisplayEndpoint? = active?.takeIf { it.request.instanceId == instanceId }?.endpoint
    fun isActive(): Boolean = active != null
    fun getProcessId(instanceId: String): Int? = active?.takeIf { it.request.instanceId == instanceId }?.launch?.pid
    suspend fun observeHost(instanceId: String): VmHostObservation? = withContext(Dispatchers.IO) {
        val session = active?.takeIf { it.request.instanceId == instanceId } ?: return@withContext null
        session.engine?.observeHost()
    }
    /** Instrumentation uses the factory's fixed non-root browser/TLS diagnostics. */
    internal suspend fun proveGuestBrowser(instanceId: String): Unit = withContext(Dispatchers.IO) {
        val session = lock.withLock {
            val current = requireNotNull(active?.takeIf { it.request.instanceId == instanceId })
            check(current.endpoint != null && !current.stopping && !current.proofRequested)
            current.proofRequested = true
            write(current, VmGuestControl.proof(current.request.sessionToken))
            current
        }
        withTimeout(180000) {
            session.browserProof.await()
            session.httpsProof.await()
        }
    }
    private fun setState(session: Session, value: ContainerState) {
        mutableState.value = mutableState.value + (session.request.instanceId to value)
    }
    private fun log(session: Session, text: String) = AppLogger.log("VirtualMachine",
        text.replace(session.password, "[redacted]"), session.request.instanceId)

    suspend fun launch(request: VmDesktopBootRequest, width: Int, height: Int, fps: Int): Boolean =
        withContext(Dispatchers.IO) {
            val operation = lock.withLock {
            check(!closed && scope.isActive) { "Virtual machine manager is closed" }
            check(active == null) { "Stop the current virtual machine before starting another instance" }
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val random = SecureRandom()
            val password = CharArray(8) { alphabet[random.nextInt(alphabet.length)] }.concatToString()
            VmGuestControl.launch(request.sessionToken, password, width, height, fps)
            val session = Session(request, password)
            active = session
            setState(session, ContainerState.STARTING)
            scope.async(start = CoroutineStart.LAZY) { startSession(session, width, height, fps) }
                .also { session.launchJob = it }
            }
            operation.start()
            operation.await()
        }

    private suspend fun startSession(session: Session, width: Int, height: Int, fps: Int): Boolean {
            val request = session.request
            return try {
                currentCoroutineContext().ensureActive()
                val memory = android.app.ActivityManager.MemoryInfo()
                context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(memory)
                request.validated(context.filesDir, memory.availMem, memory.lowMemory)
                val socketFile = File(request.serialPath)
                check(!socketFile.exists()) { "Serial generation already exists" }
                val listening = LocalSocket().also { session.listening = it }
                listening.bind(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                Os.chmod(socketFile.absolutePath, 384)
                val listener = LocalServerSocket(listening.fileDescriptor).also { session.listener = it }
                val binderReady = CompletableDeferred<IBinder>()
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) { binderReady.complete(service) }
                    override fun onServiceDisconnected(name: ComponentName) {
                        binderReady.completeExceptionally(IllegalStateException("VM service disconnected"))
                    }
                    override fun onNullBinding(name: ComponentName) { binderReady.completeExceptionally(IllegalStateException("VM service unavailable")) }
                    override fun onBindingDied(name: ComponentName) { binderReady.completeExceptionally(IllegalStateException("VM binding died")) }
                }
                session.connection = connection
                val intent = Intent(context, NativeVmService::class.java)
                context.startForegroundService(intent)
                session.bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                check(session.bound) { "Could not bind the virtual machine service" }
                val binder = withTimeout(10000) { binderReady.await() }
                val engine = VmEngineClient(binder, context.filesDir).also { session.engine = it }
                // Accept concurrently: QEMU connects its serial stream before publishing the console.
                val accepted = scope.async { listener.accept() }
                try {
                    session.launch = engine.startDesktop(request)
                    session.serial = withTimeout(15000) { accepted.await() }
                } finally {
                    closeSocket(listening)
                    listener.close()
                    accepted.cancel()
                    withContext(NonCancellable) { withTimeout(2000) { accepted.join() } }
                }
                check(session.serial!!.peerCredentials.uid == Process.myUid()) { "Unexpected VM serial peer" }
                log(session, "Booting Linux with ${request.memoryMiB} MiB RAM and ${request.vcpuCount} CPUs")
                currentCoroutineContext().ensureActive()
                session.reader = scope.launch { readSerial(session) }
                session.monitor = scope.launch { monitor(session) }
                withTimeout(180000) { session.bootReady.await() }
                write(session, VmGuestControl.launch(request.sessionToken, session.password, width, height, fps))
                withTimeout(45000) { session.desktopReady.await() }
                val console = requireNotNull(session.launch).console
                lock.withLock {
                    check(active === session && !session.stopping && !closed && engine.isAlive()) {
                        "Virtual machine ended before desktop readiness could be published"
                    }
                    session.endpoint = DisplayEndpoint(0, session.password, DisplayBackend.VM_RFB, console.generation, width, height)
                    setState(session, ContainerState.RUNNING)
                    log(session, "Desktop ready at ${width}×${height}; $fps FPS target")
                    true
                }
            } catch (failure: Throwable) {
                log(session, "Could not start desktop: ${failure.message ?: failure.javaClass.simpleName}")
                withContext(NonCancellable) { finish(session, graceful = false) }
                if (failure is CancellationException) throw failure
                false
            }
    }

    private suspend fun readSerial(session: Session) {
        try {
            val input = requireNotNull(session.serial).inputStream.buffered()
            val line = StringBuilder()
            var dropping = false
            while (currentCoroutineContext().isActive) {
                val next = input.read()
                if (next < 0) break
                if (next != 10) {
                    if (!dropping) {
                        if (line.length >= 8192) { line.setLength(0); dropping = true }
                        else line.append(next.toChar())
                    }
                    continue
                }
                if (dropping) {
                    dropping = false
                    log(session, "Oversized guest diagnostic line discarded")
                    continue
                }
                val text = line.toString().trimEnd('\r'); line.setLength(0)
                log(session, text)
                when (text) {
                    "LINEX_VM_DESKTOP_CONTROL_READY" -> session.bootReady.complete(Unit)
                    "LINEX_VM_DESKTOP_READY session=${session.request.sessionToken}" -> session.desktopReady.complete(Unit)
                    "LINEX_VM_DESKTOP_CLEAN_STOP" -> session.cleanStop.complete(Unit)
                    "LINEX_VM_DESKTOP_BROWSER_SCREENSHOT uid=1000" -> session.browserProof.complete(Unit)
                    "LINEX_VM_DESKTOP_HTTPS_VERIFIED uid=1000" -> session.httpsProof.complete(Unit)
                    "LINEX_VM_DESKTOP_CONTROL_REJECTED" -> error("Guest rejected desktop control")
                }
                if (!session.stopping && (text.startsWith("LINEX_VM_DESKTOP_PROCESS_EXIT kind=vnc ") ||
                        text.startsWith("LINEX_VM_DESKTOP_PROCESS_EXIT kind=xfce "))) {
                    error("Linux desktop process stopped")
                }
            }
            if (!session.stopping) error("Linux serial stream closed")
        } catch (failure: Exception) {
            if (!session.stopping) {
                session.bootReady.completeExceptionally(failure)
                session.desktopReady.completeExceptionally(failure)
                session.browserProof.completeExceptionally(failure)
                session.httpsProof.completeExceptionally(failure)
                log(session, "Desktop stopped: ${failure.message}")
                scope.launch { lock.withLock { if (active === session) finish(session, graceful = false) } }
            }
        }
    }

    private suspend fun monitor(session: Session) {
        while (currentCoroutineContext().isActive && !session.stopping) {
            delay(2000)
            val engine = session.engine ?: return
            val failure = runCatching {
                check(engine.isAlive()) { "The virtual machine service exited" }
                val status = engine.status()
                check(status.state !in listOf("FAILED", "EXITED", "STOPPING")) { status.failure ?: "Virtual machine exited" }
            }.exceptionOrNull()
            if (failure != null && !session.stopping) {
                session.bootReady.completeExceptionally(failure)
                session.desktopReady.completeExceptionally(failure)
                log(session, failure.message ?: "Virtual machine exited")
                session.browserProof.completeExceptionally(failure)
                session.httpsProof.completeExceptionally(failure)
                lock.withLock { if (active === session) finish(session, graceful = false) }
                return
            }
        }
    }

    private fun write(session: Session, command: String) {
        requireNotNull(session.serial).outputStream.apply { write(command.toByteArray(Charsets.US_ASCII)); flush() }
    }
    private fun closeSocket(socket: LocalSocket?) {
        if (socket == null) return
        // Both connected reads and listening accepts can outlive close(fd).
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }
    suspend fun pause(): Boolean = control(resume = false)
    suspend fun resume(): Boolean = control(resume = true)
    private suspend fun control(resume: Boolean): Boolean = withContext(Dispatchers.IO) { lock.withLock {
        val session = active ?: return@withLock false
        if (session.stopping || session.endpoint == null) return@withLock false
        runCatching {
            QmpClient.connect(requireNotNull(session.launch).console.qmpSocket, context.filesDir).use {
                it.negotiate(); if (resume) it.resume() else it.pause()
            }
            setState(session, if (resume) ContainerState.RUNNING else ContainerState.SUSPENDED)
            true
        }.getOrElse { log(session, "Could not ${if (resume) "resume" else "pause"}: ${it.message}"); false }
    } }

    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        val session = lock.withLock { active?.also { it.stopping = true } } ?: return@withContext false
        withContext(NonCancellable) {
            val starting = session.launchJob
            if (starting?.isCompleted == false) starting.cancelAndJoin()
            finish(session, graceful = true)
        }
    }
    private suspend fun finish(session: Session, graceful: Boolean) = session.cleanupLock.withLock {
        if (active !== session) return@withLock true
        session.stopping = true
        session.monitor?.cancel()
        val engine = session.engine
        if (engine?.isAlive() == true) {
            if (graceful && session.endpoint != null) runCatching {
                QmpClient.connect(requireNotNull(session.launch).console.qmpSocket, context.filesDir).use { it.negotiate(); it.resume() }
                write(session, VmGuestControl.stop(session.request.sessionToken))
                withTimeout(20000) { session.cleanStop.await() }
                check(engine.awaitExit(10000)) { "Guest did not finish shutdown" }
            }.onFailure { log(session, "Clean shutdown did not complete; disk journal will recover on next boot") }
            if (engine.isAlive()) {
                // A rejected START_DESKTOP never supplies a client session token.
                // The service still schedules its own exit; always observe that
                // exit even when a force-stop command cannot be sent.
                runCatching { engine.forceStop() }
                val confirmed = runCatching { engine.awaitExit(10000) }.getOrDefault(!engine.isAlive())
                if (!confirmed) {
                    log(session, "VM stop is not confirmed. Retry stopping; this disk remains owned.")
                    // Closing parent transports does not release the engine's disk lock.
                    session.reader?.cancel()
                    closeSocket(session.serial)
                    closeSocket(session.listening)
                    runCatching { session.listener?.close() }
                    session.endpoint = null
                    return@withLock false
                }
            }
        }
        session.reader?.cancel()
        closeSocket(session.serial)
        closeSocket(session.listening)
        runCatching { session.listener?.close() }
        runCatching { engine?.close() }
        if (session.bound) runCatching { context.unbindService(requireNotNull(session.connection)) }
        runCatching { File(session.request.serialPath).delete() }
        session.endpoint = null
        if (active === session) active = null
        setState(session, ContainerState.STOPPED)
        true
    }

    /** Service destruction may not block its main thread. Cleanup keeps its own IO scope until done. */
    fun close() {
        closed = true
        scope.launch {
            while (active != null) {
                if (runCatching { stop() }.getOrDefault(false)) break
                delay(2000)
            }
            scope.cancel()
        }
    }
}
