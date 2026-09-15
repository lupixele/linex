package com.linuxdroid.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.linuxdroid.app.LinuxDroidApp
import com.linuxdroid.app.MainActivity
import com.linuxdroid.app.R
import com.linuxdroid.app.core.ContainerManager
import com.linuxdroid.app.core.ProcessController
import com.linuxdroid.app.core.StorageEngine

class LinuxContainerService : Service() {

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null

    lateinit var storageEngine: StorageEngine
        private set
    lateinit var processController: ProcessController
        private set
    lateinit var containerManager: ContainerManager
        private set

    inner class LocalBinder : Binder() {
        fun getService(): LinuxContainerService = this@LinuxContainerService
    }

    override fun onCreate() {
        super.onCreate()
        storageEngine = StorageEngine(this)
        processController = ProcessController()
        containerManager = ContainerManager(this, storageEngine, processController)

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinuxDroid:ContainerWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("LinuxDroid Engine Active", "Linux container is running")
        startForeground(NOTIFICATION_ID, notification)
        wakeLock?.acquire(10 * 60 * 1000L /*10 mins default*/)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        containerManager.stopActiveInstance()
        super.onDestroy()
    }

    private fun buildNotification(title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, LinuxDroidApp.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
    }
}
