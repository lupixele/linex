package com.linex.app.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linex.app.core.TouchInputMode
import com.linex.app.data.LinuxInstance

@Composable
fun BackGestureSidebar(
    instance: LinuxInstance,
    currentTouchMode: TouchInputMode,
    isKeyboardVisible: Boolean,
    onResume: () -> Unit,
    onSuspend: () -> Unit,
    onRestart: () -> Unit,
    onShutdown: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onToggleTouchMode: () -> Unit,
    displayConnected: Boolean = false,
    onDetach: () -> Unit = {},
    onViewLogs: () -> Unit = {},
    fullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    landscape: Boolean = false,
    onToggleLandscape: () -> Unit = {}
) {
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(320.dp)
            .clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = instance.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Session Controls",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(onClick = onResume) {
                    Icon(Icons.Default.Close, contentDescription = "Close Menu")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(16.dp))

            // Section 1: Session Controls
            Text(
                text = "SESSION POWER",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onResume,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Close controls", color = MaterialTheme.colorScheme.onPrimaryContainer)
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onSuspend,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Pause, contentDescription = null, tint = Color(0xFFFF9F0A))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Freeze & Suspend")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reboot Container")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onShutdown,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Stop instance")
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(16.dp))

            // Section 2: Input & Peripherals
            OutlinedButton(onClick = onToggleFullscreen, modifier = Modifier.fillMaxWidth()) {
                Text(if (fullscreen) "Exit fullscreen" else "Enter fullscreen")
            }
            OutlinedButton(onClick = onToggleLandscape, modifier = Modifier.fillMaxWidth()) {
                Text(if (landscape) "Use device rotation" else "Use landscape")
            }
            Text("The desktop keeps its aspect ratio. For fewer borders, choose a resolution matching your screen in instance settings. Back exits fullscreen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "INPUT & DISPLAY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onToggleKeyboard,
                enabled = displayConnected,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Keyboard, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isKeyboardVisible) "Hide Keyboard" else "Show Keyboard")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onToggleTouchMode,
                enabled = displayConnected,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                val icon = if (currentTouchMode == TouchInputMode.TRACKPAD_EMULATION) Icons.Default.Mouse else Icons.Default.TouchApp
                val label = if (currentTouchMode == TouchInputMode.TRACKPAD_EMULATION) "Mode: Virtual Trackpad" else "Mode: Direct Touch"
                Icon(icon, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(label)
            }

            if (!displayConnected) {
                Text("Input controls require a connected desktop display.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onViewLogs, modifier = Modifier.fillMaxWidth()) { Text("View instance logs") }
            TextButton(onClick = onDetach, modifier = Modifier.fillMaxWidth()) { Text("Back to instances") }
            Spacer(modifier = Modifier.height(16.dp))

            // Footer Diagnostics
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Display: ${instance.resolutionMode.displayName}", style = MaterialTheme.typography.labelSmall)
                    Text("State: ${instance.state.name}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
