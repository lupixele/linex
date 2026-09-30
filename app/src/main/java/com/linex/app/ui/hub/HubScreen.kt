package com.linex.app.ui.hub

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linex.app.BuildConfig
import com.linex.app.core.StorageEngine
import com.linex.app.core.SetupTask
import com.linex.app.core.SetupStatus
import com.linex.app.data.ContainerState
import com.linex.app.data.LinuxInstance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(
    instances: List<LinuxInstance>,
    onLaunchInstance: (LinuxInstance) -> Unit,
    onSuspendInstance: (LinuxInstance) -> Unit,
    onStopInstance: (LinuxInstance) -> Unit,
    onCloneInstance: (LinuxInstance) -> Unit,
    onDeleteInstance: (LinuxInstance) -> Unit,
    onCreateInstance: (LinuxInstance) -> Unit,
    storageEngine: StorageEngine? = null,
    setupTask: SetupTask? = null,
    progressRequest: Int = 0,
    onStartSetup: (LinuxInstance) -> Unit,
    onCancelSetup: (String) -> Unit,
    onClearSetup: (String) -> Unit,
    onUpdateInstance: (LinuxInstance) -> Unit = {}
) {
    val context = LocalContext.current
    val engine = remember(storageEngine) { storageEngine ?: StorageEngine(context.applicationContext) }
    var editingInstance by remember { mutableStateOf<LinuxInstance?>(null) }
    var deletingInstance by remember { mutableStateOf<LinuxInstance?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var selectedLogInstance by remember { mutableStateOf<LinuxInstance?>(null) }
    var showSetup by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(progressRequest) { if (progressRequest > 0) showSetup = true }
    val setupRunning = setupTask?.status == SetupStatus.RUNNING

    val handleLaunchOrResume: (LinuxInstance) -> Unit = { instance ->
        if (!setupRunning && instance.state != ContainerState.STARTING) {
            if (instance.state == ContainerState.SUSPENDED || instance.state == ContainerState.RUNNING ||
                engine.isInstanceInitialized(instance.id)) {
                onLaunchInstance(instance)
            } else {
                showSetup = true
                onStartSetup(instance)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Linex",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "v${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                },
                actions = {
                    Button(
                        onClick = { showCreateDialog = true },
                        enabled = !setupRunning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            if (instances.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No Linux Instances Found",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Create a Linux instance, then download its system image.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Create First Instance")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    if (setupTask != null) {
                        item(key = "background-progress") {
                            OutlinedCard(onClick = { showSetup = true }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("${setupTask.name}: ${setupTask.stage}", style = MaterialTheme.typography.titleSmall)
                                    Text(setupTask.message, style = MaterialTheme.typography.bodySmall)
                                    if (setupRunning) {
                                        if (setupTask.fraction >= 0f) LinearProgressIndicator(progress = { setupTask.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                                    }
                                    Text("Tap for progress and controls", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    items(instances, key = { it.id }) { instance ->
                        InstanceCard(
                            instance = instance,
                            onLaunchOrResume = handleLaunchOrResume,
                            onSuspend = onSuspendInstance,
                            onStop = onStopInstance,
                            onClone = onCloneInstance,
                            onDelete = { deletingInstance = it },
                            onEditSettings = { editingInstance = it },
                            setupError = setupTask?.takeIf { it.instanceId == instance.id && (it.status == SetupStatus.FAILED || it.status == SetupStatus.CANCELLED) }?.message,
                            busy = setupRunning && setupTask?.instanceId == instance.id,
                            operationsBlocked = setupRunning,
                            onViewLogs = { selectedInst ->
                                selectedLogInstance = selectedInst
                                showLogsDialog = true
                            }
                        )
                        if (setupTask?.instanceId == instance.id) {
                            TextButton(onClick = { showSetup = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (setupRunning) "View progress: ${setupTask.stage}" else "View task result")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog && !setupRunning) {
        CreateInstanceDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { newInst ->
                showCreateDialog = false
                onCreateInstance(newInst)
            }
        )
    }

    val setupInstance = instances.firstOrNull { it.id == setupTask?.instanceId }
    if (showSetup && setupTask != null) {
        DownloadProgressDialog(
            instanceName = setupTask.name,
            operationKind = setupTask.kind,
            progress = setupTask.fraction,
            stage = setupTask.stage,
            startedAtMillis = setupTask.startedAtMillis,
            lastProgressAtMillis = setupTask.lastProgressAtMillis,
            taskStatus = setupTask.status,
            error = setupTask.message.takeIf { setupTask.status == SetupStatus.FAILED || setupTask.status == SetupStatus.CANCELLED },
            logsAvailable = setupInstance != null,
            onViewLogs = { if (setupInstance != null) { selectedLogInstance = setupInstance; showLogsDialog = true } },
            onRetry = { if (setupInstance != null) onStartSetup(setupInstance) },
            statusText = setupTask.message,
            onBackground = { showSetup = false },
            onClose = { showSetup = false; onClearSetup(setupTask.instanceId) },
            onCancel = { onCancelSetup(setupTask.instanceId) }
        )
    }

    editingInstance?.takeIf { !setupRunning }?.let { instance ->
        CreateInstanceDialog(
            onDismiss = { editingInstance = null },
            onCreate = { updated -> editingInstance = null; onUpdateInstance(updated) },
            existingInstance = instance
        )
    }
    deletingInstance?.takeIf { !setupRunning }?.let { instance ->
        AlertDialog(
            onDismissRequest = { deletingInstance = null },
            title = { Text("Delete ${instance.name}?") },
            text = { Text("This permanently removes this instance and its Linux files. Other instances are unaffected.") },
            confirmButton = {
                TextButton(onClick = { deletingInstance = null; onDeleteInstance(instance) }) {
                    Text("Delete instance", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deletingInstance = null }) { Text("Keep instance") } }
        )
    }
    if (showLogsDialog) {
        LogViewerDialog(
            selectedInstance = selectedLogInstance,
            onDismiss = {
                showLogsDialog = false
                selectedLogInstance = null
            }
        )
    }

}
