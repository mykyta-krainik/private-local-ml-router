package com.example.privacyrouter.model

enum class RequestLabel {
    DEVICE_ACTION,
    PERSONAL_QUERY,
    FACTUAL_QUERY,
    CONVERSATIONAL,
    AMBIGUOUS,
}

data class ClassificationResult(
    val label: RequestLabel,
    val confidence: Float,
    val tierId: Int,
)
