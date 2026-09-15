package com.linuxdroid.app.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.sp
import com.linuxdroid.app.data.ContainerState
import com.linuxdroid.app.data.LinuxInstance

@Composable
fun InstanceCard(
    instance: LinuxInstance,
    onLaunchOrResume: (LinuxInstance) -> Unit,
    onSuspend: (LinuxInstance) -> Unit,
    onStop: (LinuxInstance) -> Unit,
    onClone: (LinuxInstance) -> Unit,
    onDelete: (LinuxInstance) -> Unit,
    onEditSettings: (LinuxInstance) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Row: Name + Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (instance.desktop.isTouchOptimized) Icons.Default.PhoneAndroid else Icons.Default.Computer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = instance.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${instance.distro.displayName} • ${instance.desktop.displayName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                // State Badge
                val (badgeColor, statusText) = when (instance.state) {
                    ContainerState.RUNNING -> Pair(Color(0xFF30D158), "RUNNING")
                    ContainerState.SUSPENDED -> Pair(Color(0xFFFF9F0A), "SUSPENDED")
                    ContainerState.STARTING -> Pair(Color(0xFF64D2FF), "BOOTING")
                    ContainerState.STOPPED -> Pair(Color(0xFF8E8E93), "STOPPED")
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = badgeColor.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = statusText,
                        color = badgeColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Specs Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Display", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    Text(instance.resolutionMode.displayName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
                Column {
                    Text("Scaling", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    Text("${instance.dpiScaling} DPI", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
                Column {
                    Text("Memory", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    Text("${instance.ramAllocatedMb} MB", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { onEditSettings(instance) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.secondary)
                    }
                    IconButton(onClick = { onClone(instance) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Clone", tint = MaterialTheme.colorScheme.secondary)
                    }
                    IconButton(onClick = { onDelete(instance) }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (instance.state == ContainerState.RUNNING) {
                        OutlinedButton(
                            onClick = { onSuspend(instance) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Freeze")
                        }
                        Button(
                            onClick = { onStop(instance) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Stop")
                        }
                    } else {
                        Button(
                            onClick = { onLaunchOrResume(instance) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            val btnText = if (instance.state == ContainerState.SUSPENDED) "Resume" else "Launch"
                            val icon = if (instance.state == ContainerState.SUSPENDED) Icons.Default.PlayArrow else Icons.Default.RocketLaunch
                            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(btnText, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
