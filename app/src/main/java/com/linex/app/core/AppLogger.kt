package com.linex.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: String,
    val instanceId: String?, // null = global
    val tag: String,
    val message: String
) {
    override fun toString(): String {
        val instPrefix = if (instanceId != null) "[Inst:$instanceId] " else ""
        return "[$timestamp] $instPrefix[$tag] $message"
    }
}

object AppLogger {
    private const val TAG = "LinexLogger"
    private const val MAX_LOG_LINES = 3000

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    private val logBuffer = mutableListOf<LogEntry>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(tag: String, message: String, instanceId: String? = null) {
        val timestamp = dateFormat.format(Date())
        val entry = LogEntry(timestamp, instanceId, tag, message)
        Log.i(tag, entry.toString())
        logBuffer.add(entry)
        if (logBuffer.size > MAX_LOG_LINES) {
            logBuffer.removeAt(0)
        }
        _logs.value = logBuffer.toList()
    }

    fun getLogsForInstance(instanceId: String?): List<LogEntry> {
        return synchronized(this) {
            if (instanceId == null) {
                logBuffer.toList()
            } else {
                logBuffer.filter { it.instanceId == null || it.instanceId == instanceId }
            }
        }
    }

    fun getLogsAsText(instanceId: String? = null): String {
        return getLogsForInstance(instanceId).joinToString("\n") { it.toString() }
    }

    fun clear(instanceId: String? = null) {
        synchronized(this) {
            if (instanceId == null) {
                logBuffer.clear()
            } else {
                logBuffer.removeAll { it.instanceId == instanceId }
            }
            _logs.value = logBuffer.toList()
        }
    }

    /**
     * Exports logs via Android share sheet.
     */
    fun shareLogs(context: Context, instanceId: String? = null, instanceName: String? = null) {
        try {
            val fileName = if (instanceName != null) {
                "linex_${instanceName.replace(" ", "_").lowercase()}_logs.txt"
            } else {
                "linex_all_logs.txt"
            }
            val logFile = File(context.cacheDir, fileName)
            logFile.writeText(getLogsAsText(instanceId))

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                logFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Linex Logs ${instanceName ?: "All"}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(Intent.createChooser(shareIntent, "Export Linex Diagnostic Logs").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share logs", e)
        }
    }
}
