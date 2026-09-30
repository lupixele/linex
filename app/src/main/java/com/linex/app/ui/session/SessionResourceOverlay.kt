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
import com.linex.app.core.SessionResourceMonitor
import com.linex.app.core.SessionResources
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
    modifier: Modifier = Modifier
) {
    var fps by remember(view) { mutableStateOf<Double?>(null) }
    var resources by remember(processGroup) { mutableStateOf<SessionResources?>(null) }
    LaunchedEffect(view, processGroup, enabled, sessionVisible) {
        fps = null
        resources = null
        if (!enabled || !sessionVisible || view == null) return@LaunchedEffect
        val rate = PresentedFrameRate()
        val monitor = withContext(Dispatchers.IO) { runCatching { SessionResourceMonitor() }.getOrNull() }
        rate.sample(view.presentedFrameCount, System.nanoTime())
        while (isActive) {
            resources = withContext(Dispatchers.IO) {
                processGroup?.let { group -> runCatching { monitor?.sample(group) }.getOrNull() }
            }
            delay(1000)
            fps = rate.sample(view.presentedFrameCount, System.nanoTime())
        }
    }
    if (enabled && sessionVisible && view != null) {
        val text = buildList {
            add("FPS ${fps?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "…"}")
            resources?.let { sample ->
                add("RAM* ${sample.visibleRssBytes / 1048576} MiB")
                sample.cpuPercent?.let { add("CPU* ${String.format(Locale.ROOT, "%.0f", it)}%") }
                add("* visible Linux processes")
            }
        }.joinToString("\n")
        Text(text, modifier.background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(4.dp)).padding(8.dp),
            color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
    }
}
