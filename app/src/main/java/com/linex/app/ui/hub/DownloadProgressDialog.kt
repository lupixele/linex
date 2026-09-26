package com.linex.app.ui.hub

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.linex.app.data.LinuxInstance
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadProgressDialog(
    instance: LinuxInstance,
    progress: Float,
    stage: String = "Preparing setup",
    startedAtMillis: Long = SystemClock.elapsedRealtime(),
    lastProgressAtMillis: Long = startedAtMillis,
    statusText: String = "Downloading rootfs archive…",
    error: String? = null,
    onViewLogs: () -> Unit = {},
    onRetry: () -> Unit = {},
    onCancel: () -> Unit
) {
    var now by remember(startedAtMillis) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAtMillis, error) {
        while (error == null) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    fun duration(milliseconds: Long): String {
        val seconds = (milliseconds.coerceAtLeast(0) / 1_000)
        return "${seconds / 60}m ${seconds % 60}s"
    }
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(if (error != null) "Setup paused" else "Setting up ${instance.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(instance.distro.displayName)
                if (error == null) {
                    Text(stage, style = MaterialTheme.typography.titleMedium)
                    if (progress >= 0f) {
                        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "${(progress.coerceIn(0f, 1f) * 100).toInt()}% " +
                                if (stage == "Extracting") "of archive read" else "of this stage",
                            style = MaterialTheme.typography.labelMedium
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                Text(error ?: statusText,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (error == null) {
                    Text("Elapsed ${duration(now - startedAtMillis)}", style = MaterialTheme.typography.labelMedium)
                    if (now - lastProgressAtMillis >= 30_000) {
                        Text("No new progress for ${duration(now - lastProgressAtMillis)}. Open the logs for details; this does not necessarily mean setup has stopped.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (stage == "Extracting")
                        "Large Linux images contain hundreds of thousands of files. Archive progress does not predict time remaining; finishing links and configuration follows. Keep Linex open."
                        else "Keep Linex open during setup. The screen stays awake while setup is running.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Your instance is still available. Check its logs or retry when ready.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onViewLogs) { Text("View logs") }
                if (error != null) Button(onClick = onRetry) { Text("Retry setup") }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(if (error == null) "Cancel setup" else "Close") } }
    )
}
