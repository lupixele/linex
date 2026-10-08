package com.linex.app.core

import android.graphics.Bitmap
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linex.app.data.ContainerState
import com.linex.vm.VmDesktopBootRequest
import com.linex.vm.console.PrivateUnixRfbTransport
import com.linex.vm.images.PinnedVmImage
import com.linex.vm.images.VmAssetSource
import com.linex.vm.images.VmImageAsset
import com.linex.vm.images.VmImageCompression
import com.linex.vm.images.VmImageInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Boots the production candidate, never a synthetic framebuffer or substitute guest. */
@RunWith(AndroidJUnit4::class)
class VmDesktopProofTest {
    @Test fun verifiedFactoryBootPrivateDesktopAndFreshProcessRestart() = runBlocking {
        withContext(Dispatchers.IO) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val expected = requireNotNull(InstrumentationRegistry.getArguments().getString("desktopManifestSha256")) {
                "The candidate manifest must be pinned by desktopManifestSha256"
            }
            require(expected.matches(Regex("[a-f0-9]{64}")))
            val manifestFile = File(context.filesDir, "vm-image-fixtures/manifest.json")
            val stat = Os.lstat(manifestFile.path)
            check(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() && stat.st_size in 1..65536)
            val manifestBytes = manifestFile.readBytes()
            assertEquals("Candidate manifest bytes differ from CI pin", expected, sha256(manifestBytes))
            val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
            assertEquals(1, manifest.getInt("schema"))
            assertEquals("aarch64", manifest.getString("architecture"))
            assertEquals("raw", manifest.getJSONObject("disk").getString("format"))
            assertEquals("ext4", manifest.getJSONObject("disk").getString("filesystem"))
            assertEquals("xz", manifest.getJSONObject("download").getString("compression"))
            fun asset(key: String): VmImageAsset {
                val value = manifest.getJSONObject(key)
                val name = value.getString("file")
                require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")))
                return VmImageAsset(name, value.getString("sha256"), value.getLong("bytes"),
                    VmAssetSource.PrivateFile("vm-image-fixtures/$name"))
            }
            val image = PinnedVmImage(manifest.getString("imageId"), "proof-${expected.take(16)}",
                asset("kernel"), asset("initramfs"), asset("download"),
                manifest.getJSONObject("disk").getLong("bytes"),
                manifest.getJSONObject("disk").getString("sha256"), VmImageCompression.XZ)
            // A unique short ID keeps the serial address inside sockaddr_un on every run.
            val id = "p${UUID.randomUUID().toString().replace("-", "").take(8)}"
            val evidenceFile = File(context.filesDir, "vm-desktop-proof.json")
            val launches = JSONArray()
            val evidence = JSONObject().put("schema", 1).put("passed", false)
                .put("manifestSha256", expected).put("imageId", image.imageId)
                .put("browserVersion", manifest.getString("browserVersion"))
                .put("instanceId", id).put("launches", launches)
                .put("guestFilePersistenceProved", false).put("browserRuntimeProved", false)
                .put("glesPresentationProved", false)
            val bundledImage = requireNotNull(VmImageCatalogue.current(context)) { "Release APK has no validated VM catalogue" }
            assertEquals(image.imageId, bundledImage.imageId)
            assertEquals(image.kernel, bundledImage.kernel.copy(source = image.kernel.source))
            assertEquals(image.initramfs, bundledImage.initramfs.copy(source = image.initramfs.source))
            assertEquals(image.download, bundledImage.download.copy(source = image.download.source))
            assertEquals(image.diskBytes, bundledImage.diskBytes)
            assertEquals(image.diskSha256, bundledImage.diskSha256)
            evidence.put("bundledCatalogueVerified", true)
            fun save() { evidenceFile.writeText(evidence.toString(2) + "\n") }
            save()
            val manager = VmSessionManager(context)
            var previousPid: Int? = null
            var previousGeneration: String? = null
            try {
                val installer = VmImageInstaller(context.filesDir)
                val installed = installer.install(id, image)
                val diskInode = Os.stat(installed.diskFile.path).st_ino
                evidence.put("installedDiskBytes", installed.diskBytes).put("installedDiskInode", diskInode)
                save()
                // Reject in the native service after parent validation succeeds.
                // This real cross-process ownership lock must then release the
                // failed session so the same manager can launch normally.
                val rejectedToken = UUID.randomUUID().toString().replace("-", "")
                val rejectedRequest = VmDesktopBootRequest(rejectedToken, id,
                    installed.kernelFile.path, installed.kernelSha256,
                    installed.initramfsFile.path, installed.initramfsSha256,
                    installed.diskFile.path, installed.diskBytes,
                    File(installed.diskFile.parentFile, "s${rejectedToken.take(8)}").path, 1024, 2)
                val ownershipFile = File(installed.diskFile.parentFile, "engine.lock")
                FileOutputStream(Os.open(ownershipFile.path,
                    OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)).use { owner ->
                    owner.channel.lock().use {
                        assertFalse("Service accepted a disk owned by another process", manager.launch(rejectedRequest, 1280, 720, 30))
                        assertFalse("Rejected startup permanently retained an active session", manager.isActive())
                        assertEquals(ContainerState.STOPPED, manager.state.value[id])
                        assertNull(manager.getEndpoint(id))
                        assertEquals(diskInode, Os.stat(installed.diskFile.path).st_ino)
                    }
                }
                evidence.put("rejectedLaunchRecovered", true); save()
                repeat(2) { index ->
                    val launchEvidence = JSONObject().put("index", index).put("stopped", false)
                    launches.put(launchEvidence); save()
                    val token = UUID.randomUUID().toString().replace("-", "")
                    val request = VmDesktopBootRequest(token, id, installed.kernelFile.path, installed.kernelSha256,
                        installed.initramfsFile.path, installed.initramfsSha256, installed.diskFile.path,
                        installed.diskBytes, File(installed.diskFile.parentFile, "s${token.take(8)}").path, 1024, 2)
                    assertTrue("Production Linux desktop launch failed", manager.launch(request, 1280, 720, 30))
                    assertEquals(ContainerState.RUNNING, manager.state.value[id])
                    val endpoint = requireNotNull(manager.getEndpoint(id))
                    assertEquals(DisplayBackend.VM_RFB, endpoint.backend)
                    val generation = requireNotNull(endpoint.sessionId)
                    val pid = requireNotNull(manager.getProcessId(id))
                    assertNotEquals("VM must live in its private Android process", Process.myPid(), pid)
                    previousPid?.let { assertNotEquals("Restart reused the previous VM process", it, pid) }
                    previousGeneration?.let { assertNotEquals("Restart reused private console generation", it, generation) }
                    previousPid = pid; previousGeneration = generation
                    launchEvidence.put("pid", pid).put("generation", generation)
                        .put("memoryMiB", 1024).put("vcpuCount", 2).put("targetFps", 30)
                        .put("transport", "private-unix-rfb-vncauth")
                    val observations = JSONArray()
                    launchEvidence.put("hostObservations", observations)
                    suspend fun observe() {
                        val host = requireNotNull(manager.observeHost(id))
                        assertEquals(pid, host.pid)
                        assertTrue("Incomplete host child observation: ${host.processes}", host.processes.complete)
                        assertEquals("Linux guest created a host child process", 0, host.processes.childCount)
                        observations.put(JSONObject().put("pid", host.pid).put("complete", true)
                            .put("hostChildren", host.processes.childCount).put("hostThreads", host.processes.threadCount))
                    }
                    observe()
                    val frameReady = CountDownLatch(1)
                    val changed = CountDownLatch(1)
                    val frames = AtomicInteger()
                    val signature = AtomicReference<Int?>()
                    val mutationArmed = AtomicBoolean(false)
                    val mutationBaseline = AtomicReference<Int?>()
                    val failure = AtomicReference<Throwable?>()
                    val executor = Executors.newSingleThreadExecutor()
                    lateinit var client: RfbClient
                    client = RfbClient(PrivateUnixRfbTransport(context.filesDir, generation), endpoint.password,
                        onFrame = { width, height, pixels ->
                            try {
                                assertEquals(1280, width); assertEquals(720, height)
                                assertEquals(width * height, pixels.size)
                                frames.incrementAndGet()
                                // A cursor on a black framebuffer is not a ready
                                // desktop. Wait for actual XFCE content before
                                // saving the frame or starting proof applications.
                                if (pixels.count { it and 0x00ffffff != 0 } > pixels.size / 50) {
                                    val current = pixels.contentHashCode()
                                    val first = signature.get()
                                    if (first == null && signature.compareAndSet(null, current)) {
                                        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                                        try {
                                            File(context.filesDir, "desktop-proof-$index.png").outputStream().use {
                                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                                            }
                                        } finally { bitmap.recycle() }
                                        frameReady.countDown()
                                    }
                                    if (mutationArmed.get() && mutationBaseline.get() != current) changed.countDown()
                                    signature.set(current)
                                }
                            } catch (error: Throwable) { failure.compareAndSet(null, error); frameReady.countDown(); changed.countDown() }
                            finally { client.recycleFrame(pixels) }
                        }, onStatus = {}, targetFps = 30)
                    val reader = executor.submit {
                        try { client.run() } catch (error: Throwable) {
                            failure.compareAndSet(null, error); frameReady.countDown(); changed.countDown()
                        }
                    }
                    try {
                        assertTrue("Authenticated private RFB produced no nonuniform desktop frame", frameReady.await(90, TimeUnit.SECONDS))
                        failure.get()?.let { throw AssertionError("Private RFB frame failure", it) }
                        manager.proveGuestBrowser(id)
                        launchEvidence.put("nonRootHeadlessFirefox", true).put("defaultCaHttps", true)
                        observe()
                        // XFCE's application menu causes real framebuffer damage.
                        mutationBaseline.set(signature.get()); mutationArmed.set(true)
                        client.key(0xffe3, true); client.key(0xff1b, true)
                        client.key(0xff1b, false); client.key(0xffe3, false)
                        assertTrue("Real desktop frames never changed", changed.await(30, TimeUnit.SECONDS))
                        failure.get()?.let { throw AssertionError("Private RFB mutation failure", it) }
                        launchEvidence.put("width", 1280).put("height", 720).put("frames", frames.get())
                            .put("nonuniformFrame", true).put("frameMutation", true).put("desktopContentVisible", true)
                        observe()
                        assertTrue("QMP pause failed", manager.pause())
                        assertEquals(ContainerState.SUSPENDED, manager.state.value[id])
                        assertTrue("QMP resume failed", manager.resume())
                        assertEquals(ContainerState.RUNNING, manager.state.value[id])
                        launchEvidence.put("pauseResume", true)
                        observe()
                    } finally {
                        client.close()
                        try { reader.get(10, TimeUnit.SECONDS) }
                        finally { executor.shutdownNow() }
                    }
                    assertTrue("Android did not confirm VM process exit", manager.stop())
                    assertFalse(manager.isActive())
                    assertNull(manager.getEndpoint(id))
                    assertEquals(ContainerState.STOPPED, manager.state.value[id])
                    assertEquals("Stop replaced the instance disk", diskInode, Os.stat(installed.diskFile.path).st_ino)
                    assertEquals(installed.diskBytes, installed.diskFile.length())
                    assertEquals(installed, installer.readInstalled(id))
                    launchEvidence.put("hostObservations", observations).put("stopped", true)
                        .put("diskRetained", true)
                    save()
                }
                evidence.put("browserRuntimeProved", true).put("browserMode", "non-root-headless")
                evidence.put("passed", true); save()
            } catch (error: Throwable) {
                evidence.put("failure", error.javaClass.simpleName + ": " + error.message); save()
                throw error
            } finally {
                try { if (manager.isActive()) manager.stop() } finally { manager.close() }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
