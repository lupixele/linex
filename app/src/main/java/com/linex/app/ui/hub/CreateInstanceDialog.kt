package com.linex.app.ui.hub

import android.app.ActivityManager
import android.content.Context

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.linex.app.data.DesktopEnvironment
import com.linex.app.core.DesktopFrameRate
import com.linex.app.core.MemoryBudget
import com.linex.app.data.DisplayBackendPreference
import com.linex.app.data.MemoryBudgetMode
import com.linex.app.data.DisplayResolutionMode
import com.linex.app.data.DistroType
import com.linex.app.data.LinuxInstance
import com.linex.app.data.InstanceRuntime
import com.linex.app.data.CustomResolution
import java.util.UUID

@Composable
fun CreateInstanceDialog(
    onDismiss: () -> Unit,
    onCreate: (LinuxInstance) -> Unit,
    existingInstance: LinuxInstance? = null,
    availableVmImageId: String? = null
) {
    val vmAvailable = !availableVmImageId.isNullOrBlank()
    var runtime by remember { mutableStateOf(existingInstance?.runtime ?: if (vmAvailable) InstanceRuntime.FULL_VM else InstanceRuntime.PROOT) }
    val isVm = runtime == InstanceRuntime.FULL_VM
    var name by remember { mutableStateOf(existingInstance?.name ?: if (vmAvailable) "My Debian Workstation" else "My Ubuntu Workstation") }
    var selectedDistro by remember { mutableStateOf(existingInstance?.distro ?: if (vmAvailable) DistroType.DEBIAN_TRIXIE_VM else DistroType.UBUNTU_JAMMY) }
    val selectedDesktop = existingInstance?.desktop ?: DesktopEnvironment.XFCE4
    var selectedResolution by remember { mutableStateOf(existingInstance?.resolutionMode ?: if (vmAvailable) DisplayResolutionMode.HD_720P else DisplayResolutionMode.NATIVE_PHONE) }
    var dpiScaling by remember { mutableFloatStateOf(existingInstance?.dpiScaling?.toFloat() ?: 120f) }
    var customWidth by remember { mutableStateOf((existingInstance?.customWidth ?: 1920).toString()) }
    var customHeight by remember { mutableStateOf((existingInstance?.customHeight ?: 1080).toString()) }
    var desktopFps by remember { mutableIntStateOf(DesktopFrameRate.normalized(existingInstance?.desktopFps ?: if (vmAvailable) 30 else 60)) }
    var fpsMenuExpanded by remember { mutableStateOf(false) }
    var displayBackend by remember { mutableStateOf(existingInstance?.displayBackend ?: DisplayBackendPreference.AUTO) }
    var displayMenuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val totalRamMb = remember(context) {
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memory)
        memory.totalMem / (1024L * 1024L)
    }
    var memoryMode by remember { mutableStateOf(existingInstance?.let(MemoryBudget::mode) ?: MemoryBudgetMode.DEFAULT) }
    var customRam by remember { mutableStateOf((existingInstance?.ramAllocatedMb ?: MemoryBudget.DEFAULT_MB).toString()) }
    var memoryMenuExpanded by remember { mutableStateOf(false) }
    val memoryError = if (memoryMode == MemoryBudgetMode.CUSTOM) MemoryBudget.error(customRam, totalRamMb, runtime) else null
    val memoryInstance = LinuxInstance("memory-preview", "", selectedDistro, selectedDesktop, selectedResolution, runtime = runtime)
    val defaultRam = MemoryBudget.resolveMb(memoryInstance.copy(memoryBudgetMode = MemoryBudgetMode.DEFAULT), totalRamMb)
    val recommendedRam = MemoryBudget.resolveMb(memoryInstance.copy(memoryBudgetMode = MemoryBudgetMode.RECOMMENDED), totalRamMb)
    val resolutionError = if (selectedResolution == DisplayResolutionMode.CUSTOM)
        CustomResolution.error(customWidth, customHeight) else null

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
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (existingInstance == null) "New Linux instance" else "Instance settings",
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
                        onValueChange = { name = it.take(80) },
                        label = { Text("Instance Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    Text("Linux runtime", style = MaterialTheme.typography.titleSmall)
                    InstanceRuntime.values().forEach { choice ->
                        val choiceEnabled = existingInstance == null && (choice != InstanceRuntime.FULL_VM || vmAvailable)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = runtime == choice, enabled = choiceEnabled, onClick = {
                                if (choiceEnabled) {
                                    runtime = choice
                                    selectedDistro = if (choice == InstanceRuntime.FULL_VM) DistroType.DEBIAN_TRIXIE_VM else DistroType.UBUNTU_JAMMY
                                }
                            })
                            Column(Modifier.weight(1f)) {
                                Text(if (choice == InstanceRuntime.FULL_VM) "Full virtual machine · recommended" else "PRoot · legacy", style = MaterialTheme.typography.bodyMedium)
                                Text(if (choice == InstanceRuntime.FULL_VM) {
                                    if (vmAvailable || isVm) "Complete Linux kernel and allocated guest RAM. May run slower on older phones."
                                    else "Available when a verified VM image is included in this release."
                                } else "Native CPU execution; Android may limit Linux background processes.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (existingInstance != null) Text("The runtime and system image stay fixed for this instance.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("System image", style = MaterialTheme.typography.titleSmall)
                    DistroType.values().filter { (it == DistroType.DEBIAN_TRIXIE_VM) == isVm }.forEach { distro ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            RadioButton(
                                selected = selectedDistro == distro,
                                enabled = existingInstance == null,
                                onClick = { selectedDistro = distro }
                            )
                            Column(Modifier.weight(1f).padding(top = 12.dp)) {
                                Text(distro.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(if (isVm) "XFCE, Firefox ESR and a 4 GiB Linux disk" else "Download: about ${distro.estimatedSizeMb} MB", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text(
                        if (isVm) "An embedded desktop with an isolated Linux kernel. Setup progress continues in the background."
                        else if (selectedDistro == DistroType.UBUNTU_JAMMY)
                            "Includes XFCE and an embedded desktop display."
                        else "Minimal base image. A desktop is not included; install one before using a desktop session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Desktop preset: ${if (isVm) "XFCE" else selectedDesktop.displayName}", style = MaterialTheme.typography.bodyMedium)
                    Text("New instances use XFCE. Additional desktop presets are not yet supported.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

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

                    if (selectedResolution == DisplayResolutionMode.CUSTOM) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = customWidth,
                                onValueChange = { customWidth = it.take(10) },
                                label = { Text("Width (px)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true, isError = resolutionError != null,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = customHeight,
                                onValueChange = { customHeight = it.take(10) },
                                label = { Text("Height (px)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true, isError = resolutionError != null,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(resolutionError ?: "Up to 4096 pixels per side and 8 million pixels total.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (resolutionError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Resolution changes apply on the next session start. Match your screen's aspect ratio to avoid black borders.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Desktop frame rate", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                        Box {
                            OutlinedButton(
                                onClick = { fpsMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Up to $desktopFps FPS")
                            }
                            DropdownMenu(
                                expanded = fpsMenuExpanded,
                                onDismissRequest = { fpsMenuExpanded = false }
                            ) {
                                DesktopFrameRate.options.forEach { fps ->
                                    DropdownMenuItem(
                                        text = { Text(if (fps == 15) "$fps FPS · Battery saver" else "$fps FPS") },
                                        leadingIcon = { RadioButton(selected = desktopFps == fps, onClick = null) },
                                        onClick = { desktopFps = fps; fpsMenuExpanded = false }
                                    )
                                }
                            }
                        }
                        Text(
                            "Sets a frame-rate cap, not guaranteed FPS. Actual smoothness depends on your display, resolution and Linux workload. Higher rates use more CPU and battery. Applies on the next session start.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (!isVm) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Desktop display", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                        fun label(mode: DisplayBackendPreference) = when (mode) {
                            DisplayBackendPreference.AUTO -> "Automatic · native with compatibility fallback"
                            DisplayBackendPreference.NATIVE_X11 -> "Native X11 · experimental GPU presentation"
                            DisplayBackendPreference.RFB -> "Compatibility · VNC display"
                        }
                        Box {
                            OutlinedButton(onClick = { displayMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(label(displayBackend))
                            }
                            DropdownMenu(expanded = displayMenuExpanded, onDismissRequest = { displayMenuExpanded = false }) {
                                DisplayBackendPreference.values().forEach { mode ->
                                    DropdownMenuItem(text = { Text(label(mode)) },
                                        leadingIcon = { RadioButton(selected = displayBackend == mode, onClick = null) },
                                        onClick = { displayBackend = mode; displayMenuExpanded = false })
                                }
                            }
                        }
                        Text("Native display uses the phone GPU to present the desktop. Linux application graphics and video decoding are separate. Stop and start the instance to apply changes.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isVm) Text("Embedded desktop · GPU presentation. Linux applications use software graphics.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (isVm) "Guest RAM allocation" else "RAM budget", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                        Box {
                            OutlinedButton(onClick = { memoryMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(when (memoryMode) {
                                    MemoryBudgetMode.DEFAULT -> "Default · $defaultRam MiB"
                                    MemoryBudgetMode.RECOMMENDED -> "Recommended · $recommendedRam MiB"
                                    MemoryBudgetMode.CUSTOM -> if (isVm) "Custom allocation" else "Custom budget"
                                })
                            }
                            DropdownMenu(expanded = memoryMenuExpanded, onDismissRequest = { memoryMenuExpanded = false }) {
                                MemoryBudgetMode.values().forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(when (mode) {
                                            MemoryBudgetMode.DEFAULT -> "Default · $defaultRam MiB"
                                            MemoryBudgetMode.RECOMMENDED -> "Recommended · $recommendedRam MiB"
                                            MemoryBudgetMode.CUSTOM -> "Custom…"
                                        }) },
                                        leadingIcon = { RadioButton(selected = memoryMode == mode, onClick = null) },
                                        onClick = { memoryMode = mode; memoryMenuExpanded = false }
                                    )
                                }
                            }
                        }
                        if (memoryMode == MemoryBudgetMode.CUSTOM) {
                            OutlinedTextField(value = customRam, onValueChange = { customRam = it.take(10) },
                                label = { Text(if (isVm) "Custom guest RAM (MiB)" else "Custom RAM budget (MiB)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true, isError = memoryError != null,
                                modifier = Modifier.fillMaxWidth())
                        }
                        if (memoryError != null) Text(memoryError, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                        Text(if (isVm) "Sets the VM's real guest RAM limit, up to 4096 MiB. Linex checks available memory again before starting to leave room for Android. Changes apply after restarting."
                            else "Planning target only. Android shares RAM with Linux; Linex cannot reserve memory or enforce a guest RAM limit. Recommended considers your desktop and device RAM, up to 4096 MiB, leaving room for Android.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (totalRamMb > 0) Text("Device RAM: $totalRamMb MiB", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // DPI Scaling Slider
                    if (!isVm) Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Interface scaling", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("${dpiScaling.toInt()} DPI", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = dpiScaling,
                            onValueChange = { dpiScaling = it },
                            valueRange = 96f..240f,
                            steps = 5
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
                        enabled = name.isNotBlank() && resolutionError == null && memoryError == null && (!isVm || existingInstance != null || vmAvailable),
                        onClick = {
                            val base = existingInstance ?: LinuxInstance(
                                id = UUID.randomUUID().toString(),
                                name = name.trim(),
                                distro = selectedDistro,
                                desktop = selectedDesktop,
                                resolutionMode = selectedResolution,
                                runtime = runtime,
                                vmImageId = if (isVm) availableVmImageId else null
                            )
                            val newInstance = base.copy(
                                name = name.trim(),
                                resolutionMode = selectedResolution,
                                customWidth = if (selectedResolution == DisplayResolutionMode.CUSTOM) customWidth.toInt() else base.customWidth,
                                customHeight = if (selectedResolution == DisplayResolutionMode.CUSTOM) customHeight.toInt() else base.customHeight,
                                dpiScaling = dpiScaling.toInt(),
                                desktopFps = desktopFps,
                                displayBackend = displayBackend,
                                memoryBudgetMode = memoryMode,
                                ramAllocatedMb = when (memoryMode) {
                                    MemoryBudgetMode.DEFAULT -> defaultRam
                                    MemoryBudgetMode.RECOMMENDED -> recommendedRam
                                    MemoryBudgetMode.CUSTOM -> customRam.toInt()
                                }
                            )
                            onCreate(newInstance)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(if (existingInstance == null) "Create" else "Save changes", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
