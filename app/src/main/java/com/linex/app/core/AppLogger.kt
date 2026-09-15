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

object AppLogger {
    private const val TAG = "LinexLogger"
    private const val MAX_LOG_LINES = 2000

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val logBuffer = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val line = "[$timestamp] [$tag] $message"
        Log.i(tag, message)
        logBuffer.add(line)
        if (logBuffer.size > MAX_LOG_LINES) {
            logBuffer.removeAt(0)
        }
        _logs.value = logBuffer.toList()
    }

    fun getLogsAsText(): String {
        return synchronized(this) {
            logBuffer.joinToString("\n")
        }
    }

    fun clear() {
        synchronized(this) {
            logBuffer.clear()
            _logs.value = emptyList()
        }
    }

    /**
     * Writes all captured logs to a public cache file and triggers Android's system share sheet
     * so you can copy, save, or send the exact logfile anywhere.
     */
    fun shareLogs(context: Context) {
        try {
            val logFile = File(context.cacheDir, "linex_diagnostic_logs.txt")
            logFile.writeText(getLogsAsText())

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                logFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Linex Diagnostic Logs")
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
