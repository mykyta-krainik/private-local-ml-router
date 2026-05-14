package com.example.privacyrouter.pipeline.stage2

import com.example.privacyrouter.interfaces.NerDetectorBackend
import com.example.privacyrouter.interfaces.TextClassifierBackend
import com.example.privacyrouter.interfaces.VisualPiiDetectorBackend
import com.example.privacyrouter.model.DetectionMode
import com.example.privacyrouter.model.DetectionTier
import com.example.privacyrouter.model.PiiDetectionResult
import com.example.privacyrouter.model.RequestLabel
import com.example.privacyrouter.model.VisualInput
import com.example.privacyrouter.model.VisualPiiDetectionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class PiiDetectionOrchestrator(
    private val tier0: TextClassifierBackend,
    private val tier1: NerDetectorBackend,
    private val visualDetector: VisualPiiDetectorBackend? = null,
) {

    fun modeFor(label: RequestLabel): DetectionMode = when (label) {
        RequestLabel.DEVICE_ACTION -> DetectionMode.SKIP
        RequestLabel.FACTUAL_QUERY -> DetectionMode.TIER_0_ONLY
        RequestLabel.CONVERSATIONAL -> DetectionMode.PARALLEL
        RequestLabel.PERSONAL_QUERY, RequestLabel.AMBIGUOUS -> DetectionMode.FULL
    }

    suspend fun detect(query: String, mode: DetectionMode): PiiDetectionResult =
        detectWithVisual(query, visual = null, mode = mode)

    suspend fun detectWithVisual(
        query: String,
        visual: VisualInput?,
        mode: DetectionMode,
    ): PiiDetectionResult {
        val start = System.currentTimeMillis()
        return when (mode) {
            DetectionMode.SKIP -> PiiDetectionResult(
                entities = emptyList(),
                contextualSignals = emptySet(),
                detectionLatencyMs = 0L,
                tiersUsed = emptySet(),
            )

            DetectionMode.TIER_0_ONLY -> coroutineScope {
                val visualJob = if (visual != null && visualDetector != null)
                    async(Dispatchers.Default) { visualDetector.detect(visual) } else null
                val t0 = withContext(Dispatchers.Default) { tier0.detect(query) }
                val escalated = if (t0.isNotEmpty()) {
                    withContext(Dispatchers.Default) { tier1.detect(query) }
                } else emptyList()
                val tiers = buildSet {
                    add(DetectionTier.TIER_0)
                    if (escalated.isNotEmpty()) add(DetectionTier.TIER_1)
                }
                val visualResult = visualJob?.await() ?: VisualPiiDetectionResult.empty()
                buildResult(query, t0, escalated, emptySet(), tiers, visualResult, start)
            }

            DetectionMode.PARALLEL -> coroutineScope {
                val visualJob = if (visual != null && visualDetector != null)
                    async(Dispatchers.Default) { visualDetector.detect(visual) } else null
                val t0 = async(Dispatchers.Default) { tier0.detect(query) }
                val t1 = async(Dispatchers.Default) { tier1.detect(query) }
                val visualResult = visualJob?.await() ?: VisualPiiDetectionResult.empty()
                buildResult(
                    query, t0.await(), t1.await(), emptySet(),
                    setOf(DetectionTier.TIER_0, DetectionTier.TIER_1), visualResult, start,
                )
            }

            DetectionMode.FULL -> coroutineScope {
                val visualJob = if (visual != null && visualDetector != null)
                    async(Dispatchers.Default) { visualDetector.detect(visual) } else null
                val t0 = async(Dispatchers.Default) { tier0.detect(query) }
                val t1 = async(Dispatchers.Default) { tier1.detect(query) }
                val ctx = async(Dispatchers.Default) { ContextualPiiDetector.detect(query) }
                val visualResult = visualJob?.await() ?: VisualPiiDetectionResult.empty()
                buildResult(
                    query, t0.await(), t1.await(), ctx.await(),
                    setOf(DetectionTier.TIER_0, DetectionTier.TIER_1, DetectionTier.CONTEXTUAL),
                    visualResult, start,
                )
            }
        }
    }

    private fun buildResult(
        query: String,
        t0Entities: List<com.example.privacyrouter.model.PiiEntity>,
        t1Entities: List<com.example.privacyrouter.model.PiiEntity>,
        signals: Set<com.example.privacyrouter.model.Signal>,
        tiers: Set<DetectionTier>,
        visual: VisualPiiDetectionResult,
        start: Long,
    ): PiiDetectionResult {
        val t0Set = t0Entities.map { it.span }.toSet()
        val t1Set = t1Entities.map { it.span }.toSet()
        val shared = t0Set.intersect(t1Set).size
        val merged = PiiSpanMerger.merge(t0Entities, t1Entities)
        val visualEntities = visual.visualEntities.map { it.toPiiEntity() } +
            visual.ocrDerivedTextEntities
        val allEntities = merged + visualEntities
        val allTiers = if (visual.visualEntities.isNotEmpty() || visual.ocrDerivedTextEntities.isNotEmpty())
            tiers + DetectionTier.VISUAL else tiers
        return PiiDetectionResult(
            entities = allEntities,
            contextualSignals = signals,
            detectionLatencyMs = System.currentTimeMillis() - start,
            tiersUsed = allTiers,
            tier0EntityCount = t0Entities.size,
            tier1EntityCount = t1Entities.size,
            sharedEntityCount = shared,
            visualEntityCount = visualEntities.size,
        )
    }
}
