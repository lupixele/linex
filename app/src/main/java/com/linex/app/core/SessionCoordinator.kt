package com.linex.app.core

import android.content.Context
import android.app.ActivityManager
import com.linex.app.data.ContainerState
import com.linex.app.data.DistroType
import com.linex.app.data.InstanceRuntime
import com.linex.app.data.LinuxInstance
import com.linex.vm.VmDesktopBootRequest
import com.linex.vm.images.VmImageInstaller
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** Chooses a runtime without changing existing instance storage or settings. */
class SessionCoordinator(private val context: Context, private val proot: ContainerManager,
                         private val images: VmImageInstaller) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val virtualMachine = VmSessionManager(context)
    private val launchLock = Mutex()
    private val pending = MutableStateFlow<Map<String, ContainerState>>(emptyMap())
    val currentState = combine(proot.currentState, virtualMachine.state, pending) { containers, vms, starting -> containers + vms + starting }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun getInstanceState(id: String): ContainerState = pending.value[id] ?: virtualMachine.state.value[id] ?: proot.getInstanceState(id)
    fun getDisplayEndpoint(id: String) = virtualMachine.getEndpoint(id) ?: proot.getDisplayEndpoint(id)
    fun getProcessGroup(id: String) = proot.getProcessGroup(id)
    fun getVmProcessId(id: String) = virtualMachine.getProcessId(id)

    suspend fun launchInstance(instance: LinuxInstance, onLog: (String) -> Unit): Boolean = launchLock.withLock {
        if (instance.runtime == InstanceRuntime.PROOT) {
            if (virtualMachine.isActive()) {
                AppLogger.log("Session", "Stop the active virtual machine before starting another instance", instance.id)
                return@withLock false
            }
            require(instance.distro != DistroType.DEBIAN_TRIXIE_VM) { "The Debian VM image requires the virtual machine runtime" }
            return@withLock proot.launchInstance(instance, onLog)
        }
        pending.value = mapOf(instance.id to ContainerState.STARTING)
        try {
            check(proot.currentState.value.values.none { it != ContainerState.STOPPED } && !virtualMachine.isActive()) {
                "Stop the active session before starting another instance"
            }
            require(instance.distro == DistroType.DEBIAN_TRIXIE_VM && instance.vmImageId == "debian-trixie-desktop") {
                "Unsupported virtual machine image"
            }
            val image = requireNotNull(images.readInstalled(instance.id)) { "Install this instance's VM image before starting it" }
            require(image.imageId == instance.vmImageId) { "Installed disk belongs to a different VM image" }
            val token = UUID.randomUUID().toString().replace("-", "")
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            val geometry = proot.calculateDisplayGeometry(instance)
            val request = VmDesktopBootRequest(token, instance.id, image.kernelFile.absolutePath, image.kernelSha256,
                image.initramfsFile.absolutePath, image.initramfsSha256, image.diskFile.absolutePath, image.diskBytes,
                File(image.diskFile.parentFile, "s${token.take(8)}").absolutePath,
                MemoryBudget.resolveMb(instance, memory.totalMem / 1048576), 2)
            virtualMachine.launch(request, geometry.first, geometry.second, DesktopFrameRate.normalized(instance.desktopFps))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLogger.log("VirtualMachine", failure.message ?: "Could not start the virtual machine", instance.id)
            false
        } finally {
            pending.value = emptyMap()
        }
    }

    /** Serializes storage changes against launch registration, including pre-engine serial binding. */
    suspend fun <T> withStoppedInstance(instanceId: String, operation: suspend () -> T): T = launchLock.withLock {
        check(getInstanceState(instanceId) == ContainerState.STOPPED) { "Stop this instance before changing its disk" }
        operation()
    }

    suspend fun suspendActiveInstance(): Boolean = if (virtualMachine.isActive()) virtualMachine.pause() else proot.suspendActiveInstance()
    suspend fun resumeActiveInstance(): Boolean = if (virtualMachine.isActive()) virtualMachine.resume() else proot.resumeActiveInstance()
    suspend fun stopActiveInstance(): Boolean {
        if (virtualMachine.isActive()) return virtualMachine.stop()
        val ids = proot.currentState.value.filterValues { it != ContainerState.STOPPED }.keys
        if (!proot.stopActiveInstance()) return false
        return withTimeoutOrNull(10000) {
            proot.currentState.first { states -> ids.all { states[it] == ContainerState.STOPPED } }
            true
        } ?: false
    }
    fun close() { virtualMachine.close(); proot.stopActiveInstance(); scope.cancel() }
}
