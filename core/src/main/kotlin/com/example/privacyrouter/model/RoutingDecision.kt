package com.example.privacyrouter.model

enum class RoutingAction {
    LOCAL,
    REDACT_THEN_CLOUD,
    CLOUD,
    FUNCTION_GEMMA;

    companion object {
        fun from(raw: String): RoutingAction = when (raw.lowercase()) {
            "route_local", "local" -> LOCAL
            "redact_then_cloud" -> REDACT_THEN_CLOUD
            "cloud" -> CLOUD
            "function_gemma" -> FUNCTION_GEMMA
            else -> throw IllegalArgumentException("Unknown routing action: $raw")
        }
    }
}

data class RoutingDecision(
    val action: RoutingAction,
    val sensitivityScore: Float,
    val firedRule: String?,
)

data class RawInput(
    val transcript: String,
    val timestampMs: Long,
)
