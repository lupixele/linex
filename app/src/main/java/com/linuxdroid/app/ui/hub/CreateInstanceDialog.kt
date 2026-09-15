package com.linuxdroid.app.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.linuxdroid.app.data.DesktopEnvironment
import com.linuxdroid.app.data.DisplayResolutionMode
import com.linuxdroid.app.data.DistroType
import com.linuxdroid.app.data.LinuxInstance
import java.util.UUID

@Composable
fun CreateInstanceDialog(
    onDismiss: () -> Unit,
    onCreate: (LinuxInstance) -> Unit
) {
    var name by remember { mutableStateOf("My Ubuntu Workstation") }
    var selectedDistro by remember { mutableStateOf(DistroType.UBUNTU_JAMMY) }
    var selectedDesktop by remember { mutableStateOf(DesktopEnvironment.XFCE4) }
    var selectedResolution by remember { mutableStateOf(DisplayResolutionMode.NATIVE_PHONE) }
    var dpiScaling by remember { mutableFloatStateOf(120f) }
    var ramAllocationMb by remember { mutableFloatStateOf(2048f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Create New Instance",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Configure your isolated rootless Linux environment",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                // Scrollable Form
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Instance Name
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Instance Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    // Desktop Environment Presets
                    Text(
                        text = "Desktop Environment",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    DesktopEnvironment.values().forEach { desktop ->
                        val isSelected = desktop == selectedDesktop
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                .border(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedDesktop = desktop }
                                .padding(12.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = desktop.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (desktop.isTouchOptimized) {
                                        Text(
                                            text = "TOUCH-FIRST",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = desktop.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }

                    // Resolution Profile
                    Text(
                        text = "Display Resolution Mode",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    DisplayResolutionMode.values().forEach { mode ->
                        val isSelected = mode == selectedResolution
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedResolution = mode }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedResolution = mode }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = mode.displayName, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    // DPI Scaling Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Interface Scaling (DPI)", style = MaterialTheme.typography.bodyMedium)
                            Text("${dpiScaling.toInt()} DPI", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = dpiScaling,
                            onValueChange = { dpiScaling = it },
                            valueRange = 96f..240f,
                            steps = 5
                        )
                    }

                    // RAM Allocation Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("RAM Memory Limit", style = MaterialTheme.typography.bodyMedium)
                            Text("${(ramAllocationMb / 1024).toInt()} GB", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = ramAllocationMb,
                            onValueChange = { ramAllocationMb = it },
                            valueRange = 1024f..6144f,
                            steps = 4
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(14.dp))

                // Footer Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = {
                            val newInstance = LinuxInstance(
                                id = UUID.randomUUID().toString(),
                                name = name.ifBlank { "Linux Workstation" },
                                distro = selectedDistro,
                                desktop = selectedDesktop,
                                resolutionMode = selectedResolution,
                                dpiScaling = dpiScaling.toInt(),
                                ramAllocatedMb = ramAllocationMb.toInt()
                            )
                            onCreate(newInstance)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Create Instance", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
