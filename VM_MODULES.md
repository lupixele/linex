# Full VM capability map

Status: module boundaries and build order approved by the user's “proceed” on
2026-10-03. Engine specification: SPEC-vm-engine.md (approved for implementation).

| Module | Responsibility | Depends on |
| --- | --- | --- |
| vm-engine | Embed maintained ARM64 full-system QEMU in a nonexported Android-managed service; boot a kernel, own lifecycle/control and rootless networking, retain bounded diagnostics. | — |
| vm-images | Obtain verified boot assets and private writable VM disks, retain metadata and resumable setup progress; preserve existing PRoot instances. | vm-engine boot contract |
| vm-console | Connect the VM framebuffer through private authenticated sockets and reuse Linex fullscreen, keyboard, mouse and touchpad controls. | vm-engine display contract |
| vm-app | Expose new VM instances, actual guest RAM allocation, foreground notifications and supported resource metrics. | vm-engine, vm-images, vm-console |

Build order: vm-engine boot proof → vm-images and vm-console → vm-app.
The engine proof uses a small fixture kernel/initramfs, not an existing rootfs directory.

The first proof must boot a real Linux kernel without root inside an Android-managed
process, show that guest forks do not create Android child processes, and shut down
cleanly. No VM option becomes selectable before this proof. Production guest images
and desktop/browser networking follow; booting an initramfs is not a completed desktop.

Use CPU emulation initially. Do not require KVM, root, shell-granted permissions or
Android policy changes. Measure Snapdragon732G performance; host GPU presentation
does not supply guest GPU acceleration or hardware video decoding automatically.

PRoot instances remain readable and usable. A VM is a separate instance with a
bootable disk and kernel/firmware contract; conversion requires a later explicit flow.
Android may still reclaim the managed VM under memory/OEM policy. The architecture
removes the per-Linux-process Android child multiplication, not every possible kill.

Current prerequisites: Windows host has JDK/SDK/NDK/CMake and authenticated GitHub CLI.
WSL is not installed; Docker/QEMU are absent. A Linux build runner is needed for the
modern Android QEMU embedding proof. Old Limbo binaries are reference material only.
