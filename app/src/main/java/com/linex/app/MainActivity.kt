package com.linex.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.linex.app.core.StorageEngine
import com.linex.app.data.*
import com.linex.app.service.LinuxContainerService
import com.linex.app.ui.hub.HubScreen
import com.linex.app.ui.session.SessionScreen
import com.linex.app.ui.theme.LinexTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {

    private var containerService by mutableStateOf<LinuxContainerService?>(null)
    private var isServiceBound by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Permission result handled
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LinuxContainerService.LocalBinder
            containerService = binder.getService()
            isServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            containerService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request POST_NOTIFICATIONS runtime permission on Android 13+ (API 33+)
        checkNotificationPermission()

        // Bind Foreground Container Service
        val serviceIntent = Intent(this, LinuxContainerService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            LinexTheme {
                val coroutineScope = rememberCoroutineScope()
                val instanceRepository = remember { InstanceRepository(applicationContext) }
                var instances by remember { mutableStateOf(instanceRepository.loadInstancesSync()) }
                var activeSessionInstance by remember { mutableStateOf<LinuxInstance?>(null) }
                var launchLogMessage by remember { mutableStateOf<String?>(null) }
                val snackbarHostState = remember { SnackbarHostState() }

                fun persistInstances(updated: List<LinuxInstance>) {
                    instances = updated
                    coroutineScope.launch(Dispatchers.IO) {
                        instanceRepository.saveInstances(updated)
                    }
                }

                LaunchedEffect(launchLogMessage) {
                    launchLogMessage?.let { msg ->
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(msg)
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (activeSessionInstance != null) {
                        val currentInst = activeSessionInstance!!
                        SessionScreen(
                            instance = currentInst,
                            onSuspend = {
                                containerService?.containerManager?.suspendActiveInstance()
                                persistInstances(instances.map {
                                    if (it.id == currentInst.id) it.copy(state = ContainerState.SUSPENDED) else it
                                })
                                activeSessionInstance = null
                            },
                            onShutdown = {
                                containerService?.containerManager?.stopActiveInstance()
                                persistInstances(instances.map {
                                    if (it.id == currentInst.id) it.copy(state = ContainerState.STOPPED) else it
                                })
                                activeSessionInstance = null
                            },
                            onRestart = {
                                coroutineScope.launch {
                                    containerService?.containerManager?.stopActiveInstance()
                                    persistInstances(instances.map {
                                        if (it.id == currentInst.id) it.copy(state = ContainerState.STARTING) else it
                                    })
                                    val success = containerService?.containerManager?.launchInstance(currentInst) { logLine ->
                                        launchLogMessage = logLine
                                    } ?: false
                                    val finalState = if (success) ContainerState.RUNNING else ContainerState.STOPPED
                                    persistInstances(instances.map {
                                        if (it.id == currentInst.id) it.copy(state = finalState) else it
                                    })
                                }
                            }
                        )
                    } else {
                        HubScreen(
                            instances = instances,
                            storageEngine = containerService?.storageEngine,
                            rootfsDownloader = containerService?.containerManager?.rootfsDownloader,
                            onLaunchInstance = { inst ->
                                coroutineScope.launch {
                                    if (inst.state == ContainerState.SUSPENDED) {
                                        containerService?.containerManager?.resumeActiveInstance()
                                        persistInstances(instances.map {
                                            if (it.id == inst.id) it.copy(state = ContainerState.RUNNING) else it
                                        })
                                        activeSessionInstance = inst.copy(state = ContainerState.RUNNING)
                                    } else {
                                        persistInstances(instances.map {
                                            if (it.id == inst.id) it.copy(state = ContainerState.STARTING) else it
                                        })
                                        val success = containerService?.containerManager?.launchInstance(inst) { logLine ->
                                            launchLogMessage = logLine
                                        } ?: false
                                        val finalState = if (success) ContainerState.RUNNING else ContainerState.STOPPED
                                        persistInstances(instances.map {
                                            if (it.id == inst.id) it.copy(state = finalState) else it
                                        })
                                        if (success) {
                                            activeSessionInstance = inst.copy(state = ContainerState.RUNNING)
                                        } else {
                                            Toast.makeText(this@MainActivity, "Failed to launch ${inst.name}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            onSuspendInstance = { inst ->
                                containerService?.containerManager?.suspendActiveInstance()
                                persistInstances(instances.map {
                                    if (it.id == inst.id) it.copy(state = ContainerState.SUSPENDED) else it
                                })
                            },
                            onStopInstance = { inst ->
                                containerService?.containerManager?.stopActiveInstance()
                                persistInstances(instances.map {
                                    if (it.id == inst.id) it.copy(state = ContainerState.STOPPED) else it
                                })
                            },
                            onCloneInstance = { inst ->
                                val newId = UUID.randomUUID().toString()
                                val cloned = inst.copy(
                                    id = newId,
                                    name = "${inst.name} (Clone)",
                                    state = ContainerState.STOPPED
                                )
                                coroutineScope.launch(Dispatchers.IO) {
                                    val engine = containerService?.storageEngine ?: StorageEngine(applicationContext)
                                    engine.cloneInstance(inst.id, newId) {}
                                }
                                persistInstances(instances + cloned)
                            },
                            onDeleteInstance = { inst ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    val engine = containerService?.storageEngine ?: StorageEngine(applicationContext)
                                    engine.deleteInstance(inst.id)
                                }
                                persistInstances(instances.filter { it.id != inst.id })
                            },
                            onCreateInstance = { newInst ->
                                persistInstances(instances + newInst)
                            }
                        )
                    }

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onDestroy() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onDestroy()
    }
}
