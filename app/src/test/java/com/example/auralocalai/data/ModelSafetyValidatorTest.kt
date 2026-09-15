package com.example.auralocalai.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ModelSafetyValidatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testMagicHeaderStandardZip() {
        val file = tempFolder.newFile("standard.task")
        file.writeBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x03, 0x04, 0x00, 0x00, 0x00, 0x00))
        assertTrue(ModelSafetyValidator.isValidModelFile(file, minSizeBytes = 8L))
    }

    @Test
    fun testMagicHeaderOffsetZip() {
        val file = tempFolder.newFile("offset.task")
        file.writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x00, 'P'.code.toByte(), 'K'.code.toByte(), 0x03, 0x04))
        assertTrue(ModelSafetyValidator.isValidModelFile(file, minSizeBytes = 8L))
    }

    @Test
    fun testMagicHeaderDirectFlatbuffer() {
        val file = tempFolder.newFile("model.tflite")
        file.writeBytes(byteArrayOf('T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte(), 0x01, 0x02, 0x03, 0x04))
        assertTrue(ModelSafetyValidator.isValidModelFile(file, minSizeBytes = 8L))
    }

    @Test
    fun testMagicHeaderOffsetFlatbuffer() {
        val file = tempFolder.newFile("offset_model.tflite")
        file.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04, 'T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte()))
        assertTrue(ModelSafetyValidator.isValidModelFile(file, minSizeBytes = 8L))
    }

    @Test
    fun testMagicHeaderLitertlm() {
        val file = tempFolder.newFile("bundle.litertlm")
        file.writeBytes("LITERTLM_BUNDLE_HEADER".toByteArray(Charsets.US_ASCII))
        assertTrue(ModelSafetyValidator.isValidModelFile(file, minSizeBytes = 8L))
    }

    @Test
    fun testRejectionOnInvalidHeader() {
        val file = tempFolder.newFile("garbage.bin")
        file.writeBytes("INVALID_HEADER_BYTES".toByteArray())
        assertFalse(ModelSafetyValidator.isValidModelFile(file, minSizeBytes = 8L))
    }

    @Test
    fun testRejectionOnSubThresholdSize() {
        val file = tempFolder.newFile("tiny.task")
        file.writeBytes("LITERTLM".toByteArray())
        // Enforcing 50 MB threshold should reject tiny file
        assertFalse(ModelSafetyValidator.isValidModelFile(file, minSizeBytes = 50L * 1024 * 1024))
    }

    @Test
    fun testSha256ChecksumCalculation() {
        val file = tempFolder.newFile("checksum_test.dat")
        val sampleData = "AuraLocalAI SHA-256 Validation Test String".toByteArray(Charsets.UTF_8)
        file.writeBytes(sampleData)

        val computed = ModelSafetyValidator.calculateSha256(file)

        val md = MessageDigest.getInstance("SHA-256")
        val expected = md.digest(sampleData).joinToString("") { "%02x".format(it) }

        assertEquals(expected, computed)
    }

    @Test
    fun testVerifyModelIntegrityFullPass() {
        val file = tempFolder.newFile("full_model.litertlm")
        val content = "LITERTLM_EXTRA_DATA_PADDING".toByteArray()
        file.writeBytes(content)

        val md = MessageDigest.getInstance("SHA-256")
        val expectedSha = md.digest(content).joinToString("") { "%02x".format(it) }

        val resultSuccess = ModelSafetyValidator.verifyModelIntegrity(file, expectedSha256 = expectedSha, minSizeBytes = 8L)
        assertTrue("Integrity check should succeed", resultSuccess is IntegrityResult.Success)

        val resultMismatch = ModelSafetyValidator.verifyModelIntegrity(file, expectedSha256 = "deadbeef12345678", minSizeBytes = 8L)
        assertTrue("Integrity check should report mismatch", resultMismatch is IntegrityResult.ChecksumMismatch)
    }

    @Test
    fun testMoveFileSafelyFallback() {
        val sourceFile = tempFolder.newFile("source.tmp")
        sourceFile.writeText("model weights stream copy test")
        val destFile = File(tempFolder.root, "final_model.bin")

        val moved = ModelSafetyValidator.moveFileSafely(sourceFile, destFile)
        assertTrue("moveFileSafely should succeed", moved)
        assertTrue("Destination file must exist", destFile.exists())
        assertEquals("model weights stream copy test", destFile.readText())
        assertFalse("Source file must be cleaned up", sourceFile.exists())
    }

    @Test
    fun testSweepCorruptedOrIncompleteArtifacts() = runTest {
        val storageDir = tempFolder.newFolder("model_storage")

        val tmp1 = File(storageDir, "partial1.tmp").apply { writeText("in progress") }
        val tmp2 = File(storageDir, "partial2.part").apply { writeText("in progress") }
        val emptyModel = File(storageDir, "bad.task").apply { writeText("") }
        val htmlError = File(storageDir, "error.litertlm").apply { writeText("<!DOCTYPE html><html>404 Not Found</html>") }
        val goodModel = File(storageDir, "good.litertlm").apply { writeBytes("LITERTLM_VALID_FILE_PAYLOAD".toByteArray()) }

        val swept = ModelSafetyValidator.sweepCorruptedOrIncompleteArtifacts(storageDir)

        assertEquals("Should have swept 4 invalid/temporary files", 4, swept)
        assertFalse(tmp1.exists())
        assertFalse(tmp2.exists())
        assertFalse(emptyModel.exists())
        assertFalse(htmlError.exists())
        assertTrue("Good model should remain intact", goodModel.exists())
    }
    @Test
    fun testDynamicRamFloorsPerPresetAndContextSlider() {
        val preset4Gb = ModelPreset(
            id = "test-4gb",
            name = "Test 4GB Model",
            description = "",
            sizeLabel = "1.5 GB",
            ramRequirement = "4 GB+ RAM",
            downloadUrl = "",
            fileName = "test.litertlm"
        )
        val preset6Gb = ModelPreset(
            id = "test-6gb",
            name = "Test 6GB Model",
            description = "",
            sizeLabel = "1.8 GB",
            ramRequirement = "6 GB+ RAM",
            downloadUrl = "",
            fileName = "test.litertlm"
        )
        val preset8Gb = ModelPreset(
            id = "test-8gb",
            name = "Test 8GB Model",
            description = "",
            sizeLabel = "3.4 GB",
            ramRequirement = "8 GB+ RAM",
            downloadUrl = "",
            fileName = "test.litertlm"
        )

        // 1. Exact byte assertions for Hardware Tier Guards (totalMem):
        // (X GiB * 1024^3) - (800 MiB * 1024^2)
        val expectedTier4Gb = (4L * 1024L * 1024L * 1024L) - (800L * 1024L * 1024L) // 3,456,106,496 bytes (~3.22 GiB)
        val expectedTier6Gb = (6L * 1024L * 1024L * 1024L) - (800L * 1024L * 1024L) // 5,603,590,144 bytes (~5.22 GiB)
        val expectedTier8Gb = (8L * 1024L * 1024L * 1024L) - (800L * 1024L * 1024L) // 7,751,073,792 bytes (~7.22 GiB)

        assertEquals("Exact byte tier threshold for 4GB model", expectedTier4Gb, ModelSafetyValidator.getMinRequiredRamBytes(preset4Gb))
        assertEquals("Exact byte tier threshold for 6GB model", expectedTier6Gb, ModelSafetyValidator.getMinRequiredRamBytes(preset6Gb))
        assertEquals("Exact byte tier threshold for 8GB model", expectedTier8Gb, ModelSafetyValidator.getMinRequiredRamBytes(preset8Gb))
        assertEquals("Default threshold should equal 6GB model", expectedTier6Gb, ModelSafetyValidator.getMinRequiredRamBytes(null))

        // 2. Context slider scaling assertions (4 turns vs 6 turns vs 12 turns):
        val availAt4Turns = ModelSafetyValidator.getMinRequiredAvailableRamBytes(preset6Gb, isGpu = false, contextTurns = 4)
        val availAt6Turns = ModelSafetyValidator.getMinRequiredAvailableRamBytes(preset6Gb, isGpu = false, contextTurns = 6)
        val availAt12Turns = ModelSafetyValidator.getMinRequiredAvailableRamBytes(preset6Gb, isGpu = false, contextTurns = 12)

        // Verifies that reducing context window scales down required RAM footprint
        assertTrue("4 turns must require strictly less RAM than 6 turns", availAt4Turns < availAt6Turns)
        assertTrue("6 turns must require strictly less RAM than 12 turns", availAt6Turns < availAt12Turns)

        // Formula verification at 6 turns:
        val size1_8Gb = (1.8 * 1024.0 * 1024.0 * 1024.0).toLong()
        val expectedKv6Turns = 6L * 75L * 1024L * 1024L + 350L * 1024L * 1024L // 800 MiB
        assertEquals(size1_8Gb + expectedKv6Turns, availAt6Turns)

        // 3. GPU unified staging buffer overhead delta (780 MiB):
        val availGpuAt6Turns = ModelSafetyValidator.getMinRequiredAvailableRamBytes(preset6Gb, isGpu = true, contextTurns = 6)
        assertEquals("GPU overhead must be exactly 780 MiB", 780L * 1024L * 1024L, availGpuAt6Turns - availAt6Turns)

        // 4. Multimodal headroom constant check:
        assertEquals(500L * 1024L * 1024L, ModelSafetyValidator.MULTIMODAL_VISION_OVERHEAD_BYTES)
    }
}
