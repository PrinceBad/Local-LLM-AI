package com.example.auralocalai.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmInferenceEngineTest {

    @Test
    fun testIsChatMlOrQwenModel_detectsQwenAndDeepSeekPresets() {
        assertTrue(LlmInferenceEngine.isChatMlOrQwenModel("qwen-1.5b.litertlm"))
        assertTrue(LlmInferenceEngine.isChatMlOrQwenModel("qwen-1.5b-uncensored.litertlm"))
        assertTrue(LlmInferenceEngine.isChatMlOrQwenModel("deepseek-r1.litertlm"))
        assertTrue(LlmInferenceEngine.isChatMlOrQwenModel("qwen2.5-coder-3b.litertlm"))
        assertTrue(LlmInferenceEngine.isChatMlOrQwenModel("qwen3-4b.litertlm"))
        assertTrue(LlmInferenceEngine.isChatMlOrQwenModel("/storage/emulated/0/Download/custom_qwen_model.bin"))
        assertTrue(LlmInferenceEngine.isChatMlOrQwenModel("chatml_custom.litertlm"))
    }

    @Test
    fun testIsChatMlOrQwenModel_doesNotFlagGemmaModels() {
        assertFalse(LlmInferenceEngine.isChatMlOrQwenModel("gemma4-e2b.litertlm"))
        assertFalse(LlmInferenceEngine.isChatMlOrQwenModel("gemma4-e4b.litertlm"))
        assertFalse(LlmInferenceEngine.isChatMlOrQwenModel("gemma4-e2b-abliterated.litertlm"))
        assertFalse(LlmInferenceEngine.isChatMlOrQwenModel("supergemma4-e4b-abliterated.litertlm"))
        assertFalse(LlmInferenceEngine.isChatMlOrQwenModel(null))
        assertFalse(LlmInferenceEngine.isChatMlOrQwenModel("unknown_model.litertlm"))
    }

    @Test
    fun testQwenChatMlTemplate_doesNotUseUnsafePlusOnContent() {
        val template = LlmInferenceEngine.QWEN_CHATML_TEMPLATE

        // Must define ChatML delimiters
        assertTrue("Must include <|im_start|>", template.contains("<|im_start|>"))
        assertTrue("Must include <|im_end|>", template.contains("<|im_end|>"))

        // Must handle LiteRT-LM sequence content parts
        assertTrue("Must handle string content", template.contains("message.content is string"))
        assertTrue("Must handle item.text for sequence parts", template.contains("item.text is defined"))

        // Must NOT perform binary + on message.content (which causes Minja string+sequence crash)
        assertFalse("Cannot use + message.content +", template.contains("+ message.content"))
        assertFalse("Cannot use + message['content']", template.contains("+ message['content']"))
    }

    @Test
    fun testNpuCacheVersion_isExplicitlyStamped() {
        assertTrue("NPU cache version must be stamped for migration", LlmInferenceEngine.NPU_CACHE_VERSION.isNotBlank())
        assertTrue("NPU cache version must include version identifier", LlmInferenceEngine.NPU_CACHE_VERSION.contains("v3.0"))
    }

    @Test
    fun testIsMissingSpeculativeDrafterError_detectsDirectAndWrappedCauses() {
        val direct = RuntimeException("Failed to create engine: NOT_FOUND: tf_lite_mtp_drafter not found in the model.")
        assertTrue(LlmInferenceEngine.isMissingSpeculativeDrafterError(direct))

        val wrapped = Exception("CPU initialization failed", RuntimeException("missing mtp_drafter subgraph"))
        assertTrue(LlmInferenceEngine.isMissingSpeculativeDrafterError(wrapped))

        val unrelated = RuntimeException("Vulkan VK_ERROR_DEVICE_LOST during shader pipeline creation")
        assertFalse(LlmInferenceEngine.isMissingSpeculativeDrafterError(unrelated))
    }

    @Test
    fun testGpuCacheVersion_isExplicitlyStamped() {
        assertTrue("GPU cache version must be stamped for migration", LlmInferenceEngine.GPU_CACHE_VERSION.isNotBlank())
        assertTrue("GPU cache version must reflect GPU-first migration", LlmInferenceEngine.GPU_CACHE_VERSION.contains("v3.1"))
    }
}
