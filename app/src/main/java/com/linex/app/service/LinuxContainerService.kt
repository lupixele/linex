package com.linex.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.linex.app.LinexApp
import com.linex.app.MainActivity
import com.linex.app.core.AppLogger
import com.linex.app.core.BackgroundWorkOwner
import com.linex.app.core.ContainerManager
import com.linex.app.core.ProcessController
import com.linex.app.core.SetupStatus
import com.linex.app.core.SetupTask
import com.linex.app.core.StorageEngine
import com.linex.app.core.SessionCoordinator
import com.linex.app.core.VmImageCatalogue
import com.linex.app.data.InstanceRuntime
import com.linex.vm.images.VmImageInstaller
import kotlinx.coroutines.flow.update
import com.linex.app.data.ContainerState
import com.linex.app.data.InstanceRepository
import com.linex.app.data.LinuxInstance
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

/** Owns long-running work independently of activities and their composition scopes. */
class LinuxContainerService : Service() {
    private val binder = LocalBinder()
    private val workOwner = BackgroundWorkOwner(Dispatchers.Main.immediate)
    private val serviceScope get() = workOwner.scope

    private var destroying = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val mutableSetup = MutableStateFlow<SetupTask?>(null)
    val setupState: StateFlow<SetupTask?> = mutableSetup.asStateFlow()
    private val taskPreferences by lazy { getSharedPreferences("background_operation", MODE_PRIVATE) }
    private var lastNotificationAt = 0L
    private var lastNotificationStage = ""

    lateinit var storageEngine: StorageEngine
        private set
    lateinit var processController: ProcessController
        private set
    lateinit var containerManager: ContainerManager
        private set
    lateinit var sessions: SessionCoordinator
        private set
    lateinit var vmImages: VmImageInstaller
        private set
    private val readyVms = MutableStateFlow<Set<String>>(emptySet())
    val vmReadyInstances: StateFlow<Set<String>> = readyVms.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): LinuxContainerService = this@LinuxContainerService
    }

    override fun onCreate() {
        super.onCreate()
        storageEngine = StorageEngine(this)
        processController = ProcessController()
        containerManager = ContainerManager(this, storageEngine, processController)
        vmImages = VmImageInstaller(filesDir)
        sessions = SessionCoordinator(this, containerManager, vmImages)
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                InstanceRepository(this@LinuxContainerService).loadInstances()
                    .filter { it.runtime == InstanceRuntime.FULL_VM }.forEach { instance ->
                        runCatching { vmImages.readInstalled(instance.id) }.getOrNull()?.let {
                            readyVms.update { ready -> ready + instance.id }
                        }
                    }
            }.onFailure { AppLogger.log("VirtualMachine", "Could not inspect installed VM images: ${it.message}") }
        }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Linex:BackgroundOperation")
            .apply { setReferenceCounted(false) }
        restoreInterruptedWork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Rebinding or opening Linex must not replace operation progress with "Ready".
        publishNotification(force = true)
        if (intent?.action == ACTION_CANCEL) {
            if (intent.getLongExtra(EXTRA_STARTED_AT, -1L) == mutableSetup.value?.startedAtMillis) {
                intent.getStringExtra(EXTRA_INSTANCE_ID)?.let(::cancelSetup)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun startSetup(instance: LinuxInstance) {
        startOperation(instance, "setup", "Preparing setup") {
            if (instance.runtime == InstanceRuntime.FULL_VM) {
                val image = requireNotNull(VmImageCatalogue.current(this@LinuxContainerService)) { "This build has no validated VM image catalogue" }
                require(image.imageId == instance.vmImageId) { "Requested VM image is not available in this release" }
                vmImages.install(instance.id, image, vmProgressReporter())
                readyVms.update { it + instance.id }
            } else containerManager.rootfsDownloader.downloadWithProgress(instance.id, instance.distro.rootfsDownloadUrl)
                .collect { progress ->
                    updateProgress(progress.fraction, progress.message, progress.stage)
                }
        }
    }

    fun startClone(source: LinuxInstance, newInstance: LinuxInstance) {
        startOperation(source, "clone", "Copying instance") {
            sessions.withStoppedInstance(source.id) {
            val operationStarted = mutableSetup.value!!.startedAtMillis
            if (source.runtime == InstanceRuntime.FULL_VM) {
                require(newInstance.runtime == source.runtime && newInstance.vmImageId == source.vmImageId)
                vmImages.clone(source.id, newInstance.id, vmProgressReporter())
                readyVms.update { it + newInstance.id }
            } else storageEngine.cloneInstance(source.id, newInstance.id, onDetail = { count ->
                reportFileProgress(source.id, operationStarted, "Copied $count files", "Copying instance")
            }, onProgress = { })
            val repository = InstanceRepository(this@LinuxContainerService)
            repository.upsertInstance(newInstance)
            }
        }
    }

    fun startDelete(instance: LinuxInstance) {
        startOperation(instance, "delete", "Deleting instance") {
            sessions.withStoppedInstance(instance.id) {
            val operationStarted = mutableSetup.value!!.startedAtMillis
            if (instance.runtime == InstanceRuntime.FULL_VM) {
                vmImages.delete(instance.id, vmProgressReporter())
                readyVms.update { it - instance.id }
            } else storageEngine.deleteInstance(instance.id) { count ->
                reportFileProgress(instance.id, operationStarted, "Deleted $count entries", "Deleting instance")
            }
            val repository = InstanceRepository(this@LinuxContainerService)
            repository.removeInstance(instance.id)
            }
        }
    }

    private fun vmProgressReporter(): (com.linex.vm.images.VmInstallProgress) -> Unit {
        var lastProgress = 0L
        var lastStage = ""
        return { progress ->
            val stage = when (progress.stage) {
                com.linex.vm.images.VmInstallStage.PREPARE -> "Preparing virtual machine"
                com.linex.vm.images.VmInstallStage.DOWNLOAD_KERNEL -> "Downloading Linux kernel"
                com.linex.vm.images.VmInstallStage.DOWNLOAD_INITRAMFS -> "Downloading boot files"
                com.linex.vm.images.VmInstallStage.DOWNLOAD_DISK -> "Downloading desktop image"
                com.linex.vm.images.VmInstallStage.EXPAND_DISK -> "Preparing instance disk"
                com.linex.vm.images.VmInstallStage.VERIFY -> "Verifying instance disk"
                com.linex.vm.images.VmInstallStage.READY -> "Virtual machine ready"
                com.linex.vm.images.VmInstallStage.CLONE_DISK -> "Copying virtual machine"
                com.linex.vm.images.VmInstallStage.DELETE -> "Deleting virtual machine"
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (stage != lastStage || now - lastProgress >= 250 || progress.completedBytes == progress.totalBytes) {
                lastStage = stage; lastProgress = now
                updateProgress(progress.fraction, "$stage · ${progress.completedBytes / 1048576} / ${progress.totalBytes / 1048576} MiB", stage)
            }
        }
    }

    private fun startOperation(instance: LinuxInstance, kind: String, stage: String, operation: suspend () -> Unit) {
        if (mutableSetup.value?.status == SetupStatus.RUNNING || workOwner.isRunning) return
        val now = System.currentTimeMillis()
        mutableSetup.value = SetupTask(instance.id, instance.name, -1f, stage, stage, now, now, SetupStatus.RUNNING, kind)
        persistTask()
        try {
            publishNotification(force = true)
        } catch (e: Exception) {
            mutableSetup.value = mutableSetup.value?.copy(status = SetupStatus.FAILED, stage = "Failed",
                message = "Could not start background work: ${e.message}")
            persistTask()
            AppLogger.log("BackgroundOperation", "Could not start background work: ${e.message}", instance.id)
            return
        }
        workOwner.start(instance.id, kind != "delete") {
            try {
                // Work can exceed ten minutes. Release deterministically on every terminal path.
                wakeLock?.acquire()
                operation()
                currentCoroutineContext().ensureActive()
                finish(SetupStatus.COMPLETE, when (kind) {
                    "clone" -> "Instance copied"
                    "delete" -> "Instance deleted"
                    else -> "Installation complete. Open Linex to start your desktop."
                })
            } catch (e: CancellationException) {
                if (!destroying) finish(SetupStatus.CANCELLED, "Cancelled. Open Linex to retry; completed downloads are retained.")
                throw e
            } catch (e: Exception) {
                finish(SetupStatus.FAILED, e.message ?: "Operation failed. Open instance logs for details.")
            } finally {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        }
    }

    fun cancelSetup(instanceId: String) {
        val task = mutableSetup.value ?: return
        if (task.instanceId == instanceId && task.status == SetupStatus.RUNNING && task.kind != "delete") workOwner.cancel(instanceId)
    }

    fun clearSetup(instanceId: String) {
        val task = mutableSetup.value ?: return
        if (task.instanceId == instanceId && task.status != SetupStatus.RUNNING) {
            mutableSetup.value = null
            taskPreferences.edit().clear().commit()
            publishNotification(force = true)
        }
    }

    private fun reportFileProgress(instanceId: String, startedAt: Long, message: String, stage: String) {
        serviceScope.launch {
            val task = mutableSetup.value
            if (task?.instanceId == instanceId && task.startedAtMillis == startedAt && task.status == SetupStatus.RUNNING) {
                updateProgress(-1f, message, stage)
            }
        }
    }

    private fun updateProgress(fraction: Float, message: String, stage: String) {
        mutableSetup.update { task -> if (task?.status == SetupStatus.RUNNING) task.copy(fraction = fraction, message = message, stage = stage,
            lastProgressAtMillis = System.currentTimeMillis()) else task }
        publishNotification()
    }

    private fun finish(status: SetupStatus, message: String) {
        val task = mutableSetup.value ?: return
        mutableSetup.value = task.copy(status = status, fraction = if (status == SetupStatus.COMPLETE) 1f else task.fraction,
            message = message, stage = when (status) {
                SetupStatus.COMPLETE -> "Complete"
                SetupStatus.CANCELLED -> "Cancelled"
                else -> "Failed"
            }, lastProgressAtMillis = System.currentTimeMillis())
        AppLogger.log("BackgroundOperation", message, task.instanceId)
        persistTask()
        publishNotification(force = true)
    }

    private fun persistTask() {
        val task = mutableSetup.value ?: return
        taskPreferences.edit().putString("id", task.instanceId).putString("name", task.name)
            .putString("kind", task.kind).putString("status", task.status.name)
            .putString("message", task.message).putString("stage", task.stage)
            .putLong("started", task.startedAtMillis).putFloat("fraction", task.fraction).commit()
    }

    private fun restoreInterruptedWork() {
        val id = taskPreferences.getString("id", null) ?: return
        val saved = runCatching { SetupStatus.valueOf(taskPreferences.getString("status", "FAILED")!!) }.getOrDefault(SetupStatus.FAILED)
        val interrupted = saved == SetupStatus.RUNNING
        mutableSetup.value = SetupTask(id, taskPreferences.getString("name", "Linux instance")!!,
            taskPreferences.getFloat("fraction", -1f),
            if (interrupted) "Interrupted when Linex stopped. Open Linex to retry; completed downloads are retained." else taskPreferences.getString("message", "")!!,
            if (interrupted) "Interrupted" else taskPreferences.getString("stage", "Failed")!!,
            taskPreferences.getLong("started", System.currentTimeMillis()), System.currentTimeMillis(),
            if (interrupted) SetupStatus.FAILED else saved, taskPreferences.getString("kind", "setup")!!)
        if (interrupted) {
            AppLogger.log("BackgroundOperation", mutableSetup.value!!.message, id)
            persistTask()
        }
    }

    private fun publishNotification(force: Boolean = false) {
        val task = mutableSetup.value
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && task?.stage == lastNotificationStage && now - lastNotificationAt < 1000L) return
        lastNotificationAt = now
        lastNotificationStage = task?.stage.orEmpty()
        val running = task?.status == SetupStatus.RUNNING
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, LinexApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(task?.let { "${it.name}: ${it.stage}" } ?: "Linex engine")
            .setContentText(task?.message ?: "Ready to manage your Linux sessions")
            .setStyle(NotificationCompat.BigTextStyle().bigText(task?.message ?: "Ready to manage your Linux sessions"))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(running || task == null)
        if (running && task != null) {
            builder.setWhen(task.startedAtMillis).setUsesChronometer(true)
                .setProgress(100, task.progressPercent ?: 0, task.progressPercent == null)
            if (task.kind != "delete") {
                val cancelIntent = Intent(this, LinuxContainerService::class.java).setAction(ACTION_CANCEL)
                    .setData(android.net.Uri.parse("linex://cancel/${task.instanceId}/${task.startedAtMillis}"))
                    .putExtra(EXTRA_INSTANCE_ID, task.instanceId)
                    .putExtra(EXTRA_STARTED_AT, task.startedAtMillis)
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel",
                    PendingIntent.getService(this, 1, cancelIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
        }
        val notification: Notification = builder.build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (running) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, if (running) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        destroying = true
        workOwner.close()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        sessions.close()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        private const val ACTION_CANCEL = "com.linex.app.CANCEL_OPERATION"
        private const val EXTRA_INSTANCE_ID = "instance_id"
        private const val EXTRA_STARTED_AT = "started_at"
    }
}
