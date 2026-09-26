package com.linex.app.core

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object AppLogger {
    private const val TAG = "LinexLogger"
    private const val MAX_ENTRIES_PER_INSTANCE = 1000
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading
    private val _storageError = MutableStateFlow<String?>(null)
    val storageError: StateFlow<String?> = _storageError
    private val worker = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private var initialized = false
    private var store: DiagnosticLogStore? = null

    @Synchronized fun init(context: Context) {
        if (initialized) return
        initialized = true
        val directory = File(context.applicationContext.filesDir, "logs")
        worker.launch {
            try {
                store = DiagnosticLogStore(directory)
                _logs.value = store!!.load()
            } catch (e: Exception) {
                storageFailure("Could not restore saved diagnostics", e)
            } finally { _loading.value = false }
        }
    }

    @Synchronized fun log(tag: String, message: String, instanceId: String? = null) {
        val entry = LogEntry(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()),
            instanceId, tag.take(100), message.take(4096))
        Log.i(tag, entry.toString())
        worker.launch {
            val history = _logs.value
            val sameInstance = history.filter { it.instanceId == instanceId }.takeLast(MAX_ENTRIES_PER_INSTANCE - 1)
            _logs.value = (history.filter { it.instanceId != instanceId } + sameInstance + entry).sortedBy { it.timestamp }
            try { store?.append(entry) } catch (e: Exception) { storageFailure("Could not save diagnostics", e) }
        }
    }

    fun getLogsForInstance(instanceId: String?): List<LogEntry> =
        _logs.value.let { entries -> if (instanceId == null) entries else entries.filter { it.instanceId == instanceId } }

    fun getLogsAsText(instanceId: String? = null): String =
        getLogsForInstance(instanceId).joinToString("\n").ifEmpty { "No diagnostic logs captured yet." }

    @Synchronized fun clear(instanceId: String? = null) {
        worker.launch {
            try {
                store?.clear(instanceId)
                _logs.value = if (instanceId == null) emptyList() else _logs.value.filter { it.instanceId != instanceId }
                _storageError.value = null
            } catch (e: Exception) { storageFailure("Could not clear saved diagnostics", e) }
        }
    }

    fun shareLogs(context: Context, instanceId: String? = null, instanceName: String? = null) {
        worker.launch {
            try {
                val safeName = (instanceName ?: "all").replace(Regex("[^A-Za-z0-9_-]"), "_").take(60)
                val logFile = File(context.cacheDir, "linex_${safeName}_logs.txt")
                logFile.writeText(getLogsAsText(instanceId))
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", logFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Linex diagnostics — ${instanceName ?: "All"}")
                    clipData = ClipData.newRawUri("Linex diagnostics", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent.createChooser(shareIntent, "Export diagnostics").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not export diagnostics", e)
                withContext(Dispatchers.Main) { Toast.makeText(context, "Could not export logs: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun storageFailure(message: String, error: Exception) {
        Log.w(TAG, message, error)
        _storageError.value = "$message. Live output is still available."
    }
}
