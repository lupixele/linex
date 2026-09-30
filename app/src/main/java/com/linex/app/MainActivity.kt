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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.linex.app.core.AppLogger
import com.linex.app.core.SetupTask
import com.linex.app.core.SetupStatus
import com.linex.app.data.*
import com.linex.app.service.LinuxContainerService
import com.linex.app.ui.hub.HubScreen
import com.linex.app.ui.session.SessionScreen
import com.linex.app.ui.theme.LinexTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var containerService by mutableStateOf<LinuxContainerService?>(null)
    private var progressRequest by mutableIntStateOf(0)
    private var isServiceBound = false
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            containerService = (service as LinuxContainerService.LocalBinder).getService()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            containerService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkNotificationPermission()
        val serviceIntent = Intent(this, LinuxContainerService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        isServiceBound = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            LinexTheme {
                val scope = rememberCoroutineScope()
                val repository = remember { InstanceRepository(applicationContext) }
                val saveMutex = remember { Mutex() }
                var instances by remember { mutableStateOf<List<LinuxInstance>>(emptyList()) }
                var loaded by remember { mutableStateOf(false) }
                var loadError by remember { mutableStateOf<String?>(null) }
                var loadAttempt by remember { mutableIntStateOf(0) }
                var sessionId by remember { mutableStateOf<String?>(null) }

                val snackbar = remember { SnackbarHostState() }
                val manager = containerService?.containerManager
                val emptyStates = remember { MutableStateFlow<Map<String, ContainerState>>(emptyMap()) }
                val emptySetup = remember { MutableStateFlow<SetupTask?>(null) }
                val setupTask by (containerService?.setupState ?: emptySetup).collectAsState()
                val operationRunning = setupTask?.status == SetupStatus.RUNNING
                val states by (manager?.currentState ?: emptyStates).collectAsState()
                val visibleInstances = instances.map { it.copy(state = states[it.id] ?: ContainerState.STOPPED) }
                val session = visibleInstances.firstOrNull { it.id == sessionId }

                fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }
                fun persist(updated: LinuxInstance) {
                    if (operationRunning) return
                    instances = if (instances.any { it.id == updated.id }) {
                        instances.map { if (it.id == updated.id) updated else it }
                    } else instances + updated
                    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        try {
                            withContext(kotlinx.coroutines.NonCancellable) {
                                saveMutex.withLock { repository.upsertInstance(updated) }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            snackbar.showSnackbar("Could not save instances: ${e.message}")
                        }
                    }
                }
                fun launch(instance: LinuxInstance, restart: Boolean = false) {
                    if (operationRunning) { message("Wait for the current background task to finish."); return }
                    val engine = manager
                    if (engine == null) { message("Container engine is still connecting. Try again shortly."); return }
                    scope.launch {
                        val state = engine.getInstanceState(instance.id)
                        if (state == ContainerState.RUNNING && !restart) { sessionId = instance.id; return@launch }
                        val success = if (state == ContainerState.SUSPENDED && !restart) {
                            engine.resumeActiveInstance()
                        } else {
                            engine.launchInstance(instance) { }
                        }
                        if (success) sessionId = instance.id
                        else snackbar.showSnackbar("${instance.name} could not start. Open its Logs for details.")
                    }
                }

                LaunchedEffect(progressRequest) { if (progressRequest > 0) sessionId = null }
                LaunchedEffect(loadAttempt) {
                    loaded = false
                    loadError = null
                    try {
                        instances = repository.loadInstances()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        loadError = "Could not load saved instances: ${e.message}"
                    } finally { loaded = true }
                }
                LaunchedEffect(setupTask?.instanceId, setupTask?.status) {
                    if (setupTask != null && setupTask?.status != SetupStatus.RUNNING && setupTask?.kind != "setup") loadAttempt++
                }
                LaunchedEffect(sessionId, states) {
                    val id = sessionId
                    if (id != null && states[id] == ContainerState.STOPPED) {
                        sessionId = null
                        snackbar.showSnackbar("Session ended. Open this instance's Logs for details.")
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    if (!loaded) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    } else if (loadError != null) {
                        Column(Modifier.align(Alignment.Center).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(loadError!!)
                            Text("Your saved file has been preserved. Retry loading before making changes.")
                            Button(onClick = { loadAttempt++ }) { Text("Retry loading") }
                        }
                    } else if (session != null) {
                        SessionScreen(
                            instance = session,
                            endpoint = manager?.getDisplayEndpoint(session.id),
                            onSuspend = {
                                if (manager?.suspendActiveInstance() == true) sessionId = null
                                else message("Could not pause the session. See instance logs.")
                            },
                            onShutdown = { manager?.stopActiveInstance(); sessionId = null },
                            onRestart = {
                                manager?.stopActiveInstance()
                                sessionId = null
                                launch(session, restart = true)
                            },
                            onDetach = { sessionId = null }
                        )
                    } else {
                        HubScreen(
                            instances = visibleInstances,
                            storageEngine = containerService?.storageEngine,
                            setupTask = setupTask,
                            progressRequest = progressRequest,
                            onStartSetup = { instance ->
                                val service = containerService
                                if (service == null) message("Background engine is still connecting. Try again shortly.")
                                else {
                                    if (!androidx.core.app.NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled()) {
                                        message("Notifications are disabled. Enable Linex notifications in Android Settings to see background progress.")
                                        checkNotificationPermission()
                                    }
                                    service.startSetup(instance)
                                }
                            },
                            onCancelSetup = { containerService?.cancelSetup(it) },
                            onClearSetup = { containerService?.clearSetup(it) },
                            onLaunchInstance = { launch(it) },
                            onSuspendInstance = {
                                if (manager?.suspendActiveInstance() != true) message("Could not pause the session.")
                            },
                            onStopInstance = { manager?.stopActiveInstance() },
                            onCloneInstance = { source ->
                                val clone = source.copy(id = UUID.randomUUID().toString(), name = "${source.name} (Copy)", state = ContainerState.STOPPED)
                                containerService?.startClone(source, clone)
                            },
                            onDeleteInstance = { instance -> containerService?.startDelete(instance) },
                            onCreateInstance = { persist(it) },
                            onUpdateInstance = { updated -> persist(updated) }
                        )
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
                }

            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        progressRequest++
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onDestroy() {
        if (isServiceBound) { unbindService(serviceConnection); isServiceBound = false }
        super.onDestroy()
    }
}
