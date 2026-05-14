package com.example.privacyrouter.execution

import com.example.privacyrouter.interfaces.FunctionCallingBackend
import com.example.privacyrouter.model.RequestLabel

/** JVM stub — regex heuristic replacing the MediaPipe FunctionGemma 270M engine. */
class FunctionGemmaEngine : FunctionCallingBackend {

    override suspend fun resolveAction(query: String): FunctionCall = heuristic(query)

    override suspend fun classifyRequest(query: String): FunctionCall = FunctionCall(
        function = "classify_request",
        args = mapOf(
            "category" to RequestLabel.AMBIGUOUS.name,
            "confidence" to 0.5f,
            "reasoning" to "JVM stub — FunctionGemma not available without MediaPipe",
        ),
    )

    private fun heuristic(query: String): FunctionCall {
        val q = query.lowercase()
        return when {
            Regex("set (a )?timer").containsMatchIn(q) -> FunctionCall("set_timer", extractDuration(q))
            Regex("set (a )?alarm").containsMatchIn(q) -> FunctionCall("set_alarm", extractTime(q))
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
}
