package com.example.privacyrouter.execution

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.Closeable
import java.io.File

/**
 * FunctionGemma 270M engine. Loads a fine-tuned Gemma 3 270M `.task` file via the
 * MediaPipe LLM Inference API (same runtime as the main local LLM; FunctionGemma is
 * just a smaller fine-tune prompted to emit JSON function calls). Falls back to a
 * regex heuristic when the `.task` file is missing.
 */
class FunctionGemmaEngine(
    private val context: Context,
    private val modelPath: String = "/data/local/tmp/function_gemma_270m.task",
    private val maxTokens: Int = 256,
    private val temperature: Float = 0.1f,
    private val topK: Int = 1,
) : Closeable {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val mapAdapter = moshi.adapter(
        com.squareup.moshi.Types.newParameterizedType(
            Map::class.java, String::class.java, Any::class.java,
        ),
    )

    private val llm: LlmInference? = runCatching {
        if (!File(modelPath).exists()) return@runCatching null
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(maxTokens)
            .setTopK(topK)
            .setTemperature(temperature)
            .build()
        LlmInference.createFromOptions(context, options)
    }.onFailure { Log.w(TAG, "FunctionGemmaEngine failed to init: ${it.message}") }.getOrNull()

    fun resolveAction(query: String): FunctionCall =
        llm?.let { runCatching { invokeAction(it, query) }.getOrNull() }
            ?: heuristic(query)

    fun classifyRequest(query: String): FunctionCall =
        llm?.let { runCatching { invokeClassify(it, query) }.getOrNull() }
            ?: FunctionCall(
                function = "classify_request",
                args = mapOf("category" to "AMBIGUOUS", "confidence" to 0.5f),
            )

    private fun invokeAction(engine: LlmInference, query: String): FunctionCall {
        val prompt = ACTION_PROMPT.replace("{{QUERY}}", query)
        val raw = engine.generateResponse(prompt)
        return parseFunctionCall(raw)
    }

    private fun invokeClassify(engine: LlmInference, query: String): FunctionCall {
        val prompt = CLASSIFY_PROMPT.replace("{{QUERY}}", query)
        val raw = engine.generateResponse(prompt)
        return parseFunctionCall(raw)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseFunctionCall(raw: String): FunctionCall {
        val firstBrace = raw.indexOf('{')
        val lastBrace = raw.lastIndexOf('}')
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            return FunctionCall("unknown", mapOf("raw" to raw))
        }
        val json = raw.substring(firstBrace, lastBrace + 1)
        val parsed = mapAdapter.fromJson(json) as? Map<String, Any?>
            ?: return FunctionCall("unknown", mapOf("raw" to raw))
        val function = (parsed["function"] as? String) ?: "unknown"
        val args = (parsed["parameters"] as? Map<String, Any?>)
            ?: (parsed["args"] as? Map<String, Any?>)
            ?: emptyMap()
        return FunctionCall(function, args)
    }

    private fun heuristic(query: String): FunctionCall {
        val q = query.lowercase()
        return when {
            Regex("set (a )?timer").containsMatchIn(q) ->
                FunctionCall("set_timer", extractDuration(q))
            Regex("set (a )?alarm").containsMatchIn(q) ->
                FunctionCall("set_alarm", extractTime(q))
            Regex("turn (on|off) (?:the )?flashlight").containsMatchIn(q) ->
                FunctionCall("toggle_flashlight", mapOf("on" to q.contains(" on ")))
            Regex("add (?:a )?(?:calendar )?event|create calendar event").containsMatchIn(q) ->
                FunctionCall("create_calendar_event", mapOf("title" to query))
            Regex("call (?:my )?\\w+").containsMatchIn(q) ->
                FunctionCall("make_phone_call", mapOf("contact" to query))
            Regex("send (?:a )?(?:sms|text|message)").containsMatchIn(q) ->
                FunctionCall("send_sms", mapOf("body" to query))
            else -> FunctionCall("unknown", mapOf("raw" to query))
        }
    }

    private fun extractDuration(q: String): Map<String, Any?> {
        val match = Regex("""(\d+)\s*(second|minute|hour)s?""").find(q)
        return mapOf(
            "amount" to (match?.groupValues?.get(1)?.toIntOrNull() ?: 5),
            "unit" to (match?.groupValues?.get(2) ?: "minute"),
        )
    }

    private fun extractTime(q: String): Map<String, Any?> {
        val match = Regex("""(\d{1,2}):?(\d{2})?\s*(am|pm)?""").find(q)
        return mapOf(
            "hour" to (match?.groupValues?.get(1)?.toIntOrNull() ?: 7),
            "minute" to (match?.groupValues?.get(2)?.toIntOrNull() ?: 0),
            "meridiem" to (match?.groupValues?.get(3) ?: ""),
        )
    }

    override fun close() {
        runCatching { llm?.close() }
    }

    companion object {
        private const val TAG = "FunctionGemmaEngine"

        private const val ACTION_PROMPT = """You are an Android on-device function-calling model. Emit a single JSON object with "function" and "parameters" keys. Supported functions: create_calendar_event, set_alarm, set_timer, make_phone_call, send_sms, toggle_flashlight.
Query: {{QUERY}}
JSON:"""

        private const val CLASSIFY_PROMPT = """You classify user queries into categories: DEVICE_ACTION, PERSONAL_QUERY, FACTUAL_QUERY, CONVERSATIONAL, AMBIGUOUS. Emit a single JSON object with keys "function"="classify_request" and "parameters" containing "category", "confidence" (0..1), and "reasoning".
Query: {{QUERY}}
JSON:"""
    }
}
