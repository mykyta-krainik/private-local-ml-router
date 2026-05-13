package com.example.privacyrouter.model

enum class DetectionMode {
    SKIP,
    TIER_0_ONLY,
    PARALLEL,
    FULL,
}

data class PiiDetectionResult(
    val entities: List<PiiEntity>,
    val contextualSignals: Set<Signal>,
    val detectionLatencyMs: Long,
    val tiersUsed: Set<DetectionTier>,
)
