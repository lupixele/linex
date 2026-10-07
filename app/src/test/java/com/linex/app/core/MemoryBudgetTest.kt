package com.linex.app.core

import com.linex.app.data.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class MemoryBudgetTest {
    private val legacy = """{"id":"old","name":"Linux","distro":"UBUNTU_JAMMY","desktop":"XFCE4","resolutionMode":"HD_720P","ramAllocatedMb":3072}"""
    @Test fun legacyCustomBudgetSurvivesMigration() {
        val instance = Json.decodeFromString<LinuxInstance>(legacy)
        assertEquals(MemoryBudgetMode.CUSTOM, MemoryBudget.mode(instance))
        assertEquals(3072, MemoryBudget.resolveMb(instance, 8192))
    }
    @Test fun recommendedLeavesAndroidHeadroom() {
        assertEquals(2048, MemoryBudget.recommendedMb(6144))
        assertEquals(4096, MemoryBudget.recommendedMb(24000))
        assertEquals(2048, MemoryBudget.recommendedMb(0))
        assertEquals(512, MemoryBudget.recommendedMb(1024))
        assertEquals(1536, MemoryBudget.recommendedMb(3072, 1536))
        assertEquals(128, MemoryBudget.recommendedMb(128))
    }
    @Test fun customRejectsOverflowAndImpossibleDeviceBudget() {
        assertNotNull(MemoryBudget.error("999999999999", 8192))
        assertNotNull(MemoryBudget.error("8193", 8192))
        assertNotNull(MemoryBudget.error("255", 8192))
        assertNull(MemoryBudget.error("3072", 8192))
    }
    @Test fun modeAndCustomValueRoundTrip() {
        val instance = Json.decodeFromString<LinuxInstance>(legacy).copy(memoryBudgetMode = MemoryBudgetMode.RECOMMENDED)
        assertEquals(instance, Json.decodeFromString<LinuxInstance>(Json.encodeToString(instance)))
    }
    @Test fun vmAllocationsAreBoundedAndKeepTheRequestedCustomValue() {
        val instance = Json.decodeFromString<LinuxInstance>(legacy).copy(runtime = InstanceRuntime.FULL_VM)
        assertEquals(3072, MemoryBudget.resolveMb(instance, 8192))
        assertEquals(1024, MemoryBudget.resolveMb(instance.copy(memoryBudgetMode = MemoryBudgetMode.DEFAULT), 6144))
        assertEquals(1536, MemoryBudget.resolveMb(instance.copy(memoryBudgetMode = MemoryBudgetMode.RECOMMENDED), 6144))
        assertEquals(2048, MemoryBudget.resolveMb(instance.copy(memoryBudgetMode = MemoryBudgetMode.RECOMMENDED), 24000))
        assertNotNull(MemoryBudget.error("4097", 8192, InstanceRuntime.FULL_VM))
        assertNull(MemoryBudget.error("4096", 8192, InstanceRuntime.FULL_VM))
        assertEquals(5000, MemoryBudget.resolveMb(instance.copy(ramAllocatedMb = 5000), 8192))
    }
}
