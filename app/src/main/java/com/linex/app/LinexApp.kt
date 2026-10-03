package com.linex.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.linex.app.core.AppLogger

class LinexApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // The disposable X server must not rotate journals or record its own intentional
        // process exit as a crash of the main application.
        val processName = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
            else runCatching { java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000') }.getOrNull()
        if (processName?.endsWith(":x11") == true) return
        AppLogger.init(applicationContext)
        val version = com.linex.app.core.AppVersion.read(applicationContext)
        AppLogger.log("LinexApp", "Linex Application initialized (installed v${version.name} code ${version.code})")
        createNotificationChannels()
        Thread({ com.linex.app.core.HostExitDiagnostics.record(applicationContext) }, "LinexExitDiagnostics").start()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "linux_container_channel"
    }
}
