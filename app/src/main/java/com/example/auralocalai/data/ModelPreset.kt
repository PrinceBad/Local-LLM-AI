package com.example.auralocalai.data

import androidx.compose.runtime.Immutable
import java.io.File
import java.util.Locale

enum class LlmBackendRestriction {
    ANY,
    CPU_ONLY,
    GPU_ONLY,
    NPU_ONLY
}

enum class ModelSpeedTier {
    TURBO,
    BALANCED,
    POWER
}

enum class ModelTag {
    REASONING,
    VISION,
    UNCENSORED,
    CODING,
    TURBO,
    MULTILINGUAL
}

@Immutable
data class ModelPreset(
    val id: String,
    val name: String,
    val description: String,
    val sizeBytes: Long = 0L,
    val minRamGb: Int = 4,
    val downloadUrl: String,
    val fileName: String,
    val requiresHfToken: Boolean = false,
    val expectedExtension: String = ".litertlm",
    val backendRestriction: LlmBackendRestriction = LlmBackendRestriction.ANY,
    val quantization: String = "INT4",
    val parameterCount: String = "Unknown",
    val contextLength: String = "4,096 tokens",
    val isReasoningModel: Boolean = false,
    val expectedSha256: String? = null,
    val kvCacheTokens: Int = 0,
    val supportsVision: Boolean = true,
    val speedTier: ModelSpeedTier = ModelSpeedTier.BALANCED,
    val defaultThinkingEnabled: Boolean = false,
    val tags: Set<ModelTag> = emptySet(),
    val sizeLabelOverride: String? = null,
    val ramRequirementOverride: String? = null
) {
    val sizeLabel: String
        get() = sizeLabelOverride ?: formatDecimalBytes(sizeBytes)

    val ramRequirement: String
        get() = ramRequirementOverride ?: "$minRamGb GB+ RAM"

    constructor(
        id: String,
        name: String,
        description: String,
        sizeLabel: String,
        ramRequirement: String,
        downloadUrl: String,
        fileName: String,
        requiresHfToken: Boolean = false,
        expectedExtension: String = ".litertlm",
        backendRestriction: LlmBackendRestriction = LlmBackendRestriction.ANY,
        quantization: String = "INT4",
        parameterCount: String = "Unknown",
        contextLength: String = "4,096 tokens",
        isReasoningModel: Boolean = false,
        expectedSha256: String? = null,
        kvCacheTokens: Int = 0,
        supportsVision: Boolean = true,
        speedTier: ModelSpeedTier = ModelSpeedTier.BALANCED,
        defaultThinkingEnabled: Boolean = false,
        tags: Set<ModelTag> = emptySet()
    ) : this(
        id = id,
        name = name,
        description = description,
        sizeBytes = parseSizeBytesFromLabel(sizeLabel),
        minRamGb = parseRamGbFromLabel(ramRequirement),
        downloadUrl = downloadUrl,
        fileName = fileName,
        requiresHfToken = requiresHfToken,
        expectedExtension = expectedExtension,
        backendRestriction = backendRestriction,
        quantization = quantization,
        parameterCount = parameterCount,
        contextLength = contextLength,
        isReasoningModel = isReasoningModel,
        expectedSha256 = expectedSha256,
        kvCacheTokens = kvCacheTokens,
        supportsVision = supportsVision,
        speedTier = speedTier,
        defaultThinkingEnabled = defaultThinkingEnabled,
        tags = tags,
        sizeLabelOverride = sizeLabel,
        ramRequirementOverride = ramRequirement
    )

    val effectiveContextTokens: Int
        get() = when {
            kvCacheTokens > 0 -> kvCacheTokens
            else -> EKV_REGEX
                .find(downloadUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: DEFAULT_CONTEXT_TOKENS
        }

    companion object {
        private val EKV_REGEX = Regex("ekv(\\d+)", RegexOption.IGNORE_CASE)
        const val DEFAULT_CONTEXT_TOKENS = 2048

        fun formatDecimalBytes(bytes: Long): String {
            return if (bytes < 1_000_000_000L) {
                "${bytes / 1_000_000L} MB"
            } else {
                val gb = bytes.toDouble() / 1_000_000_000.0
                String.format(Locale.US, "%.2f GB", gb)
            }
        }

        fun parseSizeBytesFromLabel(label: String): Long {
            val gbMatch = Regex("([0-9.]+)\\s*GB", RegexOption.IGNORE_CASE).find(label)
            if (gbMatch != null) {
                val gb = gbMatch.groupValues[1].toDoubleOrNull() ?: 1.0
                return (gb * 1_000_000_000.0).toLong()
            }
            val mbMatch = Regex("([0-9.]+)\\s*MB", RegexOption.IGNORE_CASE).find(label)
            if (mbMatch != null) {
                val mb = mbMatch.groupValues[1].toDoubleOrNull() ?: 500.0
                return (mb * 1_000_000.0).toLong()
            }
            return 0L
        }

        fun parseRamGbFromLabel(label: String): Int {
            return Regex("(\\d+)\\s*GB", RegexOption.IGNORE_CASE).find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 4
        }

        val presetsById: Map<String, ModelPreset> by lazy {
            presets.associateBy { it.id.lowercase() }
        }

        val presetsByFileName: Map<String, ModelPreset> by lazy {
            presets.associateBy { it.fileName.lowercase() }
        }

        fun findByIdOrFileName(identifier: String?): ModelPreset? {
            if (identifier == null) return null
            val lower = identifier.lowercase()
            return presetsById[lower] ?: presetsByFileName[lower]
        }

        fun isReasoningModel(modelId: String?): Boolean {
            if (modelId == null) return false
            val preset = presetsById[modelId.lowercase()]
            if (preset != null) return preset.isReasoningModel
            return modelId.contains("deepseek", ignoreCase = true) || 
                   modelId.contains("qwq", ignoreCase = true)
        }

        fun isKnownNonReasoningModel(modelId: String?): Boolean {
            if (modelId == null) return false
            val preset = presetsById[modelId.lowercase()]
            return preset != null && !preset.isReasoningModel
        }

        val presets = listOf(
            ModelPreset(
                id = "qwen3-0.6b",
                name = "Qwen 3 0.6B (Pocket Turbo)",
                description = "Alibaba's ultra-lightweight hybrid reasoning model. Highly optimized for fast responses on 2GB+ devices with minimal battery drain (Pocket Turbo).",
                sizeBytes = 497516544L,
                minRamGb = 2,
                downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/e6687177e00897731e7716267eebd4eba0dde7c2/qwen3_0_6b_mixed_int4.litertlm",
                fileName = "qwen3-0.6b.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "mixed INT4",
                parameterCount = "0.6B",
                contextLength = "2,048 tokens",
                isReasoningModel = true,
                expectedSha256 = "7900eb4e7362d88c58782c6f9999bb7a129e03544aa98b8f338ea0cc5d8c22c1",
                kvCacheTokens = 2048,
                supportsVision = false,
                speedTier = ModelSpeedTier.TURBO,
                defaultThinkingEnabled = false,
                tags = setOf(ModelTag.TURBO, ModelTag.REASONING)
            ),
            ModelPreset(
                id = "qwen-1.5b",
                name = "Qwen 2.5 1.5B Instruct",
                description = "Alibaba's state-of-the-art multilingual LLM. Outperforms models of similar size in general knowledge, instruction following, and multilingual tasks (Floor Model).",
                sizeBytes = 1597922856L,
                minRamGb = 4,
                downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/5068b3fd9788a4ed5633dc868687f778143b73f3/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
                fileName = "qwen-1.5b.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "Q8 (8-bit)",
                parameterCount = "1.5B",
                contextLength = "4,096 tokens",
                expectedSha256 = "c4a9b7289365ab80fe599b844b77ad253eb3c53fb0e5d963d699146a6f01255e",
                kvCacheTokens = 4096,
                supportsVision = false,
                speedTier = ModelSpeedTier.BALANCED,
                defaultThinkingEnabled = false,
                tags = setOf(ModelTag.MULTILINGUAL)
            ),
            ModelPreset(
                id = "qwen3-1.7b",
                name = "Qwen 3 1.7B (Dynamic INT4)",
                description = "Next-gen compact powerhouse using block-32 INT4 weights and FP32 activations. Delivers enhanced logic and multi-turn stability in a sub-1GB footprint.",
                sizeBytes = 977184032L,
                minRamGb = 4,
                downloadUrl = "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/79bf0667b32ae025b972e5fe555fe72ca6e8816c/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
                fileName = "qwen3-1.7b.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "dynamic INT4",
                parameterCount = "1.7B",
                contextLength = "4,096 tokens",
                isReasoningModel = true,
                expectedSha256 = "2a72330b504a6596d53a2c00923c57173b10f1b83e6a541213ffec08d1fc5de8",
                kvCacheTokens = 4096,
                supportsVision = false,
                speedTier = ModelSpeedTier.BALANCED,
                defaultThinkingEnabled = false,
                tags = setOf(ModelTag.REASONING)
            ),
            ModelPreset(
                id = "qwen2.5-coder-1.5b",
                name = "Qwen 2.5 Coder 1.5B Instruct",
                description = "Official Apache-2.0 code intelligence model. High-speed generation for code completion, script writing, and debugging on 4GB devices (Agile Coder).",
                sizeBytes = 1117385648L,
                minRamGb = 4,
                downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-Coder-1.5B-Instruct/resolve/2bdadb12e1e7d39986c559448e1318f53502197b/Qwen2.5-Coder-1.5B-Instruct_int4.litertlm",
                fileName = "qwen2.5-coder-1.5b.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "INT4",
                parameterCount = "1.5B",
                contextLength = "4,096 tokens",
                expectedSha256 = "273ecc7771ba2dd5fe1bb6d4d4726ad0353102f04ad094082ccf59bca9f21213",
                kvCacheTokens = 4096,
                supportsVision = false,
                speedTier = ModelSpeedTier.BALANCED,
                defaultThinkingEnabled = false,
                tags = setOf(ModelTag.CODING)
            ),
            ModelPreset(
                id = "deepseek-1.5b",
                name = "DeepSeek-R1 Distill Qwen 1.5B",
                description = "DeepSeek's powerful reasoning model distilled into Qwen architecture, outputting detailed chain-of-thought logic (Offline Reasoning).",
                sizeBytes = 1833451520L,
                minRamGb = 6,
                downloadUrl = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/9fd875541bb0f277e4b3cefe7d9622c4c1de2fb5/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
                fileName = "deepseek-r1.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "Q8 (8-bit)",
                parameterCount = "1.5B",
                contextLength = "4,096 tokens",
                isReasoningModel = true,
                expectedSha256 = "69b35f01759eed765641ab4af589bbe98131fd2825662a086d9037409b8c1295",
                kvCacheTokens = 4096,
                supportsVision = false,
                speedTier = ModelSpeedTier.BALANCED,
                defaultThinkingEnabled = true,
                tags = setOf(ModelTag.REASONING)
            ),
            ModelPreset(
                id = "gemma4-e2b",
                name = "Google Gemma 4 E2B Instruct (Multimodal)",
                description = "Google's next-gen multimodal mobile LLM. Features advanced reasoning, high-quality responses, and native multimodal vision support (Budget Multimodal).",
                sizeBytes = 2588147712L,
                minRamGb = 6,
                downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/1caa527c1e88b55f046fcd8d2b4265606680663d/gemma-4-E2B-it.litertlm",
                fileName = "gemma4-e2b.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "mixed 2/4/8-bit",
                parameterCount = "2.4B",
                contextLength = "8,192 tokens",
                expectedSha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
                kvCacheTokens = 8192,
                supportsVision = true,
                speedTier = ModelSpeedTier.BALANCED,
                defaultThinkingEnabled = false,
                tags = setOf(ModelTag.VISION, ModelTag.REASONING)
            ),
            ModelPreset(
                id = "gemma4-e2b-uncensored",
                name = "Gemma 4 E2B Uncensored MAX",
                description = "Community uncensored fine-tune of Gemma 4 E2B with safety refusals ablated. Features full 8,192-token context in INT4 format (Uncensored Assistant; vision unconfirmed until tested).",
                sizeBytes = 2550041824L,
                minRamGb = 6,
                downloadUrl = "https://huggingface.co/PeppX/gemma-4-e2b-uncensored-litertlm/resolve/0adcc4e5497d0bd4202a7a6f2c72c00d1a0d4be9/gemma-4-E2B-it-Uncensored-MAX.litertlm",
                fileName = "gemma4-e2b-uncensored-max.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "INT4",
                parameterCount = "2.3B",
                contextLength = "8,192 tokens",
                expectedSha256 = "34738f7eb50d04ee123d23dcea0df0b49e074b555134101251685f9416f39bb9",
                kvCacheTokens = 8192,
                supportsVision = false,
                speedTier = ModelSpeedTier.BALANCED,
                defaultThinkingEnabled = false,
                tags = setOf(ModelTag.UNCENSORED)
            ),
            ModelPreset(
                id = "supergemma4-e4b-abliterated",
                name = "SuperGemma 4 E4B Abliterated",
                description = "Upgraded community export of abliterated Gemma 4 E4B. Features full 8,192-token context and MTP drafter for uninhibited high-capacity reasoning (Flagship Abliterated; vision unconfirmed until tested).",
                sizeBytes = 3654467584L,
                minRamGb = 8,
                downloadUrl = "https://huggingface.co/typomonster/supergemma4-e4b-abliterated-litert-lm/resolve/3cb37a0fe1688c84cdb9faaa273052d82b8ca68b/supergemma4-e4b-abliterated.litertlm",
                fileName = "supergemma4-e4b-abliterated.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "INT4",
                parameterCount = "4.0B",
                contextLength = "8,192 tokens",
                expectedSha256 = "83399794f0ad10166c0034451e06b9cdb120590eab3665863ff20e443b3f9750",
                kvCacheTokens = 8192,
                supportsVision = false,
                speedTier = ModelSpeedTier.POWER,
                defaultThinkingEnabled = false,
                tags = setOf(ModelTag.UNCENSORED)
            ),
            ModelPreset(
                id = "qwen3-4b",
                name = "Qwen 3 4B (Hybrid Reasoning)",
                description = "Alibaba's hybrid Qwen 3 architecture with 4 billion parameters. Excels at complex synthesis, math, and multi-step deduction with thinking toggle (Mid-Range Reasoning).",
                sizeBytes = 2659053760L,
                minRamGb = 8,
                downloadUrl = "https://huggingface.co/litert-community/Qwen3-4B/resolve/8b4c3d3f6e727cdb445f42feff14b808db8da4fa/qwen3_4b_mixed_int4.litertlm",
                fileName = "qwen3-4b.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "mixed INT4",
                parameterCount = "4.0B",
                contextLength = "2,048 tokens",
                isReasoningModel = true,
                expectedSha256 = "569fe029f51e55b6377f98ebd7f6d26431bc73e1d632034f5389f2fd720da701",
                kvCacheTokens = 2048,
                supportsVision = false,
                speedTier = ModelSpeedTier.POWER,
                defaultThinkingEnabled = false,
                tags = setOf(ModelTag.REASONING)
            ),
            ModelPreset(
                id = "gemma4-e4b",
                name = "Google Gemma 4 E4B Instruct (Multimodal)",
                description = "Google's flagship on-device multimodal LLM. Superior reasoning and vision understanding with native speculative decoding acceleration (Flagship Multimodal).",
                sizeBytes = 3659530240L,
                minRamGb = 8,
                downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/e6a3892a8c22cf87ff99583996149cf3cb32452b/gemma-4-E4B-it.litertlm",
                fileName = "gemma4-e4b.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "mixed 2/4/8-bit",
                parameterCount = "4.0B",
                contextLength = "8,192 tokens",
                expectedSha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
                kvCacheTokens = 8192,
                supportsVision = true,
                speedTier = ModelSpeedTier.POWER,
                defaultThinkingEnabled = false,
                tags = setOf(ModelTag.VISION, ModelTag.REASONING)
            )
        )
    }
}

fun isValidModelFile(file: File, minSizeBytes: Long = ModelSafetyValidator.DEFAULT_MIN_MODEL_SIZE_BYTES): Boolean = ModelSafetyValidator.isValidModelFile(file, minSizeBytes = minSizeBytes)
