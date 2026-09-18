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
    val backendRestriction: LlmBackendRestriction = LlmBackendRestriction.ANY,
    val quantization: String = "INT4",
    val parameterCount: String = "Unknown",
    val contextLength: String = "4,096 tokens",
    val isReasoningModel: Boolean = false,
    val expectedSha256: String? = null
) {
    companion object {
        fun isReasoningModel(modelId: String?): Boolean {
            if (modelId == null) return false
            val preset = presets.find { it.id.equals(modelId, ignoreCase = true) }
            if (preset != null) return preset.isReasoningModel
            return modelId.contains("deepseek", ignoreCase = true) || modelId.contains("qwq", ignoreCase = true)
        }

        fun isKnownNonReasoningModel(modelId: String?): Boolean {
            if (modelId == null) return false
            val preset = presets.find { it.id.equals(modelId, ignoreCase = true) }
            return preset != null && !preset.isReasoningModel
        }

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
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "Q8 (8-bit)",
                parameterCount = "1.5B",
                contextLength = "4,096 tokens",
                isReasoningModel = true,
                expectedSha256 = "69b35f01759eed765641ab4af589bbe98131fd2825662a086d9037409b8c1295"
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
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "Q8 (8-bit)",
                parameterCount = "1.5B",
                contextLength = "4,096 tokens",
                expectedSha256 = "faa60663b333290c1496c499828b21d3e3254a788cacd8cce917ce0f761a2dc9"
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
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "mixed INT4",
                parameterCount = "4.0B",
                contextLength = "4,096 tokens",
                expectedSha256 = "f0794bc77efeaaf4f7af815f04c483b19b8f2ae4a102cef1b7b760a25848a18e"
            ),
            ModelPreset(
                id = "qwen2.5-coder-3b",
                name = "Qwen 2.5 Coder 3B Instruct",
                description = "Alibaba's fast and highly capable coding-specialized LLM with 3 billion parameters (Coding Expert).",
                sizeLabel = "2.9 GB",
                ramRequirement = "8 GB+ RAM",
                downloadUrl = "https://huggingface.co/4ntoine/Qwen2.5-Coder-3B-Instruct-LiteRTLM/resolve/main/model.litertlm",
                fileName = "qwen2.5-coder-3b.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "INT4",
                parameterCount = "3.0B",
                contextLength = "4,096 tokens",
                expectedSha256 = "d481c78671e2baf8af60762ad83c4c02610cf067f7c15615c36c82806f687886"
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
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "mixed 2/4/8-bit",
                parameterCount = "2.4B",
                contextLength = "8,192 tokens",
                expectedSha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
            ),
            ModelPreset(
                id = "gemma4-e4b",
                name = "Google Gemma 4 E4B Instruct (Multimodal)",
                description = "Google's powerful on-device LLM with 4B parameters. Superior reasoning, math, and coding over E2B with native multimodal vision support (High-Res Multimodal).",
                sizeLabel = "3.4 GB",
                ramRequirement = "12 GB+ RAM",
                downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
                fileName = "gemma4-e4b.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "mixed 2/4/8-bit",
                parameterCount = "4.0B",
                contextLength = "8,192 tokens",
                expectedSha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"
            )
        )
    }
}

fun isValidModelFile(file: File, minSizeBytes: Long = ModelSafetyValidator.DEFAULT_MIN_MODEL_SIZE_BYTES): Boolean = ModelSafetyValidator.isValidModelFile(file, minSizeBytes = minSizeBytes)
