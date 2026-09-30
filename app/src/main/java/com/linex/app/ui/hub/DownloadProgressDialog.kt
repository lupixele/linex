package com.linex.app.ui.hub

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
import com.linex.app.core.SetupStatus
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadProgressDialog(
    instanceName: String,
    operationKind: String = "setup",
    progress: Float,
    stage: String = "Preparing setup",
    startedAtMillis: Long = System.currentTimeMillis(),
    lastProgressAtMillis: Long = startedAtMillis,
    statusText: String = "Downloading rootfs archive…",
    error: String? = null,
    taskStatus: SetupStatus = SetupStatus.RUNNING,
    onBackground: () -> Unit,
    onClose: () -> Unit,
    onViewLogs: () -> Unit = {},
    logsAvailable: Boolean = true,
    onRetry: () -> Unit = {},
    onCancel: () -> Unit
) {
    val running = taskStatus == SetupStatus.RUNNING
    var now by remember(startedAtMillis) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMillis, taskStatus) {
        while (running) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    fun duration(milliseconds: Long): String {
        val seconds = (milliseconds.coerceAtLeast(0) / 1_000)
        return "${seconds / 60}m ${seconds % 60}s"
    }
    AlertDialog(
        onDismissRequest = { if (running) onBackground() else onClose() },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(when (taskStatus) {
            SetupStatus.COMPLETE -> "Task completed"
            SetupStatus.FAILED -> "Task failed"
            SetupStatus.CANCELLED -> "Task cancelled"
            SetupStatus.RUNNING -> stage
        }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(instanceName)
                if (running) {
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
                if (running) {
                    Text("Elapsed ${duration(now - startedAtMillis)}", style = MaterialTheme.typography.labelMedium)
                    if (now - lastProgressAtMillis >= 30_000) {
                        Text("No new progress for ${duration(now - lastProgressAtMillis)}. Open the logs for details; this does not necessarily mean setup has stopped.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (stage == "Extracting")
                        "Large Linux images contain hundreds of thousands of files. Archive progress does not predict time remaining; finishing links and configuration follows. You can leave Linex or turn the screen off."
                        else "This task continues in the background, even with the screen off. Follow progress in notifications.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(if (taskStatus == SetupStatus.COMPLETE && operationKind == "setup") "Return to the instance card and tap Launch to open your desktop." else "Check the instance list or logs for details.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onViewLogs, enabled = logsAvailable) { Text("View logs") }
                if (error != null && operationKind == "setup") Button(onClick = onRetry) { Text("Retry setup") }
                if (running) Button(onClick = onBackground) { Text("Run in background") }
            }
        },
        dismissButton = { TextButton(onClick = { if (running) onCancel() else onClose() }, enabled = !running || operationKind != "delete") { Text(if (running) "Cancel task" else "Close") } }
    )
}
