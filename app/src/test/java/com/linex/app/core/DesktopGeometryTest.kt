package com.linex.app.core

import com.linex.app.data.*
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopGeometryTest {
    private val instance = LinuxInstance("test", "Desktop", DistroType.UBUNTU_JAMMY, DesktopEnvironment.XFCE4, DisplayResolutionMode.NATIVE_PHONE)

    @Test fun nativeDesktopIsLandscapeRegardlessOfLaunchOrientation() {
        assertEquals(2400 to 1080, DesktopGeometry.resolve(instance, 1080, 2400))
        assertEquals(2400 to 1080, DesktopGeometry.resolve(instance, 2400, 1080))
    }

    @Test fun preservesCustomPortraitResolutionAndStandardProfiles() {
        assertEquals(1080 to 1920, DesktopGeometry.resolve(instance.copy(resolutionMode = DisplayResolutionMode.CUSTOM, customWidth = 1080, customHeight = 1920), 1080, 2400))
        assertEquals(1280 to 720, DesktopGeometry.resolve(instance.copy(resolutionMode = DisplayResolutionMode.HD_720P), 1080, 2400))
    }
}
