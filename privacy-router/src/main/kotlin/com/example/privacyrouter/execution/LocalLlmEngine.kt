package com.example.privacyrouter.execution

import android.content.Context
import java.io.Closeable

/**
 * Path B — on-device LLM engine (primary model: Gemma 3 4B Q4 via MediaPipe LLM
 * Inference API). Placeholder — the real engine calls
 * `LlmInference.createFromOptions(...)` and `generateResponseAsync(...)`. Until the
 * .task file is dropped in, this returns a deterministic stub so the pipeline is
 * runnable end-to-end.
 */
class LocalLlmEngine(
    private val context: Context,
    private val modelPath: String = "/data/local/tmp/gemma3_4b_q4.task",
    private val maxTokens: Int = 1024,
    private val temperature: Float = 0.8f,
) : Closeable {

    private val modelAvailable: Boolean = java.io.File(modelPath).exists()

    suspend fun generate(prompt: String): String {
        if (modelAvailable) {
            // TODO: construct LlmInferenceOptions(modelPath, maxTokens, topK=40,
            //  temperature, randomSeed), create LlmInference, call
            //  generateResponseAsync and collect partials into a single string.
            return stub(prompt)
        }
        return stub(prompt)
    }

    private fun stub(prompt: String): String =
        "[local-llm placeholder] ${prompt.take(120)}"

    override fun close() { /* no-op until LlmInference is wired */ }
}
