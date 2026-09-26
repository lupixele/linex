package com.linex.app.ui.hub

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.linex.app.data.LinuxInstance

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadProgressDialog(
    instance: LinuxInstance,
    progress: Float,
    statusText: String = "Downloading rootfs archive…",
    error: String? = null,
    onViewLogs: () -> Unit = {},
    onRetry: () -> Unit = {},
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(if (error != null) "Setup paused" else "Setting up ${instance.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(instance.distro.displayName)
                if (error == null) {
                    if (progress >= 0f && progress < 0.99f) {
                        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                Text(error ?: statusText,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (error == null) {
                    Text("First setup downloads about ${instance.distro.estimatedSizeMb} MB. Unpacking may take several minutes.",
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
