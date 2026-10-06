package com.linex.vm

/** Fixed rootless backend choices; never accepts a caller-supplied QEMU network string. */
enum class VmNetworkMode(val wireValue: Int) {
    DISABLED(0), SLIRP_V4(1);

    internal fun qemuArguments(): Array<String> = when (this) {
        DISABLED -> arrayOf("-nic", "none")
        SLIRP_V4 -> arrayOf("-netdev", "user,id=linexnet,ipv6=off",
            "-device", "virtio-net-device,netdev=linexnet")
    }

    companion object {
        fun fromWire(value: Int): VmNetworkMode = entries.singleOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unsupported VM network mode")
    }
}
