package com.example.privacyrouter.model

enum class PiiType {
    PERSON,
    LOCATION,
    ORGANIZATION,
    ADDRESS,
    PHONE,
    EMAIL,
    DATE_TIME,
    HEALTH,
    FINANCIAL,
    MISC,
}

enum class DetectionTier {
    TIER_0,
    TIER_1,
    CONTEXTUAL,
    VISUAL,
}

enum class Signal {
    HEALTH_CONTEXT,
    FINANCIAL_CONTEXT,
    SENSITIVE_ROLE,
    IMPLICIT_LOCATION,
}

data class PiiEntity(
    val span: IntRange,
    val text: String,
    val type: PiiType,
    val confidence: Float,
    val source: DetectionTier,
)
