package com.linex.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class LinexApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(applicationContext)
        AppLogger.log("LinexApp", "Linex Application initialized (Build v${BuildConfig.VERSION_NAME} code ${BuildConfig.VERSION_CODE})")
        createNotificationChannels()
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
