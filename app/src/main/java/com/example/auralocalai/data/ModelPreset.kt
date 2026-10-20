package com.example.auralocalai.data

import androidx.compose.runtime.Immutable
import java.io.File
import java.util.zip.ZipFile

enum class LlmBackendRestriction {
    ANY,
    CPU_ONLY,
    GPU_ONLY,
    NPU_ONLY
}

@Immutable
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
            return modelId.contains("deepseek", ignoreCase = true) || modelId.contains("qwq", ignoreCase = true)
        }

        fun isKnownNonReasoningModel(modelId: String?): Boolean {
            if (modelId == null) return false
            val preset = presetsById[modelId.lowercase()]
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
                expectedSha256 = "c4a9b7289365ab80fe599b844b77ad253eb3c53fb0e5d963d699146a6f01255e"
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
                expectedSha256 = "569fe029f51e55b6377f98ebd7f6d26431bc73e1d632034f5389f2fd720da701"
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
            ),
            ModelPreset(
                id = "gemma4-e2b-uncensored",
                name = "Gemma 4 E2B Uncensored",
                description = "Community uncensored fine-tune of Gemma 4 E2B with refusal behavior ablated and converted to the LiteRT-LM runtime (Uncensored).",
                sizeLabel = "2.4 GB",
                ramRequirement = "6 GB+ RAM",
                downloadUrl = "https://huggingface.co/PeppX/gemma-4-e2b-uncensored-litertlm/resolve/main/gemma-4-E2B-it-Uncensored-MAX.litertlm",
                fileName = "gemma4-e2b-uncensored.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "INT4",
                parameterCount = "2.3B",
                contextLength = "8,192 tokens",
                expectedSha256 = "34738f7eb50d04ee123d23dcea0df0b49e074b555134101251685f9416f39bb9"
            ),
            ModelPreset(
                id = "gemma4-e4b-abliterated",
                name = "Gemma 4 E4B Abliterated",
                description = "Community abliterated fine-tune of Gemma 4 E4B with refusal directions removed, converted to LiteRT-LM. Higher capacity than E2B for uncensored chat (Uncensored).",
                sizeLabel = "3.4 GB",
                ramRequirement = "12 GB+ RAM",
                downloadUrl = "https://huggingface.co/olekk/gemma-4-E4B-it-abliterated-litert-lm/resolve/main/gemma-4-E4B-it-abliterated.litertlm",
                fileName = "gemma4-e4b-abliterated.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "INT4",
                parameterCount = "4.0B",
                contextLength = "8,192 tokens",
                expectedSha256 = "f5a6625e0368e853743c91b39fa6ac6a8add1fc4825b18f98f518966610f2a88"
            ),
            ModelPreset(
                id = "supergemma4-e4b-abliterated",
                name = "SuperGemma 4 E4B Abliterated",
                description = "Typomonster's abliterated SuperGemma 4 E4B fine-tune converted to LiteRT-LM for on-device use. Aggressive refusal removal for uncensored roleplay (Uncensored).",
                sizeLabel = "3.4 GB",
                ramRequirement = "12 GB+ RAM",
                downloadUrl = "https://huggingface.co/typomonster/supergemma4-e4b-abliterated-litert-lm/resolve/main/supergemma4-e4b-abliterated.litertlm",
                fileName = "supergemma4-e4b-abliterated.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "INT4",
                parameterCount = "4.0B",
                contextLength = "8,192 tokens",
                expectedSha256 = "83399794f0ad10166c0034451e06b9cdb120590eab3665863ff20e443b3f9750"
            ),
            ModelPreset(
                id = "gemma4-e2b-heretic",
                name = "Gemma 4 E2B Ultra Uncensored (Heretic)",
                description = "Heretic-style ultra-uncensored fine-tune of Gemma 4 E2B converted to LiteRT-LM. Instruction following with safety refusals stripped (Heretic).",
                sizeLabel = "2.4 GB",
                ramRequirement = "6 GB+ RAM",
                downloadUrl = "https://huggingface.co/jamarag/gemma-4-E2B-it-ultra-uncensored-heretic-litertlm/resolve/main/model.litertlm",
                fileName = "gemma4-e2b-heretic.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "INT4",
                parameterCount = "2.3B",
                contextLength = "8,192 tokens",
                expectedSha256 = "28622ff12905f78403a11790c81cbb0bae20760767e278930d126131fbb03373"
            ),
            ModelPreset(
                id = "qwen2.5-3b-abliterated",
                name = "Qwen 2.5 3B Abliterated",
                description = "Community abliterated Qwen 2.5 3B Instruct converted to LiteRT-LM. Multilingual uncensored assistant with solid coding ability (Uncensored).",
                sizeLabel = "2.9 GB",
                ramRequirement = "8 GB+ RAM",
                downloadUrl = "https://huggingface.co/Sean1357/qwen2.5-3b-abliterated-litertlm/resolve/main/model.litertlm",
                fileName = "qwen2.5-3b-abliterated.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "INT4",
                parameterCount = "3.0B",
                contextLength = "4,096 tokens",
                expectedSha256 = "e044fe3c60441df97a6eaa2123d3e0e3d73b4d3c21bd95b07eaf8d788dcbbd61"
            ),
            ModelPreset(
                id = "qwen3-4b-abliterated",
                name = "Qwen 3 4B Abliterated",
                description = "Community abliterated Qwen 3 4B converted to LiteRT-LM. Latest Qwen generation with refusal training removed (Uncensored).",
                sizeLabel = "4.2 GB",
                ramRequirement = "8 GB+ RAM",
                downloadUrl = "https://huggingface.co/internetov1488/qwen3-4b-abliterated-litertlm/resolve/main/model.litertlm",
                fileName = "qwen3-4b-abliterated.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "INT4",
                parameterCount = "4.0B",
                contextLength = "4,096 tokens",
                expectedSha256 = "945599718d5d4942dbbdd816ae1cd9cbef8a4c7ce219104c7b61c09c7fa98d21"
            ),
            ModelPreset(
                id = "lfm2.5-3b-heretic",
                name = "LFM 2.5 VL 3B Heretic (Experimental Vision)",
                description = "LiquidAI LFM2.5 VL 3B heretic fine-tune in INT4 for LiteRT-LM. Compact uncensored vision-language model running CPU-only (Experimental Vision).",
                sizeLabel = "2.0 GB",
                ramRequirement = "4 GB+ RAM",
                downloadUrl = "https://huggingface.co/stuarch/LFM2.5-VL-3B-heretic-litert-lm/resolve/main/LFM2.5-VL-3B-heretic_int4.litertlm",
                fileName = "lfm2.5-3b-heretic.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.CPU_ONLY,
                quantization = "INT4",
                parameterCount = "3.2B",
                contextLength = "4,096 tokens",
                expectedSha256 = "2eaebe43683e1db15a2b292b4353afcf73492e4c444d9638a8beda3489519ee6"
            ),
            ModelPreset(
                id = "deepseek-r1-7b",
                name = "DeepSeek-R1 Distill Qwen 7B",
                description = "DeepSeek's R1 reasoning distilled into Qwen 7B and quantized for LiteRT-LM. The largest official DeepSeek reasoning bundle available on-device (Offline Reasoning).",
                sizeLabel = "4.3 GB",
                ramRequirement = "12 GB+ RAM",
                downloadUrl = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-7B/resolve/main/DeepSeek-R1-Distill-Qwen-7B_q4_block32_ekv4096.litertlm",
                fileName = "deepseek-r1-7b.litertlm",
                requiresHfToken = false,
                expectedExtension = ".litertlm",
                backendRestriction = LlmBackendRestriction.ANY,
                quantization = "INT4 (q4_block32)",
                parameterCount = "7.0B",
                contextLength = "4,096 tokens",
                isReasoningModel = true,
                expectedSha256 = "050787a59d80acf064ed71209e45a28a7ae734a06f409f271751f254643216e1"
            )
        )
    }
}

fun isValidModelFile(file: File, minSizeBytes: Long = ModelSafetyValidator.DEFAULT_MIN_MODEL_SIZE_BYTES): Boolean = ModelSafetyValidator.isValidModelFile(file, minSizeBytes = minSizeBytes)
