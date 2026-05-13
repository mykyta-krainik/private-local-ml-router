package com.example.privacyrouter.pipeline.stage2

import com.example.privacyrouter.model.DetectionMode
import com.example.privacyrouter.model.DetectionTier
import com.example.privacyrouter.model.PiiDetectionResult
import com.example.privacyrouter.model.RequestLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class PiiDetectionOrchestrator(
    private val tier0: TextClassifierDetector,
    private val tier1: NerModelDetector,
) {

    fun modeFor(label: RequestLabel): DetectionMode = when (label) {
        RequestLabel.DEVICE_ACTION -> DetectionMode.SKIP
        RequestLabel.FACTUAL_QUERY -> DetectionMode.TIER_0_ONLY
        RequestLabel.CONVERSATIONAL -> DetectionMode.PARALLEL
        RequestLabel.PERSONAL_QUERY, RequestLabel.AMBIGUOUS -> DetectionMode.FULL
    }

    suspend fun detect(query: String, mode: DetectionMode): PiiDetectionResult {
        val start = System.currentTimeMillis()
        return when (mode) {
            DetectionMode.SKIP -> PiiDetectionResult(
                entities = emptyList(),
                contextualSignals = emptySet(),
                detectionLatencyMs = 0L,
                tiersUsed = emptySet(),
            )

            DetectionMode.TIER_0_ONLY -> {
                val t0 = withContext(Dispatchers.Default) { tier0.detect(query) }
                val escalated = if (t0.isNotEmpty()) {
                    withContext(Dispatchers.Default) { tier1.detect(query) }
                } else emptyList()
                val tiers = buildSet {
                    add(DetectionTier.TIER_0)
                    if (escalated.isNotEmpty()) add(DetectionTier.TIER_1)
                }
                PiiDetectionResult(
                    entities = PiiSpanMerger.merge(t0, escalated),
                    contextualSignals = emptySet(),
                    detectionLatencyMs = System.currentTimeMillis() - start,
                    tiersUsed = tiers,
                )
            }

            DetectionMode.PARALLEL -> coroutineScope {
                val t0 = async(Dispatchers.Default) { tier0.detect(query) }
                val t1 = async(Dispatchers.Default) { tier1.detect(query) }
                PiiDetectionResult(
                    entities = PiiSpanMerger.merge(t0.await(), t1.await()),
                    contextualSignals = emptySet(),
                    detectionLatencyMs = System.currentTimeMillis() - start,
                    tiersUsed = setOf(DetectionTier.TIER_0, DetectionTier.TIER_1),
                )
            }

            DetectionMode.FULL -> coroutineScope {
                val t0 = async(Dispatchers.Default) { tier0.detect(query) }
                val t1 = async(Dispatchers.Default) { tier1.detect(query) }
                val ctx = async(Dispatchers.Default) { ContextualPiiDetector.detect(query) }
                PiiDetectionResult(
                    entities = PiiSpanMerger.merge(t0.await(), t1.await()),
                    contextualSignals = ctx.await(),
                    detectionLatencyMs = System.currentTimeMillis() - start,
                    tiersUsed = setOf(
                        DetectionTier.TIER_0, DetectionTier.TIER_1, DetectionTier.CONTEXTUAL,
                    ),
                )
            }
        }
    }
}
