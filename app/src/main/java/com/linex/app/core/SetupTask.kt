package com.linex.app.core

enum class SetupStatus { RUNNING, COMPLETE, FAILED, CANCELLED }

/** A service-owned operation; fraction is local to its current stage, not a time estimate. */
data class SetupTask(
    val instanceId: String,
    val name: String,
    val fraction: Float,
    val message: String,
    val stage: String,
    val startedAtMillis: Long,
    val lastProgressAtMillis: Long,
    val status: SetupStatus,
    val kind: String = "setup"
) {
    val progressPercent: Int? get() = if (fraction.isFinite() && fraction >= 0f)
        (fraction.coerceIn(0f, 1f) * 100).toInt() else null
}
