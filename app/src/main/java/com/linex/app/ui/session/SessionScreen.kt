package com.linex.app.ui.session

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.linex.app.core.AppLogger
import com.linex.app.core.DisplayEndpoint
import com.linex.app.core.DisplayBackend
import com.linex.app.core.DesktopFrameRate
import com.linex.app.data.DisplayBackendPreference
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
    endpoint: DisplayEndpoint? = null,
    processGroup: Int? = null
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showLogs by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var connected by remember(instance.id, endpoint, retry) { mutableStateOf(false) }
    var status by remember(instance.id, endpoint, retry) { mutableStateOf("Preparing desktop. First startup may install display packages; progress is in instance logs.") }
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("desktop_controls", Context.MODE_PRIVATE) }
    var trackpad by rememberSaveable(instance.id) { mutableStateOf(preferences.getBoolean("trackpad.${instance.id}", false)) }
    var showMonitor by rememberSaveable(instance.id) { mutableStateOf(preferences.getBoolean("monitor.${instance.id}", false)) }
    var desktop by remember { mutableStateOf<EmbeddedDesktopView?>(null) }
    val displayMessages = remember { SnackbarHostState() }
    val compatibilityFallback = endpoint?.backend == DisplayBackend.RFB &&
        instance.displayBackend == DisplayBackendPreference.AUTO
    LaunchedEffect(endpoint) {
        if (compatibilityFallback && displayMessages.showSnackbar(
                "Native display could not start. Using RFB at ${DesktopFrameRate.normalized(instance.desktopFps)} FPS target.",
                actionLabel = "Logs", duration = SnackbarDuration.Long
            ) == SnackbarResult.ActionPerformed) showLogs = true
    }
    var fullscreen by rememberSaveable(instance.id) { mutableStateOf(true) }
    var landscape by rememberSaveable(instance.id) { mutableStateOf(true) }
    val activity = context.findActivity()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var sessionVisible by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val controlsVisible = drawerState.currentValue != DrawerValue.Closed || drawerState.targetValue != DrawerValue.Closed
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val totalRamMb = remember(context) {
        val memory = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memory)
        memory.totalMem / 1048576
    }
    val budgetMb = com.linex.app.core.MemoryBudget.resolveMb(instance, totalRamMb)
    DisposableEffect(lifecycle, desktop) {
        val observer = LifecycleEventObserver { _, _ ->
            sessionVisible = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!sessionVisible) desktop?.releaseInput()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(activity, instance.desktopFps) {
        val window = activity?.window
        val previous = window?.attributes?.preferredRefreshRate
        if (window != null) {
            val rates = window.decorView.display?.supportedModes?.map { it.refreshRate }.orEmpty()
            val requested = com.linex.app.core.DesktopFrameRate.preferredRefresh(instance.desktopFps, rates)
            window.attributes = window.attributes.apply { preferredRefreshRate = requested }
            AppLogger.log("Display", "Frame limit ${com.linex.app.core.DesktopFrameRate.normalized(instance.desktopFps)} FPS; requested display refresh $requested Hz", instance.id)
        }
        onDispose {
            if (window != null && previous != null) window.attributes = window.attributes.apply { preferredRefreshRate = previous }
        }
    }
    DisposableEffect(activity, fullscreen, lifecycle) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val oldBehavior = controller?.systemBarsBehavior
        val oldCutout = if (Build.VERSION.SDK_INT >= 28) window?.attributes?.layoutInDisplayCutoutMode else null
        fun applyFullscreen() {
            if (window == null || controller == null) return
            WindowCompat.setDecorFitsSystemWindows(window, !fullscreen)
            if (Build.VERSION.SDK_INT >= 28) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = if (fullscreen) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        else oldCutout ?: WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
            if (fullscreen) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else controller.show(WindowInsetsCompat.Type.systemBars())
        }
        if (window != null && controller != null) {
            applyFullscreen()
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) applyFullscreen() }
        val focusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused && fullscreen) applyFullscreen()
        }
        lifecycle.addObserver(observer)
        window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener(focusListener)
        onDispose {
            lifecycle.removeObserver(observer)
            window?.decorView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
            if (window != null && controller != null) {
                controller.show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, true)
                if (Build.VERSION.SDK_INT >= 28 && oldCutout != null) window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = oldCutout }
                oldBehavior?.let { controller.systemBarsBehavior = it }
            }
        }
    }
    DisposableEffect(activity, landscape) {
        val previous = activity?.requestedOrientation
        if (landscape) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (previous != null) activity?.requestedOrientation = previous }
    }
    LaunchedEffect(controlsVisible, showLogs, desktop, sessionVisible) {
        desktop?.inputEnabled = !controlsVisible && !showLogs && sessionVisible
        if (controlsVisible || showLogs || !sessionVisible) {
            desktop?.releaseInput()
            desktop?.clearFocus()
        }
        else if (sessionVisible) desktop?.requestFocus()
    }
    BackHandler {
        if (drawerState.isOpen) scope.launch { drawerState.close() }
        else scope.launch { drawerState.open() }
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            BackGestureSidebar(
                instance = instance,
                currentTouchMode = if (trackpad) TouchInputMode.TRACKPAD_EMULATION else TouchInputMode.DIRECT_TOUCH,
                isKeyboardVisible = keyboardVisible,
                onResume = { scope.launch { drawerState.close() } },
                onSuspend = onSuspend,
                onRestart = onRestart,
                onShutdown = onShutdown,
                onToggleKeyboard = {
                    val hide = keyboardVisible
                    scope.launch {
                        drawerState.close()
                        desktop?.requestFocus()
                        if (hide) desktop?.hideKeyboard() else desktop?.showKeyboard()
                    }
                },
                onToggleTouchMode = {
                    trackpad = !trackpad
                    preferences.edit().putBoolean("trackpad.${instance.id}", trackpad).apply()
                    desktop?.trackpadMode = trackpad
                },
                displayConnected = connected,
                onDetach = onDetach,
                onViewLogs = { showLogs = true },
                fullscreen = fullscreen,
                onToggleFullscreen = { fullscreen = !fullscreen; scope.launch { drawerState.close() } },
                landscape = landscape,
                onToggleLandscape = { landscape = !landscape },
                resourceMonitor = showMonitor,
                onToggleResourceMonitor = {
                    showMonitor = !showMonitor
                    preferences.edit().putBoolean("monitor.${instance.id}", showMonitor).apply()
                },
                ramBudgetMb = budgetMb,
                displayBackend = endpoint?.backend
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
                        TextButton(onClick = { if (keyboardVisible) desktop?.hideKeyboard() else desktop?.showKeyboard() }, enabled = connected) { Text("Keyboard") }
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
                                        view.connect(endpoint, instance.desktopFps)
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
                ) { Icon(Icons.Default.Menu, "Session controls") }
            }
            SessionResourceOverlay(
                view = desktop,
                processGroup = processGroup,
                enabled = showMonitor && connected,
                sessionVisible = sessionVisible && !showLogs,
                displayBackend = endpoint?.backend,
                modifier = Modifier.align(Alignment.TopStart).displayCutoutPadding().statusBarsPadding()
                    .padding(start = 8.dp, top = if (fullscreen) 8.dp else 72.dp)
            )
            SnackbarHost(displayMessages, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp))
        }
    }
    if (showLogs) LogViewerDialog(selectedInstance = instance, onDismiss = { showLogs = false })
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
