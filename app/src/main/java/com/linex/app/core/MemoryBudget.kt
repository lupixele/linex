package com.linex.app.core

import com.linex.app.data.LinuxInstance
import com.linex.app.data.MemoryBudgetMode
import com.linex.app.data.InstanceRuntime

/** PRoot values are planning targets; a full VM receives a real guest RAM allocation. */
object MemoryBudget {
    const val DEFAULT_MB = 2048
    const val MIN_MB = 256
    const val MAX_MB = 65536

    fun mode(instance: LinuxInstance): MemoryBudgetMode = instance.memoryBudgetMode
        ?: if (instance.ramAllocatedMb == DEFAULT_MB) MemoryBudgetMode.DEFAULT else MemoryBudgetMode.CUSTOM

    fun maximumMb(totalRamMb: Long, runtime: InstanceRuntime = InstanceRuntime.PROOT): Int {
        val cap = if (runtime == InstanceRuntime.FULL_VM) 4096 else MAX_MB
        return if (totalRamMb > 0) totalRamMb.coerceAtMost(cap.toLong()).toInt() else cap
    }

    fun recommendedMb(totalRamMb: Long, desktopMinimumMb: Int = 512): Int = if (totalRamMb <= 0) DEFAULT_MB else
        (totalRamMb / 3).coerceIn(desktopMinimumMb.coerceIn(MIN_MB, 4096).toLong(), 4096L)
            .toInt().coerceAtMost(maximumMb(totalRamMb))

    fun resolveMb(instance: LinuxInstance, totalRamMb: Long): Int = if (instance.runtime == InstanceRuntime.FULL_VM) {
        when (mode(instance)) {
            MemoryBudgetMode.DEFAULT -> 1024
            MemoryBudgetMode.RECOMMENDED -> (totalRamMb / 4).coerceIn(1024, 2048).toInt()
            MemoryBudgetMode.CUSTOM -> instance.ramAllocatedMb
        }
    } else when (mode(instance)) {
        MemoryBudgetMode.DEFAULT -> DEFAULT_MB.coerceAtMost(maximumMb(totalRamMb))
        MemoryBudgetMode.RECOMMENDED -> recommendedMb(totalRamMb, instance.desktop.recommendedRamMb)
        MemoryBudgetMode.CUSTOM -> instance.ramAllocatedMb.coerceIn(1, maximumMb(totalRamMb))
    }

    fun error(value: String, totalRamMb: Long, runtime: InstanceRuntime = InstanceRuntime.PROOT): String? {
        val mb = value.toIntOrNull() ?: return "Enter a whole number of MiB."
        val maximum = maximumMb(totalRamMb, runtime)
        return if (mb < MIN_MB || mb > maximum) "Choose $MIN_MB–$maximum MiB." else null
    }
}
