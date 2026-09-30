package com.linex.app.core

import com.linex.app.data.DisplayResolutionMode
import com.linex.app.data.LinuxInstance

object DesktopGeometry {
    /** Native sessions start in landscape even when launched from the portrait instance list. */
    fun resolve(instance: LinuxInstance, screenWidth: Int, screenHeight: Int): Pair<Int, Int> =
        when (instance.resolutionMode) {
            DisplayResolutionMode.NATIVE_PHONE -> maxOf(screenWidth, screenHeight) to minOf(screenWidth, screenHeight)
            DisplayResolutionMode.HD_720P -> 1280 to 720
            DisplayResolutionMode.FULL_HD_1080P, DisplayResolutionMode.DEX_AUTO -> 1920 to 1080
            DisplayResolutionMode.CUSTOM -> instance.customWidth to instance.customHeight
        }
}
