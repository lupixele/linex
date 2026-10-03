package com.linex.app.core

/** Geometry is configured by the attached Lorie surface, not an unsupported -screen argument. */
object NativeX11Arguments {
    fun build(dpi: Int, authorityPath: String): Array<String> {
        require(dpi in 48..480) { "Invalid display DPI" }
        require(authorityPath.startsWith("/")) { "Authority must be an absolute private path" }
        // PRoot emulates SysV IPC inside the guest; Android's native server cannot
        // attach those segments. Disable MIT-SHM until that boundary is bridged.
        // Android lacks upstream's /usr/share/fonts path. libXfont includes the
        // core fixed/cursor fonts; applications still load guest fontconfig fonts.
        return arrayOf(":0", "-nolisten", "tcp", "-auth", authorityPath, "-dpi", dpi.toString(),
            "-extension", "MIT-SHM", "-fp", "built-ins")
    }
}
