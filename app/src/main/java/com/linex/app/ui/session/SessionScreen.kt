package com.linex.app.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.viewinterop.AndroidView
import com.linex.app.core.InputBridge
import com.linex.app.core.TouchInputMode
import com.linex.app.data.LinuxInstance

@Composable
fun SessionScreen(
    instance: LinuxInstance,
    onSuspend: () -> Unit,
    onShutdown: () -> Unit,
    onRestart: () -> Unit
) {
    var isSidebarOpen by remember { mutableStateOf(false) }
    var touchMode by remember { mutableStateOf(TouchInputMode.TRACKPAD_EMULATION) }
    var isKeyboardVisible by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputBridge = remember { InputBridge() }

    // Intercept native Android back gesture to toggle sidebar sheet
    BackHandler(enabled = true) {
        isSidebarOpen = !isSidebarOpen
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Native X11 Surface
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                X11SurfaceView(context).apply {
                    bindInputBridge(inputBridge)
                }
            }
        )

        // Dimmed backdrop when sidebar is open
        if (isSidebarOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        isSidebarOpen = false
                    }
            )
        }

        // Slide-out Back-Gesture Sidebar
        AnimatedVisibility(
            visible = isSidebarOpen,
            enter = slideInHorizontally(initialOffsetX = { -it }),
            exit = slideOutHorizontally(targetOffsetX = { -it })
        ) {
            BackGestureSidebar(
                instance = instance,
                currentTouchMode = touchMode,
                isKeyboardVisible = isKeyboardVisible,
                onResume = { isSidebarOpen = false },
                onSuspend = {
                    isSidebarOpen = false
                    keyboardController?.hide()
                    onSuspend()
                },
                onRestart = {
                    isSidebarOpen = false
                    keyboardController?.hide()
                    onRestart()
                },
                onShutdown = {
                    isSidebarOpen = false
                    keyboardController?.hide()
                    onShutdown()
                },
                onToggleKeyboard = {
                    val targetVisible = !isKeyboardVisible
                    isKeyboardVisible = targetVisible
                    if (targetVisible) {
                        keyboardController?.show()
                    } else {
                        keyboardController?.hide()
                    }
                },
                onToggleTouchMode = {
                    touchMode = if (touchMode == TouchInputMode.TRACKPAD_EMULATION) {
                        TouchInputMode.DIRECT_TOUCH
                    } else {
                        TouchInputMode.TRACKPAD_EMULATION
                    }
                    inputBridge.touchMode = touchMode
                }
            )
        }
    }
}
