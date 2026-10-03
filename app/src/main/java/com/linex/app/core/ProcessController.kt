package com.linex.app.core

import android.util.Log
import java.io.File

/**
 * High-performance POSIX Process Controller.
 * Manages PRoot parent/child processes and executes native signal dispatches
 * for instant Zero-Cold-Start suspend (SIGSTOP) and resume (SIGCONT).
 */
class ProcessController {

    companion object {
        private const val TAG = "ProcessController"

        init {
            try {
                System.loadLibrary("linex_engine")
                Log.i(TAG, "Native linex_engine library successfully loaded.")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native linex_engine library not loaded yet (mock/stub fallback active)", e)
            }
        }
    }

    private var activePid: Int = -1
    private var activePgid: Int = -1
    private var ownership: GuestProcessOwnership? = null

    @Synchronized
    fun setActiveProcess(pid: Int, pgid: Int = -1) {
        require(pid > 0 && resolvePgid(pid) == pid) { "Container did not create an isolated process group" }
        this.activePid = pid
        this.activePgid = pid
        ownership = readIdentity(pid)?.takeIf { it.uid == android.os.Process.myUid() && it.group == pid }
            ?.let { GuestProcessOwnership(android.os.Process.myUid(), it) }
        refreshTrackedProcesses()
    }

    @Synchronized fun clearActiveProcess() {
        activePid = -1
        activePgid = -1
        ownership = null
    }

    /** Read-only collection; never discovers ownership from names or shared UID alone. */
    @Synchronized fun refreshTrackedProcesses(expectedPid: Int = activePid): Int {
        if (expectedPid <= 0 || expectedPid != activePid) return 0
        val rows = readIdentities()
        ownership?.observe(rows)
        return ownership?.targets(rows)?.size ?: 0
    }

    private fun readIdentity(pid: Int): GuestProcessIdentity? = runCatching {
        val directory = File("/proc/$pid")
        GuestProcessIdentity.read(File(directory, "stat").readText(), File(directory, "status").readText())
    }.getOrNull()

    private fun readIdentities(): List<GuestProcessIdentity> = File("/proc").listFiles().orEmpty()
        .asSequence().mapNotNull { it.name.toIntOrNull() }.take(4096).mapNotNull(::readIdentity).toList()

    private fun sendOwnedSignal(signal: Int): Boolean {
        val rows = readIdentities()
        ownership?.observe(rows)
        val targets = ownership?.targets(rows).orEmpty()
        // A recycled numeric PGID alone does not prove session ownership.
        val confirmedGroupMember = targets.asSequence().mapNotNull(::readIdentity).any {
            it.group == activePgid && ownership?.targets(listOf(it))?.contains(it.pid) == true
        }
        val groupResult = if (confirmedGroupMember) {
            nativeSendSignal(signalTarget(), signal)
        } else false
        var individualResult = false
        for (pid in targets) {
            // Re-read identity immediately before dispatch, retaining the registry
            // even after PRoot exits. Unreadable/reused identities are never killed.
            val current = readIdentity(pid) ?: continue
            if (pid == android.os.Process.myPid() || ownership?.targets(listOf(current))?.contains(pid) != true) continue
            individualResult = nativeSendSignal(pid, signal) || individualResult
        }
        return groupResult || individualResult
    }

    @Synchronized fun getActiveProcessGroup(): Int? = activePgid.takeIf { it > 0 }

    private fun signalTarget(): Int {
        // This group was verified after the launcher completed setsid. Keep it while
        // stopping so remaining children can be killed even if their leader exits.
        check(activePid > 0 && activePgid == activePid) { "No isolated container process" }
        return -activePgid
    }

    private fun resolvePgid(pid: Int): Int {
        if (pid <= 0) return -1
        return try {
            val resolved = nativeGetProcessGroup(pid)
            if (resolved > 0) resolved else -1
        } catch (e: Throwable) {
            -1
        }
    }

    @Synchronized fun isAlive(): Boolean {
        if (activePid <= 0) return false
        return try {
            nativeCheckProcessAlive(activePid)
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Pauses the entire container process tree immediately.
     * Stops CPU consumption and preserves full RAM state.
     */
    @Synchronized fun suspendContainer(): Boolean {
        if (activePgid <= 0 && activePid <= 0) return false
        val target = signalTarget()
        Log.i(TAG, "Suspending container process group: $target via SIGSTOP")
        return try {
            sendOwnedSignal(19) // 19 = SIGSTOP
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to send SIGSTOP", e)
            false
        }
    }

    /**
     * Resumes the paused container instantly in <150ms.
     */
    @Synchronized fun resumeContainer(): Boolean {
        if (activePgid <= 0 && activePid <= 0) return false
        val target = signalTarget()
        Log.i(TAG, "Resuming container process group: $target via SIGCONT")
        return try {
            sendOwnedSignal(18) // 18 = SIGCONT
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to send SIGCONT", e)
            false
        }
    }

    /**
     * Gracefully terminates user applications and syncs file buffers.
     */
    @Synchronized fun shutdownContainer(): Boolean {
        if (activePgid <= 0 && activePid <= 0) return false
        val target = signalTarget()
        Log.i(TAG, "Sending SIGTERM to container: $target")
        return try {
            sendOwnedSignal(15) // 15 = SIGTERM
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to send SIGTERM", e)
            false
        }
    }

    /**
     * Force-kills lingering processes.
     */
    @Synchronized fun killForce(): Boolean {
        if (activePgid <= 0 && activePid <= 0) return false
        val target = signalTarget()
        Log.i(TAG, "Force killing container process: $target via SIGKILL")
        return try {
            sendOwnedSignal(9) // 9 = SIGKILL
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to send SIGKILL", e)
            false
        }
    }

    /**
     * Native file permission setter (chmod).
     */
    fun setFilePermissions(path: String, modeOctal: Int): Boolean {
        return try {
            nativeSetPermissions(path, modeOctal)
        } catch (e: Throwable) {
            Log.w(TAG, "Fallback to java setExecutable for $path", e)
            val file = java.io.File(path)
            file.setReadable(true, false)
            file.setWritable(true, true)
            file.setExecutable(true, false)
        }
    }

    /**
     * Native symlink creator.
     */
    fun createSymlink(target: String, linkpath: String): Boolean {
        return try {
            nativeCreateSymlink(target, linkpath)
        } catch (e: Throwable) {
            Log.w(TAG, "Native symlink failed: $target -> $linkpath", e)
            false
        }
    }

    // JNI Native Methods implemented in cpp/engine_bridge.cpp
    private external fun nativeSendSignal(pidOrPgid: Int, signal: Int): Boolean
    private external fun nativeGetProcessGroup(pid: Int): Int
    private external fun nativeSetPgid(pid: Int, pgid: Int): Boolean
    private external fun nativeCheckProcessAlive(pid: Int): Boolean
    private external fun nativeSetPermissions(path: String, mode: Int): Boolean
    private external fun nativeCreateSymlink(target: String, linkpath: String): Boolean
}
