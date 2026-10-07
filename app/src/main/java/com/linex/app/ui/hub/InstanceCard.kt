package com.linex.app.ui.hub

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linex.app.data.ContainerState
import com.linex.app.data.LinuxInstance
import com.linex.app.data.InstanceRuntime

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstanceCard(
    instance: LinuxInstance,
    onLaunchOrResume: (LinuxInstance) -> Unit,
    onSuspend: (LinuxInstance) -> Unit,
    onStop: (LinuxInstance) -> Unit,
    onClone: (LinuxInstance) -> Unit,
    onDelete: (LinuxInstance) -> Unit,
    onEditSettings: (LinuxInstance) -> Unit,
    onViewLogs: (LinuxInstance) -> Unit = {},
    setupError: String? = null,
    busy: Boolean = false,
    operationsBlocked: Boolean = false
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val stopped = instance.state == ContainerState.STOPPED
    val starting = instance.state == ContainerState.STARTING
    val status = when {
        busy -> "Setting up"
        setupError != null -> "Setup needs attention"
        starting -> "Starting"
        instance.state == ContainerState.RUNNING -> "Running"
        instance.state == ContainerState.SUSPENDED -> "Suspended"
        else -> "Stopped"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(instance.name, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(instance.distro.displayName, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }, enabled = !busy && !operationsBlocked) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Actions for ${instance.name}")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("Edit settings") }, enabled = stopped && !operationsBlocked,
                            onClick = { menuExpanded = false; onEditSettings(instance) },
                            leadingIcon = { Icon(Icons.Default.Settings, null) })
                        DropdownMenuItem(text = { Text("Clone instance") }, enabled = stopped && !operationsBlocked,
                            onClick = { menuExpanded = false; onClone(instance) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
                        DropdownMenuItem(text = { Text("Delete instance") }, enabled = stopped && !operationsBlocked,
                            onClick = { menuExpanded = false; onDelete(instance) },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, null) })
                    }
                }
            }
            Text(status, style = MaterialTheme.typography.labelLarge,
                color = if (setupError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text(if (instance.runtime == InstanceRuntime.FULL_VM) "Full virtual machine" else "PRoot · legacy",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${instance.desktop.displayName}\n${instance.resolutionMode.displayName} · ${instance.dpiScaling} DPI",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (setupError != null) {
                Text(setupError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Text("Open this instance’s logs for details, then retry setup.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { onLaunchOrResume(instance) }, enabled = !busy && !starting && !operationsBlocked) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(when {
                        busy -> "Setting up…"
                        starting -> "Starting…"
                        setupError != null -> "Retry setup"
                        instance.state == ContainerState.RUNNING -> "Open session"
                        instance.state == ContainerState.SUSPENDED -> "Resume"
                        else -> "Launch"
                    })
                }
                OutlinedButton(onClick = { onViewLogs(instance) }) {
                    Icon(Icons.Default.Article, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Logs")
                }
                if (instance.state == ContainerState.RUNNING) {
                    TextButton(onClick = { onSuspend(instance) }) { Text("Suspend") }
                }
                if (instance.state == ContainerState.RUNNING || instance.state == ContainerState.SUSPENDED) {
                    TextButton(onClick = { onStop(instance) }) { Text("Stop") }
                }
            }
        }
    }
}
