package com.linex.app.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class InstanceRuntimeSerializationTest {
    private val old = """{"id":"existing","name":"My Ubuntu","distro":"UBUNTU_JAMMY","desktop":"XFCE4","resolutionMode":"HD_720P"}"""
    @Test fun existingInstancesKeepTheirRootFilesystemRuntime() {
        val instance = Json.decodeFromString<LinuxInstance>(old)
        assertEquals(InstanceRuntime.PROOT, instance.runtime)
        assertNull(instance.vmImageId)
        assertEquals("existing", instance.id)
        assertEquals(DistroType.UBUNTU_JAMMY, instance.distro)
    }
    @Test fun vmImageIdentityAndAllocatedRamSurviveSettingsRoundTrip() {
        val instance = Json.decodeFromString<LinuxInstance>(old).copy(
            runtime = InstanceRuntime.FULL_VM, distro = DistroType.DEBIAN_TRIXIE_VM,
            vmImageId = "debian-trixie-desktop", ramAllocatedMb = 1536, memoryBudgetMode = MemoryBudgetMode.CUSTOM)
        assertEquals(instance, Json.decodeFromString<LinuxInstance>(Json.encodeToString(instance)))
    }
}
