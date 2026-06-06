package com.example.auralocalai.data

enum class LlmBackendRestriction {
    ANY,
    CPU_ONLY,
    GPU_ONLY
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
    val expectedExtension: String = ".task",
    val backendRestriction: LlmBackendRestriction = LlmBackendRestriction.ANY
) {
    companion object {
        val presets = listOf(
            ModelPreset(
                id = "deepseek-1.5b",
                name = "DeepSeek-R1 Distill Qwen 1.5B",
                description = "DeepSeek's powerful reasoning model distilled into Qwen architecture, outputting detailed chain-of-thought logic.",
                sizeLabel = "1.6 GB",
                ramRequirement = "6 GB+ RAM",
                downloadUrl = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/deepseek_q8_ekv1280.task",
                fileName = "deepseek-r1.task",
                requiresHfToken = false,
                expectedExtension = ".task",
                backendRestriction = LlmBackendRestriction.ANY
            ),
            ModelPreset(
                id = "qwen-1.5b",
                name = "Qwen 2.5 1.5B Instruct",
                description = "Alibaba's state-of-the-art multilingual LLM. Outperforms models of similar size in math, coding, and general knowledge.",
                sizeLabel = "1.6 GB",
                ramRequirement = "6 GB+ RAM",
                downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_seq128_q8_ekv1280.task",
                fileName = "qwen-1.5b.task",
                requiresHfToken = false,
                expectedExtension = ".task",
                backendRestriction = LlmBackendRestriction.ANY
            ),
            ModelPreset(
                id = "gemma2-2b",
                name = "Google Gemma 2 2B IT",
                description = "Google's highly optimized on-device LLM. Excellent logic, reasoning, and conversational capabilities.",
                sizeLabel = "1.6 GB",
                ramRequirement = "6 GB+ RAM",
                downloadUrl = "https://huggingface.co/litert-community/Gemma2-2B-IT/resolve/main/gemma2_q8_multi-prefill-seq_ekv1280.task",
                fileName = "gemma2-2b.task",
                requiresHfToken = false,
                expectedExtension = ".task",
                backendRestriction = LlmBackendRestriction.ANY
            ),
            ModelPreset(
                id = "gemma4-e2b",
                name = "Google Gemma 4 E2B Instruct (Multimodal)",
                description = "Google's next-gen multimodal mobile LLM. Features advanced chain-of-thought logic, high-quality responses, and native multimodal support.",
                sizeLabel = "1.5 GB",
                ramRequirement = "6 GB+ RAM",
                downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-web.task",
                fileName = "gemma4-e2b.task",
                requiresHfToken = false,
                expectedExtension = ".task",
                backendRestriction = LlmBackendRestriction.ANY
            ),
            ModelPreset(
                id = "gemma4-e4b",
                name = "Google Gemma 4 E4B Instruct (Multimodal)",
                description = "Google's powerful on-device LLM with 4B parameters. Superior reasoning, math, and coding over E2B with native multimodal vision support.",
                sizeLabel = "2.8 GB",
                ramRequirement = "8 GB+ RAM",
                downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it-web.task",
                fileName = "gemma4-e4b.task",
                requiresHfToken = false,
                expectedExtension = ".task",
                backendRestriction = LlmBackendRestriction.ANY
            )
        )
    }
}
