package com.linex.app.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.app.ActivityManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.linex.app.service.NativeX11Service
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

/** A single service-owned display, independent of Activity/surface lifetimes. */
object EmbeddedX11Server {
    @Volatile private var remote: IBinder? = null
    @Volatile private var session: String? = null
    @Volatile private var binding: ServiceConnection? = null
    private var owner: Context? = null
    private var authority: File? = null
    private var diagnosticCapture: NativeX11DiagnosticCapture? = null
    private var diagnosticFile: File? = null

    suspend fun start(context: Context, tmpDirectory: File, width: Int, height: Int, dpi: Int, fps: Int, onLog: (String) -> Unit): String = withContext(Dispatchers.IO) {
        stop()
        val app = context.applicationContext
        val startedAt = System.currentTimeMillis()
        var diagnostics: File? = null
        var pipeWriter: ParcelFileDescriptor? = null
        var serverPid: Int? = null
        val ready = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                if (binding !== this) return
                remote = service
                ready.complete(service)
            }
            override fun onServiceDisconnected(name: ComponentName?) { if (binding === this) remote = null }
            override fun onNullBinding(name: ComponentName?) {
                if (binding === this) ready.completeExceptionally(IllegalStateException("Native X11 service unavailable"))
            }
            override fun onBindingDied(name: ComponentName?) {
                if (binding !== this) return
                remote = null
                ready.completeExceptionally(IllegalStateException("Native X11 service died"))
            }
        }
        owner = app
        binding = connection
        try {
            diagnosticFile?.let { runCatching { it.delete() } }
            // This directory is outside all guest bind mounts. createTempFile
            // creates a fresh file; guest-controlled symlinks are never opened.
            val hostDirectory = File(app.noBackupFilesDir, "native-x11-diagnostics")
            check(hostDirectory.isDirectory || hostDirectory.mkdirs()) { "Cannot create host display diagnostics" }
            // Keep at most four bounded launch records across app restarts.
            hostDirectory.listFiles()?.filter { it.name.startsWith("display-") && it.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }?.drop(3)
                ?.forEach { runCatching { java.nio.file.Files.deleteIfExists(it.toPath()) } }
            val file = File.createTempFile("display-", ".log", hostDirectory)
            diagnostics = file
            diagnosticFile = file
            android.system.Os.chmod(file.absolutePath, 0x180)
            val pipe = ParcelFileDescriptor.createPipe()
            pipeWriter = pipe[1]
            diagnosticCapture = NativeX11DiagnosticCapture(ParcelFileDescriptor.AutoCloseInputStream(pipe[0]), file)
                .also { it.start() }
            val secret = File(tmpDirectory, ".linex-x11.auth")
            java.nio.file.Files.deleteIfExists(secret.toPath())
            val cookie = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            secret.writeBytes(X11Authority.encode(cookie))
            android.system.Os.chmod(secret.absolutePath, 0x180)
            authority = secret
            val intent = Intent(app, NativeX11Service::class.java).putExtra("tmp", tmpDirectory.absolutePath)
                .putExtra("args", NativeX11Arguments.build(dpi, secret.absolutePath))
            check(app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) { "Could not bind native X11 service" }
            val service = withTimeout(15_000) { ready.await() }
            pipeWriter.use { output ->
                serverPid = transact(NativeX11Protocol.START, service,
                    write = { requireNotNull(output).writeToParcel(it, 0) }) { it.readInt() }
            }
            pipeWriter = null
            withTimeout(15_000) {
                while (true) {
                    val error = transact(NativeX11Protocol.ERROR) { it.readString() }
                    check(error == null) { error.orEmpty() }
                    check(remote?.isBinderAlive == true) { "Native X11 process exited during startup" }
                    // Connection IPC is also required; a stale pathname alone is not readiness.
                    val fd = if (File(tmpDirectory, ".X11-unix/X0").exists()) connectionDescriptor() else null
                    if (fd != null && File(tmpDirectory, ".X11-unix/X0").exists()) { fd.close(); break }
                    fd?.close()
                    delay(100)
                }
            }
            val id = UUID.randomUUID().toString()
            session = id
            onLog("Embedded native X11 ready; host GLES presentation, guest GL remains software. Frame target $fps FPS.")
            id
        } catch (error: Throwable) {
            if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) {
                runCatching { pipeWriter?.close() }
                pipeWriter = null
                withContext(NonCancellable) { stop() }
                throw error
            }
            // Read historical exits before intentional teardown, and only match
            // the PID returned by this launch's Binder START transaction.
            val exit = if (Build.VERSION.SDK_INT >= 30 && serverPid != null) runCatching {
                app.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(app.packageName, 0, 10)
                    .firstOrNull { it.timestamp >= startedAt && it.pid == serverPid && it.processName == "${app.packageName}:x11" }
                    ?.let { "Android X11 exit: reason=${it.reason}, status=${it.status}, description=${it.description?.take(512)}" }
            }.getOrNull() else null
            // Closing our writer and stopping the child gives the parent reader
            // EOF, so queued native fatal output is persisted before reading it.
            runCatching { pipeWriter?.close() }
            pipeWriter = null
            val capture = diagnosticCapture
            withContext(NonCancellable) { stop() }
            val lines = capture?.lines()?.takeIf { it.isNotEmpty() }
                ?: runCatching { diagnostics?.let { NativeX11DiagnosticTail.read(it) }.orEmpty() }.getOrDefault(emptyList())
            lines.forEach { onLog("Native X11: $it") }
            exit?.let(onLog)
            val detail = lines.lastOrNull()?.take(512) ?: exit
            throw IllegalStateException(listOfNotNull(error.message ?: error.javaClass.simpleName, detail).joinToString("; "), error)
        } finally {
            runCatching { pipeWriter?.close() }
        }
    }

    suspend fun obtainConnection(sessionId: String): ParcelFileDescriptor? = withContext(Dispatchers.IO) {
        if (session != sessionId) return@withContext null
        val expected = remote ?: return@withContext null
        if (session != sessionId) return@withContext null
        val descriptor = connectionDescriptor(expected)
        if (session != sessionId || remote !== expected) {
            descriptor?.close()
            null
        } else descriptor
    }

    fun isAlive(sessionId: String): Boolean = session == sessionId && remote?.isBinderAlive == true

    private fun connectionDescriptor(binder: IBinder? = remote): ParcelFileDescriptor? = transact(NativeX11Protocol.CONNECTION, binder) {
        if (it.readInt() == 0) null else ParcelFileDescriptor.CREATOR.createFromParcel(it)
    }

    private fun <T> transact(code: Int, target: IBinder? = remote, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T? {
        val binder = target ?: return null
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            request.writeInterfaceToken(NativeX11Protocol.DESCRIPTOR)
            write(request)
            check(binder.transact(code, request, response, 0)) { "Native X11 IPC rejected" }
            response.readException()
            return read(response)
        } finally { request.recycle(); response.recycle() }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        session = null
        val previous = remote
        runCatching { transact(NativeX11Protocol.STOP, previous) { Unit } }
        binding?.let { connection -> owner?.let { runCatching { it.unbindService(connection) } } }
        binding = null
        owner = null
        remote = null
        // Don't let a new bind reuse the old process while its shutdown is pending.
        if (previous != null) runCatching { withTimeout(3000) { while (previous.isBinderAlive) delay(50) } }
        diagnosticCapture?.let { runCatching { it.finish() } }
        diagnosticCapture = null
        authority?.let { runCatching { java.nio.file.Files.deleteIfExists(it.toPath()) } }
        authority = null
    }
}
