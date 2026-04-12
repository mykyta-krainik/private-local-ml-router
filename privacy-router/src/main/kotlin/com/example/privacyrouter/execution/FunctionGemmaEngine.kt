package com.example.privacyrouter.execution

import android.content.Context
import java.io.Closeable

/**
 * FunctionGemma 270M engine. Placeholder — the real engine loads a fine-tuned
 * Gemma 3 270M LiteRT .task file via the MediaPipe/LiteRT GenAI API and returns
 * structured JSON function calls. Until the asset is dropped in, this parses a
 * trivial keyword heuristic so Path A still produces plausible FunctionCalls.
 */
class FunctionGemmaEngine(
    private val context: Context,
    private val modelAssetPath: String = "function_gemma_270m.task",
) : Closeable {

    private val modelAvailable: Boolean = runCatching {
        context.assets.open(modelAssetPath).use { true }
    }.getOrDefault(false)

    fun resolveAction(query: String): FunctionCall {
        if (modelAvailable) {
            // TODO: invoke LiteRT GenAI runner, parse JSON output, build FunctionCall.
            return heuristic(query)
        }
        return heuristic(query)
    }

    /**
     * Used by Stage 1 Tier 2 fallback when MobileBERT confidence is below threshold.
     * Returns a [FunctionCall] whose "function" is "classify_request" and whose args
     * contain `category`, `confidence`, `reasoning` keys.
     */
    fun classifyRequest(query: String): FunctionCall =
        FunctionCall(
            function = "classify_request",
            args = mapOf(
                "category" to "AMBIGUOUS",
                "confidence" to 0.5f,
                "reasoning" to "placeholder — awaiting fine-tuned FunctionGemma asset",
            ),
        )

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

    override fun close() { /* no-op until runner is wired */ }
}
