package com.linex.app.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** Host app exits are separate from the guest shell's exit status. */
object HostExitDiagnostics {
    fun record(context: Context) {
        AppLogger.log("HostDiagnostics", "Device=${Build.MANUFACTURER} ${Build.MODEL}; Android=${Build.VERSION.RELEASE}; API=${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val preferences = context.getSharedPreferences("host_exit_diagnostics", Context.MODE_PRIVATE)
            val since = preferences.getLong("last_exit", 0L)
            val manager = context.getSystemService(ActivityManager::class.java)
            val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .filter { it.timestamp > since }.sortedBy { it.timestamp }
            exits.forEach {
                AppLogger.log("HostExit", "Previous Android process exit: time=${it.timestamp}; pid=${it.pid}; reason=${it.reason}; status=${it.status}; PSS=${it.pss} KiB; RSS=${it.rss} KiB; description=${it.description?.take(512)}. This is host history, not a diagnosis of the Linux guest.")
            }
            exits.lastOrNull()?.let { preferences.edit().putLong("last_exit", it.timestamp).apply() }
        } catch (e: Exception) {
            AppLogger.log("HostDiagnostics", "Android exit history unavailable: ${e.javaClass.simpleName}")
        }
    }
}
