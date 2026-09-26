package com.linex.app.data

import android.content.Context
import android.util.Log
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Manages persistence of Linex container instances using internal storage JSON.
 */
class InstanceRepository(private val context: Context) {

    companion object {
        private const val TAG = "InstanceRepository"
        private const val FILE_NAME = "instances.json"
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    fun getDefaultInstances(): List<LinuxInstance> {
        return listOf(
            LinuxInstance(
                id = UUID.randomUUID().toString(),
                name = "Ubuntu Workstation",
                distro = DistroType.UBUNTU_JAMMY,
                desktop = DesktopEnvironment.XFCE4,
                resolutionMode = DisplayResolutionMode.NATIVE_PHONE,
                dpiScaling = 120,
                ramAllocatedMb = 2048,
                state = ContainerState.STOPPED
            ),
            LinuxInstance(
                id = UUID.randomUUID().toString(),
                name = "Ubuntu Touch Mobile",
                distro = DistroType.UBUNTU_JAMMY,
                desktop = DesktopEnvironment.UBUNTU_TOUCH_PHOSH,
                resolutionMode = DisplayResolutionMode.NATIVE_PHONE,
                dpiScaling = 140,
                ramAllocatedMb = 1536,
                state = ContainerState.STOPPED
            )
        )
    }

    fun loadInstancesSync(): List<LinuxInstance> {
        return try {
            if (!file.exists()) {
                val defaults = getDefaultInstances()
                saveInstancesSync(defaults)
                defaults
            } else {
                val content = AtomicFile(file).openRead().bufferedReader().use { it.readText() }
                if (content.isBlank()) {
                    val defaults = getDefaultInstances()
                    saveInstancesSync(defaults)
                    defaults
                } else {
                    val saved = json.decodeFromString<List<LinuxInstance>>(content)
                    // Synchronize latest distro URLs and metadata from code into persisted instances
                    saved.map { inst ->
                        // Re-align with current DistroType values in case URLs or specs updated
                        val currentDistro = try {
                            DistroType.valueOf(inst.distro.name)
                        } catch (e: Exception) {
                            inst.distro
                        }
                        inst.copy(distro = currentDistro, state = ContainerState.STOPPED)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load instances from ${file.absolutePath}; preserving saved data", e)
            throw e
        }
    }

    @Synchronized
    fun saveInstancesSync(instances: List<LinuxInstance>) {
        val atomicFile = AtomicFile(file)
        var stream: java.io.FileOutputStream? = null
        try {
            val content = json.encodeToString(instances.map { it.copy(state = ContainerState.STOPPED) })
            stream = atomicFile.startWrite()
            stream.write(content.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (e: Exception) {
            atomicFile.failWrite(stream)
            Log.e(TAG, "Failed to save instances to ${file.absolutePath}", e)
            throw e
        }
    }

    suspend fun loadInstances(): List<LinuxInstance> = withContext(Dispatchers.IO) {
        loadInstancesSync()
    }

    suspend fun saveInstances(instances: List<LinuxInstance>) = withContext(Dispatchers.IO) {
        saveInstancesSync(instances)
    }
}
