package com.linex.app.ui.hub

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linex.app.core.RootfsDownloader
import com.linex.app.core.StorageEngine
import com.linex.app.data.ContainerState
import com.linex.app.data.LinuxInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
    rootfsDownloader: RootfsDownloader? = null
) {
    val context = LocalContext.current
    val engine = remember(storageEngine) { storageEngine ?: StorageEngine(context.applicationContext) }
    val downloader = remember(rootfsDownloader, engine) { rootfsDownloader ?: RootfsDownloader(engine) }
    val coroutineScope = rememberCoroutineScope()

    var showCreateDialog by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var selectedLogInstance by remember { mutableStateOf<LinuxInstance?>(null) }
    var downloadingInstance by remember { mutableStateOf<LinuxInstance?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadStatus by remember { mutableStateOf("Preparing download...") }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    val handleLaunchOrResume: (LinuxInstance) -> Unit = { instance ->
        AppLogger.log("HubScreen", "Launch tapped for instance: ${instance.name} (${instance.id})", instance.id)
        if (instance.state == ContainerState.SUSPENDED) {
            AppLogger.log("HubScreen", "Instance is suspended. Resuming...", instance.id)
            onLaunchInstance(instance)
        } else {
            val isReady = engine.isInstanceInitialized(instance.id)
            AppLogger.log("HubScreen", "Checking if instance initialized: $isReady", instance.id)
            if (isReady) {
                AppLogger.log("HubScreen", "Instance is ready. Launching session...", instance.id)
                onLaunchInstance(instance)
            } else {
                // Instance requires rootfs download before first launch
                AppLogger.log("HubScreen", "Instance not initialized. Starting download dialog for ${instance.distro.displayName}", instance.id)
                downloadingInstance = instance
                downloadProgress = 0f
                downloadStatus = "Connecting to server..."
                downloadJob?.cancel()
                downloadJob = coroutineScope.launch {
                    try {
                        downloader.download(instance.id, instance.distro.rootfsDownloadUrl).collect { progress ->
                            downloadProgress = progress
                            downloadStatus = if (progress < 0.99f) {
                                "Downloading ${instance.distro.displayName} (${(progress * 100).toInt()}%)..."
                            } else {
                                "Unpacking and configuring rootfs..."
                            }
                        }

                        // Verify instance is initialized before launching
                        val readyAfterExtract = engine.isInstanceInitialized(instance.id)
                        AppLogger.log("HubScreen", "Extraction finished. isInstanceInitialized: $readyAfterExtract", instance.id)
                        if (readyAfterExtract) {
                            downloadingInstance = null
                            onLaunchInstance(instance)
                        } else {
                            val err = "Rootfs extracted but initialization check failed for ${instance.name}. Check Diagnostic Logs."
                            AppLogger.log("HubScreen", "ERROR: $err", instance.id)
                            Log.e("HubScreen", err)
                            downloadStatus = err
                        }
                    } catch (e: CancellationException) {
                        AppLogger.log("HubScreen", "Rootfs download cancelled for ${instance.name}", instance.id)
                        downloadingInstance = null
                    } catch (e: Exception) {
                        val errMsg = e.message ?: "Unknown error"
                        AppLogger.log("HubScreen", "EXCEPTION during download/extract: $errMsg", instance.id)
                        Log.e("HubScreen", "Failed to download/extract rootfs: $errMsg", e)
                        downloadStatus = "Error: $errMsg (Tap Logs icon for details)"
                    }
                }
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
                    IconButton(onClick = { showLogsDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Article,
                            contentDescription = "Diagnostic Logs",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Button(
                        onClick = { showCreateDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Instance")
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
                        text = "Create an Ubuntu or Debian instance to begin.",
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
                    items(instances, key = { it.id }) { instance ->
                        InstanceCard(
                            instance = instance,
                            onLaunchOrResume = handleLaunchOrResume,
                            onSuspend = onSuspendInstance,
                            onStop = onStopInstance,
                            onClone = onCloneInstance,
                            onDelete = onDeleteInstance,
                            onEditSettings = { /* Open config modal */ },
                            onViewLogs = { selectedInst ->
                                selectedLogInstance = selectedInst
                                showLogsDialog = true
                            }
                        )
                    }
                }
            }
        }
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

    if (showCreateDialog) {
        CreateInstanceDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { newInst ->
                showCreateDialog = false
                onCreateInstance(newInst)
            }
        )
    }

    downloadingInstance?.let { instance ->
        DownloadProgressDialog(
            instance = instance,
            progress = downloadProgress,
            statusText = downloadStatus,
            onCancel = {
                downloadJob?.cancel()
                downloadJob = null
                downloadingInstance = null
            }
        )
    }
}
