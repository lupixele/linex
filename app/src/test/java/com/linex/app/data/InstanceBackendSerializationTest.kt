package com.linex.app.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class InstanceBackendSerializationTest {
    private val legacy = """{"id":"old","name":"Linux","distro":"UBUNTU_JAMMY","desktop":"XFCE4","resolutionMode":"HD_720P","ramAllocatedMb":3072,"desktopFps":60}"""

    @Test fun oldInstancesDefaultToAutoAndKeepExistingSettings() {
        val instance = Json.decodeFromString<LinuxInstance>(legacy)
        assertEquals(DisplayBackendPreference.AUTO, instance.displayBackend)
        assertEquals(3072, instance.ramAllocatedMb)
        assertEquals(60, instance.desktopFps)
        assertEquals("old", instance.id)
    }

    @Test fun explicitCompatibilityBackendPersistsAcrossReload() {
        val instance = Json.decodeFromString<LinuxInstance>(legacy).copy(displayBackend = DisplayBackendPreference.RFB)
        assertEquals(instance, Json.decodeFromString<LinuxInstance>(Json.encodeToString(instance)))
    }

    @Test fun olderInstancesWithoutFrameRateKeepTheirOriginalDefault() {
        val json = """{"id":"legacy","name":"Linux","distro":"UBUNTU_JAMMY","desktop":"XFCE4","resolutionMode":"HD_720P"}"""
        val instance = Json.decodeFromString<LinuxInstance>(json)
        assertEquals(15, instance.desktopFps)
        assertEquals(instance, Json.decodeFromString<LinuxInstance>(Json.encodeToString(instance)))
    }

    @Test fun explicitNativeBackendPersistsAcrossReload() {
        val instance = Json.decodeFromString<LinuxInstance>(legacy).copy(displayBackend = DisplayBackendPreference.NATIVE_X11)
        assertEquals(instance, Json.decodeFromString<LinuxInstance>(Json.encodeToString(instance)))
    }
}
