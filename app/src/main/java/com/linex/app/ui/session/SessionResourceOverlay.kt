package com.linex.app.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linex.app.core.PresentedFrameRate
import com.linex.app.core.DisplayBackend
import com.linex.app.core.SessionResourceMonitor
import com.linex.app.core.SessionResources
import com.linex.app.core.VmResourceMonitor
import com.linex.app.core.VmResources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

/** On-demand, read-only diagnostics. Updating text never reconnects or redraws the desktop. */
@Composable
internal fun SessionResourceOverlay(
    view: EmbeddedDesktopView?,
    processGroup: Int?,
    enabled: Boolean,
    sessionVisible: Boolean,
    modifier: Modifier = Modifier,
    displayBackend: DisplayBackend? = null,
    vmProcessId: Int? = null,
    guestAllocatedMb: Int? = null
) {
    val isVm = displayBackend == DisplayBackend.VM_RFB || guestAllocatedMb != null
    var fps by remember(view) { mutableStateOf<Double?>(null) }
    var resources by remember(processGroup) { mutableStateOf<SessionResources?>(null) }
    var vmResources by remember(vmProcessId) { mutableStateOf<VmResources?>(null) }
    LaunchedEffect(view, processGroup, vmProcessId, isVm, enabled, sessionVisible) {
        fps = null
        resources = null
        vmResources = null
        if (!enabled || !sessionVisible || view == null) return@LaunchedEffect
        val rate = PresentedFrameRate()
        val monitor = withContext(Dispatchers.IO) { if (!isVm) runCatching { SessionResourceMonitor() }.getOrNull() else null }
        val vmMonitor = withContext(Dispatchers.IO) { if (isVm) vmProcessId?.let { runCatching { VmResourceMonitor(it) }.getOrNull() } else null }
        if (view.frameMetricsAvailable) rate.sample(view.presentedFrameCount, System.nanoTime())
        while (isActive) {
            resources = withContext(Dispatchers.IO) {
                if (!isVm) processGroup?.let { group -> runCatching { monitor?.sample(group) }.getOrNull() } else null
            }
            vmResources = withContext(Dispatchers.IO) { runCatching { vmMonitor?.sample() }.getOrNull() }
            delay(1000)
            fps = if (view.frameMetricsAvailable) rate.sample(view.presentedFrameCount, System.nanoTime()) else null
        }
    }
    if (enabled && sessionVisible && view != null) {
        val text = buildList {
            displayBackend?.let { add(when (it) {
                DisplayBackend.NATIVE_X11 -> "Native X11"
                DisplayBackend.RFB -> "RFB compatibility"
                DisplayBackend.VM_RFB -> "VM desktop"
            }) }
            if (view.frameMetricsAvailable) add("FPS ${fps?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "…"}")
            resources?.let { sample ->
                add("RAM* ${sample.visibleRssBytes / 1048576} MiB")
                sample.cpuPercent?.let { add("CPU* ${String.format(Locale.ROOT, "%.0f", it)}%") }
                add("* visible Linux processes")
            }
            if (isVm) {
                vmResources?.let { sample ->
                    add("VM RAM ${sample.residentBytes / 1048576} MiB")
                    sample.cpuPercent?.let { add("VM CPU ${String.format(Locale.ROOT, "%.0f", it)}%") }
                }
                guestAllocatedMb?.let { add("Guest limit $it MiB") }
            }
        }.joinToString("\n")
        Text(text, modifier.background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(4.dp)).padding(8.dp),
            color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
    }
}
