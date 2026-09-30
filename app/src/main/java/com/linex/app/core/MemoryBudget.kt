package com.linex.app.core

import com.linex.app.data.LinuxInstance
import com.linex.app.data.MemoryBudgetMode

/** Planning target only: rootless PRoot shares Android's memory, with no RAM reservation. */
object MemoryBudget {
    const val DEFAULT_MB = 2048
    const val MIN_MB = 256
    const val MAX_MB = 65536

    fun mode(instance: LinuxInstance): MemoryBudgetMode = instance.memoryBudgetMode
        ?: if (instance.ramAllocatedMb == DEFAULT_MB) MemoryBudgetMode.DEFAULT else MemoryBudgetMode.CUSTOM

    fun maximumMb(totalRamMb: Long): Int = if (totalRamMb > 0)
        totalRamMb.coerceAtMost(MAX_MB.toLong()).toInt() else MAX_MB

    fun recommendedMb(totalRamMb: Long, desktopMinimumMb: Int = 512): Int = if (totalRamMb <= 0) DEFAULT_MB else
        (totalRamMb / 3).coerceIn(desktopMinimumMb.coerceIn(MIN_MB, 4096).toLong(), 4096L)
            .toInt().coerceAtMost(maximumMb(totalRamMb))

    fun resolveMb(instance: LinuxInstance, totalRamMb: Long): Int = when (mode(instance)) {
        MemoryBudgetMode.DEFAULT -> DEFAULT_MB.coerceAtMost(maximumMb(totalRamMb))
        MemoryBudgetMode.RECOMMENDED -> recommendedMb(totalRamMb, instance.desktop.recommendedRamMb)
        MemoryBudgetMode.CUSTOM -> instance.ramAllocatedMb.coerceIn(1, maximumMb(totalRamMb))
    }

    fun error(value: String, totalRamMb: Long): String? {
        val mb = value.toIntOrNull() ?: return "Enter a whole number of MiB."
        val maximum = maximumMb(totalRamMb)
        return if (mb < MIN_MB || mb > maximum) "Choose $MIN_MB–$maximum MiB." else null
    }
}
