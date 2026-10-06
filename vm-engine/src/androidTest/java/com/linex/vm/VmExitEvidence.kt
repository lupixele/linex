package com.linex.vm

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Test diagnostics only. Exit status is evidence, never attribution to a killer. */
internal class VmExitEvidence(private val context: Context, private val launchIndex: Int) {
    private val launchTimeMillis = System.currentTimeMillis()
    var pid = 0
    var phase = "before_bind"
    var lastVmState: String? = null
    var failureClass: String? = null
    var binderAliveBeforeCleanup: Boolean? = null
    var binderAliveAfterCleanup: Boolean? = null
    var forceStopAccepted: Boolean? = null
    var cleanupExitObserved: Boolean? = null
    private val cleanupErrors = mutableListOf<String>()

    fun cleanupError(action: String, error: Throwable) {
        if (cleanupErrors.size < 8) cleanupErrors.add("$action:${error.javaClass.name.take(160)}")
    }

    fun write() {
        // Diagnostics must not replace the boot assertion or cleanup failure.
        runCatching {
            val report = buildJsonObject {
                put("androidSdk", Build.VERSION.SDK_INT)
                put("processName", "${context.packageName}:vm")
                put("pid", pid)
                put("launchTimeMillis", launchTimeMillis)
                put("phase", phase.take(64))
                lastVmState?.let { put("lastVmState", it.take(64)) }
                failureClass?.let { put("failureClass", it.take(160)) }
                binderAliveBeforeCleanup?.let { put("binderAliveBeforeCleanup", it) }
                binderAliveAfterCleanup?.let { put("binderAliveAfterCleanup", it) }
                forceStopAccepted?.let { put("forceStopAccepted", it) }
                cleanupExitObserved?.let { put("cleanupExitObserved", it) }
                put("cleanupErrors", JsonArray(cleanupErrors.map { JsonPrimitive(it) }))
                put("killerAttribution", "not_inferred_from_signal")
                when {
                    Build.VERSION.SDK_INT < 30 -> put("exitHistoryUnavailable", "requires_api_30")
                    pid <= 0 -> put("exitHistoryUnavailable", "pid_not_observed")
                    else -> {
                        runCatching { collectHistory() }
                            .onSuccess { put("exitHistory", it) }
                            .onFailure { put("exitHistoryErrorClass", it.javaClass.name.take(160)) }
                    }
                }
            }
            File(context.filesDir, "vm-proof-$launchIndex-exit.json").writeText(report.toString())
        }
    }

    @RequiresApi(30)
    private fun collectHistory(): JsonArray {
        val manager = requireNotNull(context.getSystemService(ActivityManager::class.java))
        val processName = "${context.packageName}:vm"
        // ActivityManager records death asynchronously. Three reads add at most 500ms delay.
        repeat(3) { attempt ->
            val records = manager.getHistoricalProcessExitReasons(context.packageName, pid, 4)
                .take(4).filter {
                    it.pid == pid && it.processName == processName && it.timestamp >= launchTimeMillis
                }
            if (records.isNotEmpty() || attempt == 2 || binderAliveAfterCleanup == true) {
                return JsonArray(records.map { exit ->
                    buildJsonObject {
                        put("pid", exit.pid); put("timestampMillis", exit.timestamp)
                        put("reason", exit.reason); put("status", exit.status)
                        put("importance", exit.importance)
                        put("lastSampleRssKb", exit.rss); put("lastSamplePssKb", exit.pss)
                        put("lowMemoryKillReportsSupported", ActivityManager.isLowMemoryKillReportSupported())
                        exit.description?.let { description ->
                            put("systemDescription", description.take(512).map {
                                if (it.isISOControl()) ' ' else it
                            }.joinToString(""))
                        }
                    }
                })
            }
            Thread.sleep(250)
        }
        return JsonArray(emptyList())
    }
}
