package com.linex.app.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class InstanceFrameRateSerializationTest {
    private val legacyJson = """{
        "id":"existing-instance",
        "name":"Workstation",
        "distro":"UBUNTU_JAMMY",
        "desktop":"XFCE4",
        "resolutionMode":"HD_720P"
    }"""

    @Test fun legacyInstanceKeepsBatterySavingDefault() {
        val instance = Json.decodeFromString<LinuxInstance>(legacyJson)
        assertEquals(15, instance.desktopFps)
        assertEquals("existing-instance", instance.id)
    }

    @Test fun highFrameRateSurvivesSavingAndLoading() {
        val instance = Json.decodeFromString<LinuxInstance>(legacyJson).copy(desktopFps = 144)
        val reloaded = Json.decodeFromString<LinuxInstance>(Json.encodeToString(instance))
        assertEquals(144, reloaded.desktopFps)
        assertEquals(instance, reloaded)
    }
}
