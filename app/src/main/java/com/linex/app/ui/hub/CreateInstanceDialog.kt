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
    var name by remember { mutableStateOf(existingInstance?.name ?: "Ubuntu Workstation") }
    var selectedDistro by remember {
        mutableStateOf(
            existingInstance?.distro?.takeIf { it != DistroType.DEBIAN_TRIXIE_VM } ?: DistroType.UBUNTU_JAMMY
        )
    }
    val selectedDesktop = existingInstance?.desktop ?: DesktopEnvironment.XFCE4
    var selectedResolution by remember { mutableStateOf(existingInstance?.resolutionMode ?: DisplayResolutionMode.NATIVE_PHONE) }
    var dpiScaling by remember { mutableFloatStateOf(existingInstance?.dpiScaling?.toFloat() ?: 120f) }
    var customWidth by remember { mutableStateOf((existingInstance?.customWidth ?: 1920).toString()) }
    var customHeight by remember { mutableStateOf((existingInstance?.customHeight ?: 1080).toString()) }
    var desktopFps by remember { mutableIntStateOf(DesktopFrameRate.normalized(existingInstance?.desktopFps ?: 60)) }
    var fpsMenuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val totalRamMb = remember(context) {
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memory)
        memory.totalMem / (1024L * 1024L)
    }
    var memoryMode by remember { mutableStateOf(existingInstance?.let(MemoryBudget::mode) ?: MemoryBudgetMode.DEFAULT) }
    var customRam by remember { mutableStateOf((existingInstance?.ramAllocatedMb ?: MemoryBudget.DEFAULT_MB).toString()) }
    var memoryMenuExpanded by remember { mutableStateOf(false) }
    val memoryError = if (memoryMode == MemoryBudgetMode.CUSTOM) MemoryBudget.error(customRam, totalRamMb, InstanceRuntime.PROOT) else null
    val memoryInstance = LinuxInstance("memory-preview", "", selectedDistro, selectedDesktop, selectedResolution, runtime = InstanceRuntime.PROOT)
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
                            text = if (existingInstance == null) "New Linux Desktop" else "Desktop Settings",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Hardware-accelerated Linux desktop powered by Wayland",
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
                    // GPU Pipeline Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("⚡ Hardware GPU Accelerated (Wayland)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.height(4.dp))
                            Text("Direct Qualcomm Adreno GPU rendering via Mesa Turnip (Vulkan) and Zink (OpenGL) drivers. Zero-copy presentation on Android.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }

                    // Instance Name
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(80) },
                        label = { Text("Instance Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    Text("System image", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    DistroType.values().filter { it != DistroType.DEBIAN_TRIXIE_VM }.forEach { distro ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            RadioButton(
                                selected = selectedDistro == distro,
                                enabled = existingInstance == null,
                                onClick = { selectedDistro = distro }
                            )
                            Column(Modifier.weight(1f).padding(top = 12.dp)) {
                                Text(distro.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(distro.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    Text("Desktop Environment: XFCE4 (Pre-installed & Optimized)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium)

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
                                        text = { Text(if (fps == 60) "$fps FPS · Smooth (Recommended)" else "$fps FPS") },
                                        leadingIcon = { RadioButton(selected = desktopFps == fps, onClick = null) },
                                        onClick = { desktopFps = fps; fpsMenuExpanded = false }
                                    )
                                }
                            }
                        }
                        Text(
                            "Framerate target for Wayland compositor VSync. Higher rates take full advantage of 90Hz/120Hz displays.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("RAM Budget", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                        Box {
                            OutlinedButton(onClick = { memoryMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(when (memoryMode) {
                                    MemoryBudgetMode.DEFAULT -> "Default · $defaultRam MiB"
                                    MemoryBudgetMode.RECOMMENDED -> "Recommended · $recommendedRam MiB"
                                    MemoryBudgetMode.CUSTOM -> "Custom budget"
                                })
                            }
                            DropdownMenu(expanded = memoryMenuExpanded, onDismissRequest = { memoryMenuExpanded = false }) {
                                MemoryBudgetMode.values().forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(when (mode) {
                                            MemoryBudgetMode.DEFAULT -> "Default · $defaultRam MiB"
                                            MemoryBudgetMode.RECOMMENDED -> "Recommended · $recommendedRam MiB"
                                            MemoryBudgetMode.CUSTOM -> "Custom budget"
                                        }) },
                                        leadingIcon = { RadioButton(selected = memoryMode == mode, onClick = null) },
                                        onClick = { memoryMode = mode; memoryMenuExpanded = false }
                                    )
                                }
                            }
                        }
                        if (memoryMode == MemoryBudgetMode.CUSTOM) {
                            OutlinedTextField(
                                value = customRam,
                                onValueChange = { customRam = it.take(8) },
                                label = { Text("RAM budget (MiB)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true, isError = memoryError != null,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Text(memoryError ?: "Guides memory usage and system cache limits.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (memoryError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
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
                        enabled = name.isNotBlank() && resolutionError == null && memoryError == null,
                        onClick = {
                            val base = existingInstance ?: LinuxInstance(
                                id = UUID.randomUUID().toString(),
                                name = name.trim(),
                                distro = selectedDistro,
                                desktop = selectedDesktop,
                                resolutionMode = selectedResolution,
                                runtime = InstanceRuntime.PROOT,
                                displayBackend = DisplayBackendPreference.WAYLAND,
                                vmImageId = null
                            )
                            val newInstance = base.copy(
                                name = name.trim(),
                                distro = selectedDistro,
                                resolutionMode = selectedResolution,
                                customWidth = if (selectedResolution == DisplayResolutionMode.CUSTOM) customWidth.toInt() else base.customWidth,
                                customHeight = if (selectedResolution == DisplayResolutionMode.CUSTOM) customHeight.toInt() else base.customHeight,
                                dpiScaling = dpiScaling.toInt(),
                                desktopFps = desktopFps,
                                displayBackend = DisplayBackendPreference.WAYLAND,
                                runtime = InstanceRuntime.PROOT,
                                vmImageId = null,
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
                        Text(if (existingInstance == null) "Create Desktop" else "Save changes", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
