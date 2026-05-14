package com.example.privacyrouter.execution

import android.content.Context
import android.util.Log
import com.example.privacyrouter.interfaces.LlmBackend
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Path B — on-device LLM engine. Primary model: Gemma 3 4B Q4 (`.task`) via MediaPipe
 * LLM Inference API. Falls back to a deterministic stub when the `.task` file is
 * missing so the pipeline is still runnable end-to-end for integration testing.
 */
class LocalLlmEngine(
    private val context: Context,
    private val modelPath: String = "/data/local/tmp/gemma3_4b_q4.task",
    private val maxTokens: Int = 1024,
    private val temperature: Float = 0.8f,
    private val topK: Int = 40,
    private val randomSeed: Int = 101,
) : LlmBackend, Closeable {

    private val llm: LlmInference? = runCatching {
        if (!File(modelPath).exists()) return@runCatching null
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(maxTokens)
            .setTopK(topK)
            .setTemperature(temperature)
            .setRandomSeed(randomSeed)
            .build()
        LlmInference.createFromOptions(context, options)
    }.onFailure { Log.w(TAG, "LocalLlmEngine failed to init: ${it.message}") }.getOrNull()

    override suspend fun generate(prompt: String): String {
        val engine = llm ?: return stub(prompt)
        return runCatching { invoke(engine, prompt) }
            .onFailure { Log.w(TAG, "generate() failed; using stub", it) }
            .getOrDefault(stub(prompt))
    }

    private suspend fun invoke(engine: LlmInference, prompt: String): String =
        withContext(Dispatchers.IO) {
            engine.generateResponse(prompt)
        }

    private fun stub(prompt: String): String =
        "[local-llm placeholder] ${prompt.take(120)}"

    override fun close() {
        runCatching { llm?.close() }
    }

    companion object {
        private const val TAG = "LocalLlmEngine"
    }
}
