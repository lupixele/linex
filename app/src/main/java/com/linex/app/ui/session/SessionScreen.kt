package com.linex.app.ui.session

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
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
    var fullscreen by rememberSaveable(instance.id) { mutableStateOf(false) }
    var landscape by rememberSaveable(instance.id) { mutableStateOf(false) }
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, fullscreen) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val oldBehavior = controller?.systemBarsBehavior
        if (window != null && controller != null) {
            WindowCompat.setDecorFitsSystemWindows(window, !fullscreen)
            if (fullscreen) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (window != null && controller != null) {
                controller.show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, true)
                oldBehavior?.let { controller.systemBarsBehavior = it }
            }
        }
    }
    DisposableEffect(activity, landscape) {
        val previous = activity?.requestedOrientation
        if (landscape) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (previous != null) activity?.requestedOrientation = previous }
    }
    LaunchedEffect(drawerState.isOpen, showLogs) {
        if (drawerState.isOpen || showLogs) desktop?.releaseInput()
    }
    BackHandler {
        if (drawerState.isOpen) scope.launch { drawerState.close() }
        else if (fullscreen) fullscreen = false
        else onDetach()
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
                onViewLogs = { showLogs = true },
                fullscreen = fullscreen,
                onToggleFullscreen = { fullscreen = !fullscreen; scope.launch { drawerState.close() } },
                landscape = landscape,
                onToggleLandscape = { landscape = !landscape }
            )
        }
    ) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(contentWindowInsets = if (fullscreen) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets, topBar = {
                if (!fullscreen) {
                TopAppBar(
                    title = { Text(instance.name, maxLines = 1) },
                    navigationIcon = { IconButton(onClick = onDetach) { Icon(Icons.Default.ArrowBack, "Back to instances") } },
                    actions = {
                        TextButton(onClick = { desktop?.toggleKeyboard() }, enabled = connected) { Text("Keyboard") }
                        IconButton(onClick = { fullscreen = true }) { Icon(Icons.Default.Fullscreen, "Enter fullscreen") }
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, "Session controls") }
                    }
                )
                }
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                    if (!connected) {
                        Text(status, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!fullscreen || !connected) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
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
            if (fullscreen) {
                FilledIconButton(
                    onClick = { scope.launch { drawerState.open() } },
                    modifier = Modifier.align(Alignment.TopEnd).displayCutoutPadding().padding(8.dp)
                ) { Icon(Icons.Default.Menu, "Session controls and exit fullscreen") }
            }
        }
    }
    if (showLogs) LogViewerDialog(selectedInstance = instance, onDismiss = { showLogs = false })
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
