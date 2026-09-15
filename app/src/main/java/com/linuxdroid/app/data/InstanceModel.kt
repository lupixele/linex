package com.linuxdroid.app.data

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
        displayName = "Ubuntu 22.04 LTS (Jammy)",
        description = "Solid base with full apt ecosystem, Python 3.10+, and preconfigured userspace.",
        rootfsDownloadUrl = "https://github.com/linuxdroid-containers/rootfs/releases/download/v1.0/ubuntu-jammy-arm64.tar.gz",
        sha256 = "mock-sha256-ubuntu-jammy",
        estimatedSizeMb = 350
    ),
    DEBIAN_BOOKWORM(
        displayName = "Debian 12 (Bookworm)",
        description = "Minimal, ultra-stable, rock-bottom RAM usage for low-spec devices.",
        rootfsDownloadUrl = "https://github.com/linuxdroid-containers/rootfs/releases/download/v1.0/debian-bookworm-arm64.tar.gz",
        sha256 = "mock-sha256-debian-bookworm",
        estimatedSizeMb = 280
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
        startCommand = "/linuxdroid/start_xfce.sh",
        isTouchOptimized = false,
        recommendedRamMb = 512
    ),
    GNOME_FLASHBACK(
        displayName = "GNOME Classic (Flashback)",
        subtitle = "Zero systemd dependency, classic top/bottom panels, very low CPU usage.",
        startCommand = "/linuxdroid/start_gnome_flashback.sh",
        isTouchOptimized = false,
        recommendedRamMb = 768
    ),
    GNOME_SHELL(
        displayName = "Modern GNOME Shell",
        subtitle = "Modern workflow with Activities and overview gestures. Requires fast GPU.",
        startCommand = "/linuxdroid/start_gnome_flashback.sh",
        isTouchOptimized = false,
        recommendedRamMb = 1536
    ),
    UBUNTU_TOUCH_PHOSH(
        displayName = "Ubuntu Mobile (Phosh Touch Shell)",
        subtitle = "Built for phone screens. Swipe gestures, app drawer, on-screen touch keyboard.",
        startCommand = "/linuxdroid/start_phosh.sh",
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
