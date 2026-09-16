package com.linex.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
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
    private const val MAX_LOG_LINES = 5000

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    private val logBuffer = mutableListOf<LogEntry>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var logsBaseDir: File? = null
    private val diskScope = CoroutineScope(Dispatchers.IO)

    fun init(context: Context) {
        synchronized(this) {
            if (logsBaseDir == null) {
                logsBaseDir = File(context.filesDir, "logs").apply { if (!exists()) mkdirs() }
                loadPersistedLogs()
            }
        }
    }

    private fun loadPersistedLogs() {
        val base = logsBaseDir ?: return
        try {
            val globalLogFile = File(base, "linex_global.log")
            if (globalLogFile.exists()) {
                globalLogFile.readLines().takeLast(500).forEach { line ->
                    // Basic parse back into memory buffer
                    logBuffer.add(LogEntry("", null, "PERSISTED", line))
                }
                _logs.value = logBuffer.toList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read persisted logs: ${e.message}")
        }
    }

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

        // Append asynchronously to disk
        val lineStr = entry.toString()
        val base = logsBaseDir
        if (base != null) {
            diskScope.launch {
                try {
                    // 1. Global log file
                    val globalFile = File(base, "linex_global.log")
                    FileWriter(globalFile, true).use { fw ->
                        fw.write(lineStr + "\n")
                    }

                    // 2. Per-instance isolated log file
                    if (instanceId != null) {
                        val instFile = File(base, "instance_${instanceId}.log")
                        FileWriter(instFile, true).use { fw ->
                            fw.write(lineStr + "\n")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Disk log write error: ${e.message}")
                }
            }
        }
    }

    fun getLogsForInstance(instanceId: String?): List<LogEntry> {
        return synchronized(this) {
            if (instanceId == null) {
                logBuffer.toList()
            } else {
                val inMemory = logBuffer.filter { it.instanceId == null || it.instanceId == instanceId }
                if (inMemory.isNotEmpty()) {
                    inMemory
                } else {
                    // Fallback to reading disk file if memory was cleared/restarted
                    val base = logsBaseDir
                    if (base != null) {
                        val instFile = File(base, "instance_${instanceId}.log")
                        if (instFile.exists()) {
                            try {
                                instFile.readLines().takeLast(1000).map { line ->
                                    LogEntry("", instanceId, "LOG", line)
                                }
                            } catch (e: Exception) {
                                emptyList()
                            }
                        } else {
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                }
            }
        }
    }

    fun getLogsAsText(instanceId: String? = null): String {
        val list = getLogsForInstance(instanceId)
        if (list.isNotEmpty()) {
            return list.joinToString("\n") { it.toString() }
        }
        // Check disk file directly
        val base = logsBaseDir
        if (base != null) {
            val file = if (instanceId != null) File(base, "instance_${instanceId}.log") else File(base, "linex_global.log")
            if (file.exists()) {
                return try {
                    file.readText()
                } catch (e: Exception) {
                    "Error reading log file: ${e.message}"
                }
            }
        }
        return "No diagnostic logs captured yet."
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
        val base = logsBaseDir ?: return
        diskScope.launch {
            try {
                if (instanceId == null) {
                    File(base, "linex_global.log").delete()
                } else {
                    File(base, "instance_${instanceId}.log").delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete log file on disk: ${e.message}")
            }
        }
    }

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
