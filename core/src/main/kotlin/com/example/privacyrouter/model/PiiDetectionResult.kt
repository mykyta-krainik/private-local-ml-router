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
    val tier0EntityCount: Int = 0,
    val tier1EntityCount: Int = 0,
    val sharedEntityCount: Int = 0,
    val visualEntityCount: Int = 0,
)
