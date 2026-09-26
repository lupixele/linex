package com.linex.app.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linex.app.core.TouchInputMode
import com.linex.app.data.ContainerState
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
    onDetach: () -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showLogs by remember { mutableStateOf(false) }
    BackHandler {
        if (drawerState.isOpen) scope.launch { drawerState.close() } else onDetach()
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            BackGestureSidebar(
                instance = instance,
                currentTouchMode = TouchInputMode.TRACKPAD_EMULATION,
                isKeyboardVisible = false,
                onResume = { scope.launch { drawerState.close() } },
                onSuspend = onSuspend,
                onRestart = onRestart,
                onShutdown = onShutdown,
                onToggleKeyboard = {},
                onToggleTouchMode = {},
                displayConnected = false,
                onDetach = onDetach,
                onViewLogs = { showLogs = true }
            )
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Session") },
                    navigationIcon = {
                        IconButton(onClick = onDetach) { Icon(Icons.Default.ArrowBack, "Back to instances") }
                    },
                    actions = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, "Session controls")
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(instance.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("Process: ${instance.state.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    HorizontalDivider()
                    Text("Desktop display is not connected", style = MaterialTheme.typography.titleLarge)
                    Text("This build can manage Linux processes, but its X11 display renderer is not connected. A running process does not yet show a desktop here.",
                        style = MaterialTheme.typography.bodyLarge)
                    Text("Open this instance’s logs to inspect startup, or return to your instances. Returning keeps the process running while Linex remains active.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { showLogs = true }, modifier = Modifier.fillMaxWidth()) { Text("View instance logs") }
                    OutlinedButton(onClick = onDetach, modifier = Modifier.fillMaxWidth()) { Text("Back to instances") }
                }
            }
        }
    }
    if (showLogs) LogViewerDialog(selectedInstance = instance, onDismiss = { showLogs = false })
}
