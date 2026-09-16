package com.example.auralocalai.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

sealed interface IntegrityResult {
    data object Success : IntegrityResult
    data class ChecksumMismatch(val expected: String, val actual: String) : IntegrityResult
    data class StructuralFailure(val reason: String) : IntegrityResult
}

object ModelSafetyValidator {

    private const val TAG = "ModelSafetyValidator"
    const val DEFAULT_MIN_MODEL_SIZE_BYTES = 50L * 1024 * 1024 // 50 MB floor

    /**
     * Validates whether a file has a valid LLM container structure.
     * Inspects the initial header bytes for:
     * - Standard ZIP (PK\x03\x04)
     * - Offset ZIP (offset 4)
     * - Flatbuffer (TFL3 at offset 0 or 4)
     * - LITERTLM bundle identifier
     */
    fun isValidModelFile(file: File, minSizeBytes: Long = DEFAULT_MIN_MODEL_SIZE_BYTES): Boolean {
        if (!file.exists() || !file.isFile) return false
        if (file.length() < minSizeBytes) {
            Log.w(TAG, "File size ${file.length()} is below minimum threshold $minSizeBytes bytes: ${file.name}")
            return false
        }
        if (file.length() < 8L) return false

        return try {
            val bytes = ByteArray(16)
            FileInputStream(file).use { it.read(bytes) }

            // Standard ZIP archive (MediaPipe Task / LiteRT bundle)
            val isZip = bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
                        bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

            // Offset ZIP (prefixed by 4 zero bytes on some Hugging Face LFS models)
            val isOffsetZip = bytes[4] == 'P'.code.toByte() && bytes[5] == 'K'.code.toByte() &&
                              bytes[6] == 0x03.toByte() && bytes[7] == 0x04.toByte()

            // RAW TFLite Flatbuffer
            val isTfliteDirect = bytes[0] == 'T'.code.toByte() && bytes[1] == 'F'.code.toByte() &&
                                 bytes[2] == 'L'.code.toByte() && bytes[3] == '3'.code.toByte()

            // Offset RAW TFLite Flatbuffer (offset 4)
            val isTfliteOffset = bytes[4] == 'T'.code.toByte() && bytes[5] == 'F'.code.toByte() &&
                                 bytes[6] == 'L'.code.toByte() && bytes[7] == '3'.code.toByte()

            // LITERTLM Bundle format ('LITERTLM' at offset 0)
            val isLitertlm = bytes[0] == 'L'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                             bytes[2] == 'T'.code.toByte() && bytes[3] == 'E'.code.toByte() &&
                             bytes[4] == 'R'.code.toByte() && bytes[5] == 'T'.code.toByte() &&
                             bytes[6] == 'L'.code.toByte() && bytes[7] == 'M'.code.toByte()

            isZip || isOffsetZip || isTfliteDirect || isTfliteOffset || isLitertlm
        } catch (e: Exception) {
            Log.e(TAG, "Error checking model magic headers: ${e.message}", e)
            false
        }
    }

    /**
     * Computes the full-file SHA-256 digest in 64 KB sequential chunks.
     * Safe for HTTP Range resumed downloads because it digests the full assembled file on disk.
     */
    fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(65536)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies structural integrity and optional SHA-256 checksum.
     */
    fun verifyModelIntegrity(
        file: File,
        expectedSha256: String? = null,
        minSizeBytes: Long = DEFAULT_MIN_MODEL_SIZE_BYTES
    ): IntegrityResult {
        if (!isValidModelFile(file, minSizeBytes)) {
            return IntegrityResult.StructuralFailure("File is corrupted or lacks a valid Flatbuffer/ZIP/LiteRT header")
        }

        if (!expectedSha256.isNullOrBlank()) {
            val actualSha256 = calculateSha256(file)
            if (!actualSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
                return IntegrityResult.ChecksumMismatch(expectedSha256.trim(), actualSha256)
            }
        }

        return IntegrityResult.Success
    }

    const val RESERVED_RAM_ALLOWANCE_BYTES = 800L * 1024L * 1024L // 800 MiB allowance for kernel/modem/display hardware reservation

    /**
     * Android Vulkan/OpenCL rearranged buffer staging overhead on Unified Memory Architecture (UMA).
     * Calibration note: This 780 MiB baseline originates from LiteRT-LM issue #3507, measured specifically on an
     * E2B-sized model (~1.5 GB weights) at an 8192-token context window where rearranged weight tiles are duplicated
     * into GPU-accessible staging memory buffers on Android rather than remaining zero-copy memory-mapped.
     * Architectural scaling note: For larger models (>4B parameters), this buffer size may scale up with model
     * weight tile dimensions and attention head projections rather than remaining a universal 780 MiB constant.
     */
    const val GPU_BUFFER_OVERHEAD_BYTES = 780L * 1024L * 1024L

    /**
     * Additional memory overhead incurred when loading the vision encoder tower and processing
     * image patch embeddings on multimodal models (e.g. Gemma 4 E2B / E4B).
     */
    const val MULTIMODAL_VISION_OVERHEAD_BYTES = 500L * 1024L * 1024L // 500 MiB

    const val MIN_SCRATCH_BUFFER_BYTES = 512L * 1024L * 1024L // 512 MiB floor for KV cache + runtime activation buffers

    /**
     * Calculates the minimum required device physical RAM tier in bytes for a given model preset.
     * Uses binary gigabytes (1 GiB = 1024^3 bytes) and deducts a flat 800 MiB allowance (800 * 1024^2 bytes)
     * for kernel/modem/display hardware reservations.
     *
     * Exact byte calculations:
     * - 4 GB requirement: (4 * 1024^3) - (800 * 1024^2) = 4,294,967,296 - 838,860,800 = 3,456,106,496 bytes (~3.22 GiB)
     * - 6 GB requirement: (6 * 1024^3) - (800 * 1024^2) = 6,442,450,944 - 838,860,800 = 5,603,590,144 bytes (~5.22 GiB)
     * - 8 GB requirement: (8 * 1024^3) - (800 * 1024^2) = 8,589,934,592 - 838,860,800 = 7,751,073,792 bytes (~7.22 GiB)
     */
    fun getMinRequiredRamBytes(preset: ModelPreset? = null): Long {
        val reqGb = preset?.let {
            Regex("(\\d+)\\s*GB", RegexOption.IGNORE_CASE)
                .find(it.ramRequirement)
                ?.groupValues?.get(1)?.toLongOrNull()
        } ?: 6L // Default to 6 GB if preset is null or unparseable

        val rawBytes = reqGb * 1024L * 1024L * 1024L
        return maxOf(1024L * 1024L * 1024L, rawBytes - RESERVED_RAM_ALLOWANCE_BYTES)
    }

    /**
     * Estimates the raw model weight size on disk in bytes.
     * Uses the actual file length if provided, or parses sizeLabel / ramRequirement from preset.
     */
    fun estimateModelSizeBytes(modelFile: File? = null, preset: ModelPreset? = null): Long {
        if (modelFile != null && modelFile.exists() && modelFile.length() > 0) {
            return modelFile.length()
        }
        val labelGb = preset?.let {
            Regex("(\\d+(?:\\.\\d+)?)\\s*GB", RegexOption.IGNORE_CASE)
                .find(it.sizeLabel)
                ?.groupValues?.get(1)?.toDoubleOrNull()
        }
        if (labelGb != null && labelGb > 0) {
            return (labelGb * 1024.0 * 1024.0 * 1024.0).toLong()
        }
        val ramGb = preset?.let {
            Regex("(\\d+)\\s*GB", RegexOption.IGNORE_CASE)
                .find(it.ramRequirement)
                ?.groupValues?.get(1)?.toLongOrNull()
        } ?: 6L
        return when {
            ramGb <= 4L -> 1_500_000_000L // ~1.5 GB
            ramGb <= 6L -> 2_000_000_000L // ~2.0 GB
            else -> 3_400_000_000L        // ~3.4 GB
        }
    }

    /**
     * Calculates the minimum runtime available (free) RAM required immediately before loading weights.
     * Prevents native OOM kills from the kernel Low Memory Killer (LMK).
     *
     * Empirical formula grounded in on-device LiteRT-LM runtime memory benchmarks:
     * Required Free RAM = Resident Model Size + KV Cache & Activations Overhead + GPU Buffer Overhead
     *
     * - Resident Model Size: File weight size (decoder weights resident in RAM).
     * - KV Cache & Activations: Dynamically scaled with contextTurns (4..16 turns, default 6):
     *     Base scratch activation buffers: 350 MiB
     *     KV Cache: ~75 MiB per turn (at ~512 tokens/turn for 2B-4B models)
     *     Total KV/Activation = max(512 MiB, (contextTurns * 75 MiB) + 350 MiB)
     * - GPU Buffer Overhead: 780 MiB on Android Vulkan/GPU (LiteRT-LM #3507).
     */
    fun getMinRequiredAvailableRamBytes(
        preset: ModelPreset? = null,
        isGpu: Boolean = false,
        modelFile: File? = null,
        contextTurns: Int = 6
    ): Long {
        val modelSizeBytes = estimateModelSizeBytes(modelFile, preset)
        val clampedTurns = contextTurns.coerceIn(4, 16)
        val contextTokens = preset?.let {
            Regex("(\\d+(?:,\\d+)?)").find(it.contextLength)?.value?.replace(",", "")?.toIntOrNull()
        } ?: 4096
        val contextScaleFactor = (contextTokens.toDouble() / 4096.0).coerceIn(1.0, 4.0)
        val kvCacheOverhead = (clampedTurns * (75L * 1024L * 1024L) * contextScaleFactor).toLong()
        val scratchActivationOverhead = 350L * 1024L * 1024L
        val kvAndActivationOverhead = maxOf(MIN_SCRATCH_BUFFER_BYTES, kvCacheOverhead + scratchActivationOverhead)
        val gpuOverhead = if (isGpu) GPU_BUFFER_OVERHEAD_BYTES else 0L
        return modelSizeBytes + kvAndActivationOverhead + gpuOverhead
    }

    /**
     * Verifies that the device has sufficient available RAM to process an image through the vision encoder
     * on multimodal models without triggering an out-of-memory abort.
     */
    fun verifyAvailableRamForMultimodal(context: Context): Result<Unit> {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Result.success(Unit)
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)

        if (memInfo.lowMemory || memInfo.availMem < MULTIMODAL_VISION_OVERHEAD_BYTES) {
            val availMb = memInfo.availMem / (1024L * 1024L)
            val neededMb = MULTIMODAL_VISION_OVERHEAD_BYTES / (1024L * 1024L)
            return Result.failure(
                IllegalStateException(
                    "Low memory condition: Only ${availMb} MB free RAM available (system lowMemory=${memInfo.lowMemory}). Processing image attachment requires at least ${neededMb} MB free headroom for the vision encoder. Please close background apps or send text prompt."
                )
            )
        }
        return Result.success(Unit)
    }

    /**
     * Dual-phase RAM verification for on-device inference:
     * 1. Hardware Tier Guard (totalMem): Confirms device physical RAM capacity (for CPU execution).
     * 2. Runtime Memory Pressure Guard (availMem & lowMemory): Confirms sufficient free RAM right now to
     *    allocate weights, KV cache, and runtime buffers without triggering kernel LMK aborts.
     *    Evaluated for BOTH GPU (accounting for Vulkan unified buffer staging) and CPU.
     */
    fun verifyDeviceRamForInference(
        context: Context,
        preset: ModelPreset? = null,
        isGpu: Boolean = false,
        modelFile: File? = null,
        contextTurns: Int = 6
    ): Result<Unit> {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Result.success(Unit)
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)

        // Phase 1: Hardware Tier Guard (totalMem) - strictly enforced for CPU fallback
        if (!isGpu) {
            val minRequiredTierBytes = getMinRequiredRamBytes(preset)
            val requiredGbLabel = preset?.let {
                Regex("(\\d+)\\s*GB", RegexOption.IGNORE_CASE).find(it.ramRequirement)?.groupValues?.get(1)
            } ?: "6"

            if (memInfo.totalMem < minRequiredTierBytes) {
                val totalGb = "%.2f".format(memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0))
                return Result.failure(
                    IllegalStateException(
                        "Device has ${totalGb} GiB total RAM. Running ${preset?.name ?: "this model"} on CPU requires at least ${requiredGbLabel} GB device tier to prevent native OOM aborts."
                    )
                )
            }
        }

        // Phase 2: Runtime Memory Pressure Guard (availMem & lowMemory) - evaluated for BOTH GPU and CPU
        val minRequiredAvailBytes = getMinRequiredAvailableRamBytes(preset, isGpu, modelFile, contextTurns)
        if (memInfo.lowMemory || memInfo.availMem < minRequiredAvailBytes) {
            val availGb = "%.2f".format(memInfo.availMem.toDouble() / (1024.0 * 1024.0 * 1024.0))
            val neededGb = "%.2f".format(minRequiredAvailBytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
            val backendLabel = if (isGpu) "GPU" else "CPU"
            val gpuContextNote = if (isGpu) " (including GPU buffer staging)" else ""
            return Result.failure(
                IllegalStateException(
                    "Low memory condition: Only ${availGb} GiB free RAM available right now (system lowMemory=${memInfo.lowMemory}). Initializing ${preset?.name ?: "model"} on $backendLabel ($contextTurns-turn context) requires at least ${neededGb} GiB free memory (weights + KV cache + activations$gpuContextNote) to avoid being killed by Android. Please close background apps and try again."
                )
            )
        }

        return Result.success(Unit)
    }

    /**
     * Backwards-compatible overloads for CPU verification call sites.
     */
    fun verifyDeviceRamForCpu(context: Context, preset: ModelPreset? = null, contextTurns: Int = 6): Result<Unit> {
        return verifyDeviceRamForInference(context, preset, isGpu = false, contextTurns = contextTurns)
    }

    fun verifyDeviceRamForCpu(context: Context, preset: ModelPreset? = null): Result<Unit> {
        return verifyDeviceRamForInference(context, preset, isGpu = false)
    }


    /**
     * Detects Android emulator fingerprints and x86 ABI translation layers.
     */
    fun isEmulatorOrX86(): Boolean {
        val fingerprint = Build.FINGERPRINT ?: ""
        val model = Build.MODEL ?: ""
        val manufacturer = Build.MANUFACTURER ?: ""
        val hardware = Build.HARDWARE ?: ""
        val product = Build.PRODUCT ?: ""

        val isEmulator = fingerprint.startsWith("generic") ||
                fingerprint.startsWith("unknown") ||
                model.contains("google_sdk") ||
                model.contains("Emulator") ||
                model.contains("Android SDK built for x86") ||
                manufacturer.contains("Genymotion") ||
                hardware.contains("goldfish") ||
                hardware.contains("ranchu") ||
                product.contains("sdk_google") ||
                product.contains("google_sdk") ||
                product.contains("sdk") ||
                product.contains("sdk_x86") ||
                product.contains("vbox86p") ||
                product.contains("emulator") ||
                product.contains("simulator")

        val isX86 = Build.SUPPORTED_ABIS.any { it.contains("x86") }
        return isEmulator || isX86
    }

    /**
     * Moves a completed file from temp location to final destination.
     * First attempts atomic renameTo; if false (e.g. cross-mount points between cache and external storage),
     * seamlessly falls back to 64 KB buffered stream-copy followed by source deletion.
     */
    fun moveFileSafely(source: File, destination: File): Boolean {
        if (!source.exists()) return false
        destination.parentFile?.mkdirs()

        // Delete destination if it already exists to prevent rename collisions
        if (destination.exists()) {
            destination.delete()
        }

        // 1. Try atomic rename
        if (source.renameTo(destination)) {
            return true
        }

        // 2. Cross-filesystem fallback: stream copy + delete source
        Log.w(TAG, "renameTo returned false for ${source.name} -> ${destination.name}. Falling back to stream-copy.")
        return try {
            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }
            val deleted = source.delete()
            if (!deleted) {
                Log.w(TAG, "Temporary file ${source.name} could not be deleted immediately after copy.")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Stream-copy move fallback failed: ${e.message}", e)
            if (destination.exists()) destination.delete()
            false
        }
    }

    /**
     * Purges orphaned .tmp files and corrupted artifacts.
     * Explicitly executed on Dispatchers.IO to prevent cold-start main-thread ANRs.
     */
    suspend fun sweepCorruptedOrIncompleteArtifacts(storageDir: File): Int = withContext(Dispatchers.IO) {
        var sweptCount = 0
        try {
            if (!storageDir.exists() || !storageDir.isDirectory) return@withContext 0
            val files = storageDir.listFiles() ?: return@withContext 0

            for (file in files) {
                if (!file.isFile) continue
                val name = file.name.lowercase()

                // Purge temporary downloads
                if (name.endsWith(".tmp") || name.endsWith(".part")) {
                    if (file.delete()) {
                        sweptCount++
                        Log.i(TAG, "Swept orphaned temporary download: ${file.name}")
                    }
                    continue
                }

                // Purge zero-byte or corrupted error HTML pages disguised as models
                if (file.length() < 1024L && (name.endsWith(".task") || name.endsWith(".litertlm") || name.endsWith(".bin"))) {
                    val content = try { file.readText().take(200) } catch (_: Exception) { "" }
                    if (file.length() == 0L || content.contains("<!DOCTYPE", ignoreCase = true) || content.contains("<html", ignoreCase = true)) {
                        if (file.delete()) {
                            sweptCount++
                            Log.i(TAG, "Swept empty/HTML error artifact: ${file.name}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sweeping storage artifacts: ${e.message}", e)
        }
        sweptCount
    }
}
