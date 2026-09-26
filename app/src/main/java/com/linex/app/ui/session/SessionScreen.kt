package com.linex.app.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.linex.app.core.AppLogger
import com.linex.app.core.DisplayEndpoint
import com.linex.app.core.TouchInputMode
import com.linex.app.data.LinuxInstance
import com.linex.app.ui.hub.LogViewerDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    instance: LinuxInstance,
    onSuspend: () -> Unit,
    onShutdown: () -> Unit,
    onRestart: () -> Unit,
    onDetach: () -> Unit,
    endpoint: DisplayEndpoint? = null
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showLogs by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var connected by remember(instance.id, endpoint, retry) { mutableStateOf(false) }
    var status by remember(instance.id, endpoint, retry) { mutableStateOf("Preparing desktop. First startup may install display packages; progress is in instance logs.") }
    var trackpad by remember { mutableStateOf(false) }
    var desktop by remember { mutableStateOf<EmbeddedDesktopView?>(null) }
    LaunchedEffect(drawerState.isOpen, showLogs) {
        if (drawerState.isOpen || showLogs) desktop?.releaseInput()
    }
    BackHandler {
        if (drawerState.isOpen) scope.launch { drawerState.close() } else onDetach()
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            BackGestureSidebar(
                instance = instance,
                currentTouchMode = if (trackpad) TouchInputMode.TRACKPAD_EMULATION else TouchInputMode.DIRECT_TOUCH,
                isKeyboardVisible = false,
                onResume = { scope.launch { drawerState.close() } },
                onSuspend = onSuspend,
                onRestart = onRestart,
                onShutdown = onShutdown,
                onToggleKeyboard = { desktop?.toggleKeyboard(); scope.launch { drawerState.close() } },
                onToggleTouchMode = { trackpad = !trackpad; desktop?.trackpadMode = trackpad },
                displayConnected = connected,
                onDetach = onDetach,
                onViewLogs = { showLogs = true }
            )
        }
    ) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(instance.name, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onDetach) { Icon(Icons.Default.ArrowBack, "Back to instances") } },
                actions = {
                    TextButton(onClick = { desktop?.toggleKeyboard() }, enabled = connected) { Text("Keyboard") }
                    IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, "Session controls") }
                }
            )
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                if (!connected) {
                    Text(status, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { showLogs = true }) { Text("Instance logs") }
                    if (!connected) TextButton(onClick = { retry++ }, enabled = endpoint != null) { Text("Retry display") }
                }
                key(instance.id, endpoint, retry) {
                    if (endpoint != null) {
                        AndroidView(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            onRelease = { view -> view.disconnect(); if (desktop === view) desktop = null },
                            factory = { context ->
                                EmbeddedDesktopView(context).also { view ->
                                    desktop = view
                                    view.trackpadMode = trackpad
                                    var lastStatus = ""
                                    view.onConnection = { ready, message ->
                                        connected = ready; status = message
                                        if (lastStatus != message) {
                                            AppLogger.log("Display", message, instance.id)
                                            lastStatus = message
                                        }
                                    }
                                    view.connect(endpoint)
                                }
                            }
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }
    if (showLogs) LogViewerDialog(selectedInstance = instance, onDismiss = { showLogs = false })
}
