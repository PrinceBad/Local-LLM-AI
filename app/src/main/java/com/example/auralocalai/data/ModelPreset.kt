package com.example.auralocalai.data

import java.io.File
import java.util.zip.ZipFile

enum class LlmBackendRestriction {
    ANY,
    CPU_ONLY,
    GPU_ONLY,
    NPU_ONLY
}

data class ModelPreset(
    val id: String,
    val name: String,
    val description: String,
    val sizeLabel: String,
    val ramRequirement: String,
    val downloadUrl: String,
    val fileName: String,
    val requiresHfToken: Boolean = false,
    val expectedExtension: String = ".litertlm",
    val backendRestriction: LlmBackendRestriction = LlmBackendRestriction.ANY
) {
    companion object {
        val presets = listOf(
            ModelPreset(
                id = "deepseek-1.5b",
                name = "DeepSeek-R1 Distill Qwen 1.5B",
                description = "DeepSeek's powerful reasoning model distilled into Qwen architecture, outputting detailed chain-of-thought logic (Offline Reasoning).",
                sizeLabel = "2.0 GB",
                ramRequirement = "6 GB+ RAM",
                downloadUrl = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
                fileName = "deepseek-r1.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY
            ),
            ModelPreset(
                id = "qwen-1.5b",
                name = "Qwen 2.5 1.5B Instruct",
                description = "Alibaba's state-of-the-art multilingual LLM. Outperforms models of similar size in math, coding, and general knowledge (General Knowledge).",
                sizeLabel = "1.8 GB",
                ramRequirement = "6 GB+ RAM",
                downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
                fileName = "qwen-1.5b.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY
            ),
            ModelPreset(
                id = "qwen3-4b",
                name = "Qwen 3 4B",
                description = "Alibaba's latest powerful Qwen 3 architecture with 4 billion parameters (High Performance).",
                sizeLabel = "2.5 GB",
                ramRequirement = "8 GB+ RAM",
                downloadUrl = "https://huggingface.co/litert-community/Qwen3-4B/resolve/main/qwen3_4b_mixed_int4.litertlm",
                fileName = "qwen3-4b.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY
            ),
            ModelPreset(
                id = "qwen2.5-coder-3b",
                name = "Qwen 2.5 Coder 3B Instruct",
                description = "Alibaba's fast and highly capable coding-specialized LLM with 3 billion parameters (Coding Expert).",
                sizeLabel = "2.9 GB",
                ramRequirement = "6 GB+ RAM",
                downloadUrl = "https://huggingface.co/4ntoine/Qwen2.5-Coder-3B-Instruct-LiteRTLM/resolve/main/model.litertlm",
                fileName = "qwen2.5-coder-3b.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY
            ),
            ModelPreset(
                id = "gemma4-e2b",
                name = "Google Gemma 4 E2B Instruct (Multimodal)",
                description = "Google's next-gen multimodal mobile LLM. Features advanced chain-of-thought logic, high-quality responses, and native multimodal support (Multimodal Vision).",
                sizeLabel = "2.4 GB",
                ramRequirement = "6 GB+ RAM",
                downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                fileName = "gemma4-e2b.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY
            ),
            ModelPreset(
                id = "gemma4-e4b",
                name = "Google Gemma 4 E4B Instruct (Multimodal)",
                description = "Google's powerful on-device LLM with 4B parameters. Superior reasoning, math, and coding over E2B with native multimodal vision support (High-Res Multimodal).",
                sizeLabel = "3.4 GB",
                ramRequirement = "8 GB+ RAM",
                downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
                fileName = "gemma4-e4b.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY
            )
        )
    }
}

fun isValidModelFile(file: File): Boolean {
    if (!file.exists() || file.length() < 8L) return false
    return try {
        val bytes = ByteArray(8)
        java.io.FileInputStream(file).use { it.read(bytes) }

        // Standard ZIP archive (MediaPipe Task)
        val isZip = bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
                    bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
        
        // Offset ZIP (Some HuggingFace hosted .task files prepend 4 zero bytes)
        val isOffsetZip = bytes[4] == 'P'.code.toByte() && bytes[5] == 'K'.code.toByte() &&
                          bytes[6] == 0x03.toByte() && bytes[7] == 0x04.toByte()

        // RAW TFLite Flatbuffer
        val isTfliteDirect = bytes[0] == 'T'.code.toByte() && bytes[1] == 'F'.code.toByte() &&
                             bytes[2] == 'L'.code.toByte() && bytes[3] == '3'.code.toByte()

        // Offset RAW TFLite Flatbuffer
        val isTfliteOffset = bytes[4] == 'T'.code.toByte() && bytes[5] == 'F'.code.toByte() &&
                             bytes[6] == 'L'.code.toByte() && bytes[7] == '3'.code.toByte()

        // LITERTLM Bundle format ('LITERTLM' at offset 0)
        val isLitertlm = bytes[0] == 'L'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                         bytes[2] == 'T'.code.toByte() && bytes[3] == 'E'.code.toByte() &&
                         bytes[4] == 'R'.code.toByte() && bytes[5] == 'T'.code.toByte() &&
                         bytes[6] == 'L'.code.toByte() && bytes[7] == 'M'.code.toByte()

        isZip || isOffsetZip || isTfliteDirect || isTfliteOffset || isLitertlm
    } catch (e: Exception) {
        false
    }
}
