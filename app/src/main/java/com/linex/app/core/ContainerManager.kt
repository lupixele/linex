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

    private var activeInstance: LinuxInstance? = null
    private var containerProcess: Process? = null
    private var logReadingJob: Job? = null

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
    suspend fun launchInstance(instance: LinuxInstance, onLog: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val logWrapper: (String) -> Unit = { msg ->
            AppLogger.log("ContainerManager", msg)
            onLog(msg)
        }
        updateState(instance.id, ContainerState.STARTING)
        activeInstance = instance

        // 1. Ensure runtime assets (scripts and configs) are deployed
        logWrapper("Validating runtime bootstrap scripts...")
        storageEngine.deployAssets(overwrite = false)

        val rootfsDir = storageEngine.getRootfsDirectory(instance.id)
        val tmpDir = storageEngine.getTmpDirectory(instance.id)
        val scriptsDir = storageEngine.scriptsDir

        // Check if rootfs directory is empty or missing or uninitialized
        val rootfsFiles = rootfsDir.listFiles()
        val isRootfsMissingOrEmpty = !rootfsDir.exists() || rootfsFiles == null || rootfsFiles.isEmpty() || !storageEngine.isInstanceInitialized(instance.id)

        if (isRootfsMissingOrEmpty) {
            logWrapper("Rootfs uninitialized. Initiating download for ${instance.distro.displayName}...")
            try {
                var lastReportedPercent = -1
                rootfsDownloader.download(instance.id, instance.distro.rootfsDownloadUrl).collect { progress ->
                    val percent = (progress * 100).toInt()
                    if (percent != lastReportedPercent && (percent % 10 == 0 || percent == 100)) {
                        lastReportedPercent = percent
                        logWrapper("Downloading rootfs: $percent%")
                    }
                }
                logWrapper("Rootfs download and extraction completed successfully.")
            } catch (e: Exception) {
                val errMsg = "Failed to download rootfs: ${e.message}"
                Log.e(TAG, errMsg, e)
                logWrapper("ERROR: $errMsg")
                updateState(instance.id, ContainerState.STOPPED)
                return@withContext false
            }
        }

        // 2. Generate display geometry
        val (width, height) = calculateDisplayGeometry(instance)
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

        try {
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
            pb.redirectErrorStream(true)

            val process = pb.start()
            containerProcess = process

            // Read PID via reflection
            try {
                val pidField = process.javaClass.getDeclaredField("pid")
                pidField.isAccessible = true
                val pid = pidField.getInt(process)
                processController.setActiveProcess(pid)
                logWrapper("Container process initialized with PID $pid")
            } catch (e: Exception) {
                Log.w(TAG, "Unable to extract process PID via reflection", e)
            }

            // Stream logs asynchronously and monitor if process exits prematurely
            logReadingJob?.cancel()
            logReadingJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
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

                // Check process exit status
                try {
                    val exitCode = process.waitFor()
                    Log.w(TAG, "Container process exited with code $exitCode")
                    logWrapper("Container exited (code $exitCode)")
                    updateState(instance.id, ContainerState.STOPPED)
                } catch (e: Exception) {
                    Log.d(TAG, "Process wait interrupted: ${e.message}")
                }
            }

            updateState(instance.id, ContainerState.RUNNING)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch container", e)
            logWrapper("Failed to launch container: ${e.message}")
            updateState(instance.id, ContainerState.STOPPED)
            false
        }
    }

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
        val instance = activeInstance ?: return false
        processController.shutdownContainer()
        logReadingJob?.cancel()
        containerProcess?.destroy()
        containerProcess = null
        updateState(instance.id, ContainerState.STOPPED)
        activeInstance = null
        return true
    }

    private fun updateState(id: String, state: ContainerState) {
        val map = _currentState.value.toMutableMap()
        map[id] = state
        _currentState.value = map
    }
}
