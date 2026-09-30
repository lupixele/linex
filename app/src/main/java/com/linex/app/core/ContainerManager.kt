package com.linex.app.core

import android.content.Context
import android.util.Log
import com.linex.app.data.ContainerState
import com.linex.app.data.DisplayResolutionMode
import com.linex.app.data.LinuxInstance
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class ContainerManager(
    private val context: Context,
    private val storageEngine: StorageEngine,
    private val processController: ProcessController,
    val rootfsDownloader: RootfsDownloader = RootfsDownloader(storageEngine)
) {
    companion object {
        private const val TAG = "ContainerManager"
    }

    private val _currentState = MutableStateFlow<Map<String, ContainerState>>(emptyMap())
    val currentState: StateFlow<Map<String, ContainerState>> = _currentState

    @Volatile private var activeInstance: LinuxInstance? = null
    @Volatile private var containerProcess: Process? = null
    @Volatile private var stopping = false
    @Volatile private var displayEndpoint: DisplayEndpoint? = null
    fun getDisplayEndpoint(instanceId: String): DisplayEndpoint? =
        if (activeInstance?.id == instanceId) displayEndpoint else null
    private var logReadingJob: Job? = null
    private val launchMutex = Mutex()
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getInstanceState(instanceId: String): ContainerState {
        return _currentState.value[instanceId] ?: ContainerState.STOPPED
    }

    /**
     * Resolves display geometry based on selected profile.
     */
    fun calculateDisplayGeometry(instance: LinuxInstance): Pair<Int, Int> {
        return when (instance.resolutionMode) {
            DisplayResolutionMode.NATIVE_PHONE -> {
                val dm = context.resources.displayMetrics
                Pair(dm.widthPixels, dm.heightPixels)
            }
            DisplayResolutionMode.FULL_HD_1080P -> Pair(1920, 1080)
            DisplayResolutionMode.HD_720P -> Pair(1280, 720)
            DisplayResolutionMode.DEX_AUTO -> {
                // Default 1080p for external display detection
                Pair(1920, 1080)
            }
            DisplayResolutionMode.CUSTOM -> {
                Pair(instance.customWidth, instance.customHeight)
            }
        }
    }

    /**
     * Boots a Linux instance with full rootless isolation via Linex bootstrap scripts.
     */
    suspend fun launchInstance(instance: LinuxInstance, onLog: (String) -> Unit): Boolean = launchMutex.withLock { withContext(Dispatchers.IO) {
        val logWrapper: (String) -> Unit = { msg ->
            AppLogger.log("ContainerManager", msg, instance.id)
            onLog(msg)
        }
        try {
            if (containerProcess?.isAlive == true) {
                logWrapper("Another session is active. Stop it before starting this instance.")
                return@withContext false
            }
            if (instance.desktop == com.linex.app.data.DesktopEnvironment.UBUNTU_TOUCH_PHOSH) {
                logWrapper("Phosh requires a Wayland compositor. Choose XFCE for the embedded X11 desktop.")
                return@withContext false
            }
            updateState(instance.id, ContainerState.STARTING)
            activeInstance = instance

            // 1. Ensure runtime assets (scripts and configs) are deployed
            logWrapper("Validating runtime bootstrap scripts...")
            if (!storageEngine.deployAssets(overwrite = true)) {
                logWrapper("Unable to prepare runtime scripts. Open instance logs for details.")
                updateState(instance.id, ContainerState.STOPPED)
                return@withContext false
            }

            val rootfsDir = storageEngine.getRootfsDirectory(instance.id)
            val tmpDir = storageEngine.getTmpDirectory(instance.id)
            refreshGuestDns(tmpDir, logWrapper)
            // Use new credentials and a loopback port for each launch. The guest
            // converts the private secret to TigerVNC's password file format.
            val random = java.security.SecureRandom()
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val password = CharArray(8) { alphabet[random.nextInt(alphabet.length)] }.concatToString()
            val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val secret = File(tmpDir, "linex-vnc.secret")
            java.nio.file.Files.deleteIfExists(secret.toPath())
            java.nio.file.Files.deleteIfExists(File(tmpDir, "linex-vnc.passwd").toPath())
            secret.writeText(password + "\n")
            android.system.Os.chmod(secret.absolutePath, 0x180)
            displayEndpoint = DisplayEndpoint(port, password)
            val scriptsDir = storageEngine.scriptsDir

            // Validate rootfs initialization before launching container
            val isInitialized = storageEngine.isInstanceInitialized(instance.id)
            if (!isInitialized) {
                val errMsg = "Cannot launch container: instance ${instance.name} is not initialized. Please download rootfs first."
                logWrapper("ERROR: $errMsg")
                updateState(instance.id, ContainerState.STOPPED)
                return@withContext false
            }

            // 2. Generate display geometry
            val (width, height) = calculateDisplayGeometry(instance)
            require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 8_000_000) {
                "Choose a display size up to 4096 pixels per side and 8 megapixels total"
            }
            val dpi = instance.dpiScaling.toInt()
            logWrapper("Geometry configured: ${width}x${height} @ ${dpi} DPI")

            // 3. Locate Entrypoint Script & PRoot Binary
            val entrypointScript = File(scriptsDir, "entrypoint.sh")
            if (!entrypointScript.exists()) {
                val err = "Missing entrypoint script at: ${entrypointScript.absolutePath}"
                Log.e(TAG, err)
                logWrapper("ERROR: $err")
                updateState(instance.id, ContainerState.STOPPED)
                return@withContext false
            }
            entrypointScript.setExecutable(true, false)

            val prootBin = File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath
            val startCommand = instance.desktop.startCommand

            val shBinary = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"

            // 3.5. Execute X11 Socket Setup before starting PRoot
            val instanceDir = storageEngine.getInstanceDirectory(instance.id)
            val x11SetupScript = File(scriptsDir, "x11_socket_setup.sh").let {
                if (it.exists()) it else File(instanceDir.parentFile, "runtime/scripts/x11_socket_setup.sh")
            }
            if (x11SetupScript.exists()) {
                logWrapper("Initializing X11 socket environment...")
                x11SetupScript.setExecutable(true, false)
                try {
                    val setupPb = ProcessBuilder(shBinary, x11SetupScript.absolutePath, tmpDir.absolutePath, "0")
                    setupPb.redirectErrorStream(true)
                    val setupProcess = setupPb.start()
                    BufferedReader(InputStreamReader(setupProcess.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            line?.let {
                                Log.d(TAG, "[X11Setup] $it")
                                logWrapper(it)
                            }
                        }
                    }
                    val setupExit = setupProcess.waitFor()
                    Log.i(TAG, "x11_socket_setup.sh completed with exit code: $setupExit")
                } catch (e: Exception) {
                    Log.w(TAG, "x11_socket_setup.sh execution error", e)
                    logWrapper("Warning: X11 socket setup error: ${e.message}")
                }
            }

            // 4. Construct entrypoint command
            // entrypoint.sh <rootfs_path> <tmp_path> <start_command> <display_width> <display_height> <display_dpi> <extra_binds> <bootstrap_dir>
            val command = listOf(
                "/system/bin/toybox", "setsid",
                shBinary,
                entrypointScript.absolutePath,
                rootfsDir.absolutePath,
                tmpDir.absolutePath,
                startCommand,
                width.toString(),
                height.toString(),
                dpi.toString(),
                "", // extra binds
                scriptsDir.absolutePath
            )

            logWrapper("Executing bootstrap sequence...")
            val pb = ProcessBuilder(command)
            pb.directory(scriptsDir)

            val env = pb.environment()
            env["CONFIG_DIR"] = storageEngine.configDir.absolutePath
            env["APP_LIB_DIR"] = context.applicationInfo.nativeLibraryDir
            env["LINUXDROID_PROOT_BIN"] = prootBin
            env["BOOTSTRAP_DIR"] = scriptsDir.absolutePath
            env["TERM"] = "xterm-256color"
            env["HOME"] = "/root"
            env["SHELL"] = "/bin/bash"
            env["LINEX_EMBEDDED_DISPLAY"] = "1"
            env["LINEX_VNC_PORT"] = port.toString()
            pb.redirectErrorStream(true)

            val process = pb.start()
            containerProcess = process

            // Android's public Process API does not expose pid(). The controlled
            // entrypoint announces its PID before any guest code can emit output.
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val handshake = reader.readLine().orEmpty()
            check(handshake.startsWith("__LINEX_PID__=")) { "Missing container process handshake: $handshake" }
            val pid = handshake.substringAfter('=').toInt()
            processController.setActiveProcess(pid)
            logWrapper("Container process initialized with isolated group $pid")
            logWrapper("Runtime: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, app ${com.linex.app.BuildConfig.VERSION_NAME}")
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val diagnosticsJob = managerScope.launch {
                while (isActive && process.isAlive) {
                    logRuntimeResources(pid, startedAt, logWrapper)
                    delay(30_000)
                }
            }

            // Stream logs asynchronously and monitor if process exits prematurely
            logReadingJob?.cancel()
            updateState(instance.id, ContainerState.RUNNING)
            logReadingJob = managerScope.launch {
                try {
                    reader.use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            line?.let {
                                Log.d(TAG, "[Guest] $it")
                                logWrapper(it)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Container log stream closed: ${e.message}")
                }
            }
            // A surviving guest daemon can retain stdout after the PRoot parent
            // exits. Observe process death independently of pipe EOF, then clean
            // up the group so those descendants cannot keep the session alive.
            managerScope.launch {
                try {
                    val exitCode = process.waitFor()
                    diagnosticsJob.cancel()
                    logRuntimeResources(pid, startedAt, logWrapper)
                    Log.w(TAG, "Container process exited with code $exitCode")
                    logWrapper("Container exited (code $exitCode)")
                    if (exitCode == 137 && !stopping) {
                        val memory = android.app.ActivityManager.MemoryInfo()
                        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memory)
                        logWrapper("Guest was forcibly killed (137/SIGKILL); cause is not identified. Android available memory=${memory.availMem / 1048576} MiB, lowMemory=${memory.lowMemory}. Device logcat is needed to distinguish memory/process-policy kills.")
                    }
                    launchMutex.withLock {
                        if (containerProcess === process && !stopping) {
                            processController.killForce()
                            containerProcess = null
                            activeInstance = null
                            displayEndpoint = null
                            processController.clearActiveProcess()
                            updateState(instance.id, ContainerState.STOPPED)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Process wait interrupted: ${e.message}")
                } finally {
                    diagnosticsJob.cancel()
                }
            }

            // Catch bootstrap failures before presenting a running session.
            delay(300)
            process.isAlive
        } catch (e: CancellationException) {
            stopActiveInstance()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch container", e)
            logWrapper("Failed to launch container: ${e.message}")
            if (containerProcess != null) stopActiveInstance()
            else { activeInstance = null; displayEndpoint = null; updateState(instance.id, ContainerState.STOPPED) }
            false
        }
    } }

    /**
     * Dispatches dynamic resolution resize inside running container.
     */
    suspend fun updateGeometry(width: Int, height: Int, dpi: Int): Boolean = withContext(Dispatchers.IO) {
        val instance = activeInstance ?: return@withContext false
        if (containerProcess == null) return@withContext false

        val rootfsDir = storageEngine.getRootfsDirectory(instance.id)
        val tmpDir = storageEngine.getTmpDirectory(instance.id)
        val scriptsDir = storageEngine.scriptsDir
        val inContainerDir = File(scriptsDir, "in_container")
        val prootBin = File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath

        val xrandrScript = File(inContainerDir, "xrandr_helper.sh")
        if (!xrandrScript.exists()) {
            Log.w(TAG, "xrandr_helper.sh not found at ${xrandrScript.absolutePath}")
            return@withContext false
        }

        try {
            val command = listOf(
                prootBin,
                "-0",
                "-r", rootfsDir.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "${tmpDir.absolutePath}:/tmp",
                "-b", "${inContainerDir.absolutePath}:/linex",
                "-w", "/root",
                "/bin/sh", "/linex/xrandr_helper.sh",
                width.toString(),
                height.toString(),
                dpi.toString()
            )
            val pb = ProcessBuilder(command)
            val env = pb.environment()
            env["APP_LIB_DIR"] = context.applicationInfo.nativeLibraryDir
            env["LINUXDROID_PROOT_BIN"] = prootBin
            env["BOOTSTRAP_DIR"] = scriptsDir.absolutePath
            env["DISPLAY"] = ":0"
            env["HOME"] = "/root"
            env["USER"] = "root"
            env["TERM"] = "xterm-256color"
            env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            env["XDG_RUNTIME_DIR"] = "/tmp/runtime-root"
            env["TMPDIR"] = "/tmp"

            val proc = pb.start()
            val exitCode = proc.waitFor()
            Log.i(TAG, "updateGeometry executed inside container with exit code: $exitCode")
            exitCode == 0
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update dynamic geometry inside container", e)
            false
        }
    }

    /**
     * Instantly suspends the active container using process freezing.
     */
    fun suspendActiveInstance(): Boolean {
        if (stopping) return false
        val instance = activeInstance ?: return false
        val success = processController.suspendContainer()
        if (success) {
            updateState(instance.id, ContainerState.SUSPENDED)
        }
        return success
    }

    /**
     * Instantly unfreezes the paused container.
     */
    fun resumeActiveInstance(): Boolean {
        if (stopping) return false
        val instance = activeInstance ?: return false
        val success = processController.resumeContainer()
        if (success) {
            updateState(instance.id, ContainerState.RUNNING)
        }
        return success
    }

    /**
     * Gracefully stops the container.
     */
    fun stopActiveInstance(): Boolean {
        if (activeInstance == null || stopping) return false
        stopping = true
        // Acquire before returning when idle, so an immediate restart queues behind
        // shutdown instead of sharing sockets/rootfs with the previous process.
        managerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            launchMutex.withLock {
                withContext(Dispatchers.IO) {
                    val instance = activeInstance
                    val stoppedProcess = containerProcess
                    try {
                        processController.resumeContainer()
                        processController.shutdownContainer()
                        stoppedProcess?.destroy()
                        delay(500)
                        processController.killForce()
                        if (stoppedProcess?.isAlive == true) stoppedProcess.destroyForcibly()
                        stoppedProcess?.waitFor()
                    } finally {
                        containerProcess = null
                        processController.clearActiveProcess()
                        activeInstance = null
                        displayEndpoint = null
                        stopping = false
                        instance?.let {
                            AppLogger.log(TAG, "Session stopped", it.id)
                            updateState(it.id, ContainerState.STOPPED)
                        }
                    }
                }
            }
        }
        return true
    }

    private fun updateState(id: String, state: ContainerState) {
        _currentState.update { it + (id to state) }
    }

    private fun logRuntimeResources(pid: Int, startedAt: Long, log: (String) -> Unit) {
        // No subprocesses: spawning ps periodically would itself add Android
        // child-process pressure. SELinux can hide entries; counts are a lower bound.
        try {
            val entries = File("/proc").listFiles().orEmpty()
                .asSequence().filter { it.name.toIntOrNull() != null }.take(4096)
            var visible = 0
            for (entry in entries) {
                val group = runCatching { GuestProcessStats.processGroup(File(entry, "stat").readText()) }.getOrNull()
                if (group == pid) visible++
            }
            val memory = android.app.ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memory)
            val runtime = Runtime.getRuntime()
            log("Resources: uptime=${(android.os.SystemClock.elapsedRealtime() - startedAt) / 1000}s, visible guest-group processes=$visible (restricted /proc; not all descendants), Android available=${memory.availMem / 1048576} MiB, lowMemory=${memory.lowMemory}, app Java heap=${(runtime.totalMemory() - runtime.freeMemory()) / 1048576}/${runtime.maxMemory() / 1048576} MiB")
        } catch (e: Exception) {
            Log.d(TAG, "Resource sample unavailable: ${e.javaClass.simpleName}")
        }
    }

    private fun refreshGuestDns(tmpDir: File, log: (String) -> Unit) {
        try {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = connectivity.activeNetwork
            val properties = network?.let(connectivity::getLinkProperties)
            if (android.os.Build.VERSION.SDK_INT >= 28 && properties?.isPrivateDnsActive == true) {
                log("Android Private DNS is active; guest libc does not inherit encrypted Android DNS. Keeping existing guest resolver configuration; networking requires device verification.")
                return
            }
            val config = GuestDns.configuration(properties?.dnsServers.orEmpty())
            val resolver = File(tmpDir, "resolv.conf")
            val temporary = java.nio.file.Files.createTempFile(tmpDir.toPath(), "resolv-", ".tmp")
            try {
                java.nio.file.Files.write(temporary, config.toByteArray())
                java.nio.file.Files.move(temporary, resolver.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally {
                java.nio.file.Files.deleteIfExists(temporary)
            }
            log("Guest DNS refreshed from the active Android network. Restart the instance after switching networks.")
        } catch (e: Exception) {
            log("Could not refresh guest DNS: ${e.message}. Existing resolver configuration retained.")
        }
    }
}
