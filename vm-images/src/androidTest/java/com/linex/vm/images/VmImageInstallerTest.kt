package com.linex.vm.images

import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.LocalServerSocket
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPOutputStream

@RunWith(AndroidJUnit4::class)
class VmImageInstallerTest {
    private fun freshRoot(): File {
        val files = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        return File(files, "it${UUID.randomUUID().toString().take(8)}").apply { check(mkdir()); Os.chmod(path, 448) }
    }
    private fun hash(bytes: ByteArray) = digestHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    @Test fun engineOwnershipDescriptorIsReallyCloseOnExecAccordingToTheKernel() = runBlocking {
        val root = freshRoot()
        val files = PrivateImageFiles(root)
        val instances = files.directory(files.root(), "vm-instances")
        val instance = files.directory(instances, "source")
        val namedLock = File(instance, "engine.lock").canonicalFile
        files.engineOwned(instance) {
            val entries = File("/proc/self/fd").listFiles()
                ?: throw AssertionError("Kernel descriptor list is not readable; CLOEXEC is unproved")
            val owned = entries.filter { entry ->
                try { File(Os.readlink(entry.path)).canonicalFile == namedLock }
                catch (error: ErrnoException) {
                    if (error.errno != OsConstants.ENOENT) throw error
                    false // Another unrelated process descriptor closed during enumeration.
                }
            }
            assertEquals("Expected one actual engine ownership descriptor", 1, owned.size)
            val info = File("/proc/self/fdinfo/${owned.single().name}").readText()
            val rows = info.lineSequence().filter { it.startsWith("flags:") }.toList()
            assertEquals("Kernel did not expose descriptor flags", 1, rows.size)
            val flags = rows.single().substringAfter(':').trim().toLong(radix = 8)
            // Independent kernel observation of Linux's documented O_CLOEXEC bit.
            assertTrue("Open engine lock would survive an exec", flags and 0x80000L != 0L)
        }
    }
    private fun candidate(root: File): PinnedVmImage {
        val fixtures = File(root, "vm-image-fixtures").apply { check(mkdir()); Os.chmod(path, 448) }
        val raw = ByteArray(100_000).apply { this[0] = 42 }
        val compressed = ByteArrayOutputStream().also { target -> GZIPOutputStream(target).use { it.write(raw) } }.toByteArray()
        fun asset(name: String, data: ByteArray): VmImageAsset {
            File(fixtures, name).apply { writeBytes(data); Os.chmod(path, 384) }
            return VmImageAsset(name, hash(data), data.size.toLong(), VmAssetSource.PrivateFile("vm-image-fixtures/$name"))
        }
        return PinnedVmImage("android-install-fixture", "1", asset("kernel", byteArrayOf(1, 2, 3)),
            asset("initramfs", byteArrayOf(4, 5, 6)), asset("factory.gz", compressed), raw.size.toLong(), hash(raw), VmImageCompression.GZIP)
    }
    @Test fun actualPrivateInstallPreservesMutableDiskAndDoesNotRedownloadReadyInstance() = runBlocking {
        val root = freshRoot()
        val image = candidate(root)
        val installer = VmImageInstaller(root)
        val stages = mutableListOf<VmInstallStage>()
        val installed = installer.install("instance", image) { stages += it.stage }
        assertEquals(File(root, "vm-images/${image.imageId}/${image.revision}/kernel"), installed.kernelFile)
        assertEquals(File(root, "vm-images/${image.imageId}/${image.revision}/initramfs"), installed.initramfsFile)
        assertEquals(File(root, "vm-instances/instance/disk.raw"), installed.diskFile)
        assertEquals(100_000, installed.diskFile.length())
        assertEquals(384, Os.lstat(installed.diskFile.path).st_mode and 511)
        assertEquals(448, Os.lstat(installed.diskFile.parentFile!!.path).st_mode and 511)
        assertTrue(stages.contains(VmInstallStage.EXPAND_DISK))
        assertEquals(VmInstallStage.READY, stages.last())
        installed.diskFile.writeBytes(ByteArray(100_000).apply { this[0] = 77 })
        assertNotNull(installer.readInstalled("instance"))
        val resumedStages = mutableListOf<VmInstallStage>()
        installer.install("instance", image) { resumedStages += it.stage }
        assertEquals(listOf(VmInstallStage.READY), resumedStages)
        assertEquals(77, installed.diskFile.inputStream().use { it.read() })
        val another = installer.install("second", image)
        assertEquals(installed.kernelFile, another.kernelFile)
        assertEquals(installed.initramfsFile, another.initramfsFile)
        assertNotEquals(installed.diskFile, another.diskFile)
        assertEquals(42, another.diskFile.inputStream().use { it.read() })
        var rejected = false
        try { installer.install("instance", image.copy(revision = "different")) } catch (_: java.io.IOException) { rejected = true }
        assertTrue(rejected)
        assertEquals(77, installed.diskFile.inputStream().use { it.read() })
    }
    @Test fun damagedSharedBootBytesAndHardLinkedDisksAreRejectedWithoutReplacement() = runBlocking {
        val root = freshRoot()
        val image = candidate(root)
        val installer = VmImageInstaller(root)
        val installed = installer.install("original", image)
        installed.kernelFile.writeBytes(byteArrayOf(7, 8, 9))
        var rejected = false
        try { installer.install("second", image) } catch (_: java.io.IOException) { rejected = true }
        assertTrue(rejected)
        assertNull(installer.readInstalled("second"))
        assertArrayEquals(byteArrayOf(7, 8, 9), installed.kernelFile.readBytes())
        assertEquals(42, installed.diskFile.inputStream().use { it.read() })
        installed.kernelFile.writeBytes(byteArrayOf(1, 2, 3))
        Os.link(installed.diskFile.path, File(installed.diskFile.parentFile, "disk-alias").path)
        rejected = false
        try { installer.readInstalled("original") } catch (_: java.io.IOException) { rejected = true }
        assertTrue(rejected)
        assertEquals(42, installed.diskFile.inputStream().use { it.read() })
    }
    @Test fun cancellationAndSymlinkSourcesNeverPublishReadyOrOverwriteAnExistingDisk() = runBlocking {
        val root = freshRoot()
        val image = candidate(root)
        val installer = VmImageInstaller(root)
        val worker = launch {
            installer.install("cancelled", image) { if (it.stage == VmInstallStage.EXPAND_DISK) cancel() }
        }
        worker.join()
        assertTrue(worker.isCancelled)
        assertNull(installer.readInstalled("cancelled"))
        val source = File(root, "vm-image-fixtures/kernel")
        check(source.delete())
        Os.symlink(File(root, "vm-image-fixtures/initramfs").path, source.path)
        // New pins/cache identity ensures this operation actually opens the malicious fixture source.
        val changed = image.copy(kernel = image.kernel.copy(sha256 = hash(byteArrayOf(9, 9, 9))))
        var rejected = false
        try { installer.install("symlink", changed) } catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        assertNull(installer.readInstalled("symlink"))
    }
    @Test fun cloneSnapshotsTheModifiedGuestDiskAndKeepsAnIndependentIdentity() = runBlocking {
        val root = freshRoot()
        val installer = VmImageInstaller(root)
        val original = installer.install("source", candidate(root))
        val edited = ByteArray(100_000).apply { this[0] = 77; this[99_999] = 31 }
        original.diskFile.writeBytes(edited)
        val stages = mutableListOf<VmInstallStage>()
        val copy = installer.clone("source", "copy") { stages += it.stage }
        assertEquals("copy", copy.instanceId)
        assertEquals(original.kernelFile, copy.kernelFile)
        assertArrayEquals(edited, copy.diskFile.readBytes())
        assertTrue(stages.contains(VmInstallStage.CLONE_DISK))
        assertEquals(VmInstallStage.READY, stages.last())
        assertEquals("copy", installer.readInstalled("copy")!!.instanceId)
        copy.diskFile.writeBytes(ByteArray(100_000))
        assertArrayEquals(edited, original.diskFile.readBytes())
        var rejected = false
        try { installer.clone("source", "copy") } catch (_: java.io.IOException) { rejected = true }
        assertTrue(rejected)
        assertEquals(0, copy.diskFile.inputStream().use { it.read() })
        installer.delete("copy")
        assertNull(installer.readInstalled("copy"))
        assertTrue(original.kernelFile.isFile)
        assertTrue(original.diskFile.isFile)
    }
    @Test fun engineOwnershipRefusesCloneAndDeleteEvenWhenTheCallerThinksTheVmStopped() = runBlocking {
        val root = freshRoot()
        val installer = VmImageInstaller(root)
        val original = installer.install("source", candidate(root))
        val lock = File(original.diskFile.parentFile, "engine.lock")
        FileOutputStream(Os.open(lock.path, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_NOFOLLOW, 384)).use { stream ->
            stream.channel.lock().use {
                for (operation in listOf<suspend () -> Unit>({ installer.clone("source", "copy"); Unit }, { installer.delete("source") })) {
                    var rejected = false
                    try { operation() } catch (_: java.io.IOException) { rejected = true }
                    assertTrue(rejected)
                }
                assertNull(installer.readInstalled("copy"))
                assertEquals(42, original.diskFile.inputStream().use { it.read() })
            }
        }
        installer.delete("source")
        assertNull(installer.readInstalled("source"))
        assertTrue(original.kernelFile.isFile)
    }
    @Test fun deletionPreservesUnexpectedDataAndAnActiveSerialListenerButAllowsAnOwnedStaleSocket() = runBlocking {
        val root = freshRoot()
        val installer = VmImageInstaller(root)
        val original = installer.install("source", candidate(root))
        val unknown = File(original.diskFile.parentFile, "user-file").apply { writeText("preserve"); Os.chmod(path, 384) }
        var rejected = false
        try { installer.delete("source") } catch (_: java.io.IOException) { rejected = true }
        assertTrue(rejected)
        assertEquals("preserve", unknown.readText())
        check(unknown.delete())
        val endpoint = File(original.diskFile.parentFile, "s1234abcd")
        LocalSocket().use { socket ->
            socket.bind(LocalSocketAddress(endpoint.path, LocalSocketAddress.Namespace.FILESYSTEM))
            Os.chmod(endpoint.path, 384)
            LocalServerSocket(socket.fileDescriptor).use {
                rejected = false
                try { installer.delete("source") } catch (_: java.io.IOException) { rejected = true }
                assertTrue(rejected)
                assertTrue(original.diskFile.isFile)
            }
        }
        assertTrue(endpoint.exists())
        installer.delete("source")
        assertNull(installer.readInstalled("source"))
        assertFalse(endpoint.exists())
        assertTrue(original.kernelFile.isFile)
    }
}
