package com.linex.app.ui.hub

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.linex.app.core.AppLogger
import com.linex.app.data.LinuxInstance

@Composable
fun LogViewerDialog(
    selectedInstance: LinuxInstance? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val allLogs by AppLogger.logs.collectAsState()
    val loading by AppLogger.loading.collectAsState()
    val storageError by AppLogger.storageError.collectAsState()
    var query by remember { mutableStateOf("") }
    var followOutput by remember { mutableStateOf(true) }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val instanceLogs = remember(allLogs, selectedInstance?.id) {
        allLogs.filter { selectedInstance == null || it.instanceId == selectedInstance.id }
    }
    val filteredLogs = remember(instanceLogs, query) {
        instanceLogs.filter { query.isBlank() || it.toString().contains(query, ignoreCase = true) }
    }

    LaunchedEffect(filteredLogs.lastOrNull(), followOutput) {
        if (followOutput && filteredLogs.isNotEmpty()) {
            listState.scrollToItem(filteredLogs.size - 1)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear saved diagnostics?") },
            text = { Text("This removes the saved output for ${selectedInstance?.name ?: "all instances"}. New output will still be recorded.") },
            confirmButton = { TextButton(onClick = { AppLogger.clear(selectedInstance?.id); confirmClear = false }) { Text("Clear logs") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }

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
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (selectedInstance != null) "${selectedInstance.name} Logs" else "All Diagnostic Logs",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (loading) "Loading saved diagnostics…" else "${instanceLogs.size} entries · saved on this device",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search diagnostics") },
                    singleLine = true
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Follow live output", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = followOutput, onCheckedChange = { followOutput = it })
                }
                storageError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(8.dp))

                // Log Box
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0C0C0E))
                        .padding(8.dp)
                ) {
                    if (filteredLogs.isEmpty()) {
                        Text(
                            text = when {
                                loading -> "Loading saved diagnostics…"
                                query.isNotBlank() -> "No entries match your search."
                                else -> "No logs recorded for this instance yet.\nStart it to capture download, setup, and session output."
                            },
                            color = Color.Gray,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(filteredLogs) { entry ->
                                val text = entry.toString()
                                val color = when {
                                    text.contains("ERROR", ignoreCase = true) || text.contains("failed", ignoreCase = true) || text.contains("EXCEPTION", ignoreCase = true) -> Color(0xFFFF453A)
                                    text.contains("Warning", ignoreCase = true) || text.contains("WARN", ignoreCase = true) -> Color(0xFFFF9F0A)
                                    text.contains("SUCCESS", ignoreCase = true) || text.contains("complete", ignoreCase = true) -> Color(0xFF30D158)
                                    else -> Color(0xFFD1D1D6)
                                }
                                Text(
                                    text = text,
                                    color = color,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action Bar: Clear, Copy, Export
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        onClick = { confirmClear = true },
                        enabled = instanceLogs.isNotEmpty() && !loading,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear")
                    }

                    Row(modifier = Modifier.weight(2f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            enabled = instanceLogs.isNotEmpty() && !loading,
                            onClick = {
                                val fullText = AppLogger.getLogsAsText(selectedInstance?.id)
                                clipboardManager.setText(AnnotatedString(fullText))
                                Toast.makeText(context, "Copied ${instanceLogs.size} entries", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy", color = MaterialTheme.colorScheme.onSurface)
                        }

                        Button(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            enabled = instanceLogs.isNotEmpty() && !loading,
                            onClick = { AppLogger.shareLogs(context, selectedInstance?.id, selectedInstance?.name) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Export", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
