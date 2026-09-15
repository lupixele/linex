package com.linex.app.data

import kotlinx.serialization.Serializable

@Serializable
enum class DistroType(
    val displayName: String,
    val description: String,
    val rootfsDownloadUrl: String,
    val sha256: String,
    val estimatedSizeMb: Int
) {
    UBUNTU_JAMMY(
        displayName = "Ubuntu 22.04 LTS (Jammy XFCE4)",
        description = "Full featured Ubuntu 22.04 LTS desktop with XFCE4, audio, and development tools pre-installed.",
        rootfsDownloadUrl = "https://github.com/RandomCoderOrg/ubuntu-on-android/releases/download/v3/udroid-arm64-xfce4-V3MBB3.tar.gz",
        sha256 = "d5290068b99740da5198a355b5152e0198fff126f0865f4c4634e509508a6da8",
        estimatedSizeMb = 1919
    ),
    DEBIAN_BOOKWORM(
        displayName = "Ubuntu 22.04 LTS (Jammy Minimal)",
        description = "Ultra lightweight minimal Ubuntu 22.04 base. Fast download (172 MB), minimal storage footprint.",
        rootfsDownloadUrl = "https://github.com/RandomCoderOrg/udroid-download/releases/download/V3R115/jammy-raw-arm64.tar.gz",
        sha256 = "0ab96cbeebc5d8fc86a9baf7d1127f28ac7ea7a2ff862ac31fd7ac8768877b82",
        estimatedSizeMb = 172
    )
}

@Serializable
enum class DesktopEnvironment(
    val displayName: String,
    val subtitle: String,
    val startCommand: String,
    val isTouchOptimized: Boolean,
    val recommendedRamMb: Int
) {
    XFCE4(
        displayName = "Ubuntu Desktop (XFCE4)",
        subtitle = "Lightweight, responsive, traditional windowed desktop. Perfect for work.",
        startCommand = "/linex/start_xfce.sh",
        isTouchOptimized = false,
        recommendedRamMb = 512
    ),
    GNOME_FLASHBACK(
        displayName = "GNOME Classic (Flashback)",
        subtitle = "Zero systemd dependency, classic top/bottom panels, very low CPU usage.",
        startCommand = "/linex/start_gnome_flashback.sh",
        isTouchOptimized = false,
        recommendedRamMb = 768
    ),
    GNOME_SHELL(
        displayName = "Modern GNOME Shell",
        subtitle = "Modern workflow with Activities and overview gestures. Requires fast GPU.",
        startCommand = "/linex/start_gnome_flashback.sh",
        isTouchOptimized = false,
        recommendedRamMb = 1536
    ),
    UBUNTU_TOUCH_PHOSH(
        displayName = "Ubuntu Mobile (Phosh Touch Shell)",
        subtitle = "Built for phone screens. Swipe gestures, app drawer, on-screen touch keyboard.",
        startCommand = "/linex/start_phosh.sh",
        isTouchOptimized = true,
        recommendedRamMb = 1024
    )
}

@Serializable
enum class DisplayResolutionMode(val displayName: String) {
    NATIVE_PHONE("Native Phone Screen"),
    FULL_HD_1080P("1080p Standard (1920x1080)"),
    HD_720P("720p Battery-Saver (1280x720)"),
    DEX_AUTO("Samsung DeX / External Auto-Detect"),
    CUSTOM("Custom Resolution")
}

@Serializable
enum class ContainerState {
    STOPPED,
    STARTING,
    RUNNING,
    SUSPENDED
}

@Serializable
data class LinuxInstance(
    val id: String,
    val name: String,
    val distro: DistroType,
    val desktop: DesktopEnvironment,
    val resolutionMode: DisplayResolutionMode,
    val customWidth: Int = 1920,
    val customHeight: Int = 1080,
    val dpiScaling: Int = 120, // 96 to 240
    val pointerLockEnabled: Boolean = true,
    val state: ContainerState = ContainerState.STOPPED,
    val ramAllocatedMb: Int = 2048,
    val snapshotPath: String? = null
)
