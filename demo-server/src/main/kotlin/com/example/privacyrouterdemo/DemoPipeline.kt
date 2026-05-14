package com.example.privacyrouterdemo

import com.example.privacyrouter.execution.ActionExecutor
import com.example.privacyrouter.execution.CloudApiClient
import com.example.privacyrouter.execution.ExecutionResult
import com.example.privacyrouter.execution.FunctionGemmaEngine
import com.example.privacyrouter.execution.LocalLlmEngine
import com.example.privacyrouter.model.ClassificationResult
import com.example.privacyrouter.model.PiiDetectionResult
import com.example.privacyrouter.model.PolicyConfig
import com.example.privacyrouter.model.RequestLabel
import com.example.privacyrouter.model.RoutingAction
import com.example.privacyrouter.model.RoutingDecision
import com.example.privacyrouter.model.VisualInput
import com.example.privacyrouter.pipeline.stage1.MobileBertClassifier
import com.example.privacyrouter.pipeline.stage1.RequestClassifier
import com.example.privacyrouter.pipeline.stage2.NerModelDetector
import com.example.privacyrouter.pipeline.stage2.PiiDetectionOrchestrator
import com.example.privacyrouter.pipeline.stage2.TextClassifierDetector
import com.example.privacyrouter.pipeline.stage3.PolicyEngine
import com.example.privacyrouter.pipeline.stage3.ScoringBreakdown
import com.example.privacyrouter.pipeline.stage3.SensitivityScorer
import com.example.privacyrouter.redaction.PiiRedactor
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class DemoPipelineResult(
    val query: String,
    val classification: ClassificationResult,
    val piiDetection: PiiDetectionResult,
    val scoring: ScoringBreakdown,
    val routing: RoutingDecision,
    val execution: ExecutionResult,
    val totalLatencyMs: Long,
    val requestIndex: Long,
)

data class AggregateMetrics(
    val totalRequests: Long,
    val tierHits: Map<String, Long>,
    val routingDistribution: Map<String, Long>,
    val scoreHistogram: Map<String, Long>,
    val avgTotalLatencyMs: Double,
    val avgDetectionLatencyMs: Double,
    val piiLeakRateEstimate: Double,
)

class DemoPipeline(
    policy: PolicyConfig = PolicyConfig.default(),
    cloudApiUrl: String? = System.getenv("CLOUD_API_URL"),
    cloudApiKey: String? = System.getenv("CLOUD_API_KEY"),
) {
    private val functionGemmaBackend = FunctionGemmaEngine()
    private val classifier = RequestClassifier(MobileBertClassifier(), functionGemmaBackend)
    private val visualDetector = JvmVisualPiiDetector()
    private val piiOrchestrator = PiiDetectionOrchestrator(TextClassifierDetector(), NerModelDetector(), visualDetector)
    private val policyEngine = PolicyEngine(policy)
    private val redactor = PiiRedactor()
    private val actionExecutor = ActionExecutor()
    private val localLlm = LocalLlmEngine()
    private val cloud: CloudApiClient? = if (!cloudApiUrl.isNullOrBlank() && !cloudApiKey.isNullOrBlank())
        CloudApiClient(cloudApiUrl, cloudApiKey) else null

    // Aggregate metric counters (in-memory, reset on restart)
    private val requestCounter = AtomicLong(0)
    private val tierHits = mutableMapOf("REGEX" to AtomicLong(0), "MOBILEBERT" to AtomicLong(0), "FUNCTION_GEMMA" to AtomicLong(0))
    private val routingCounts = mutableMapOf(
        "LOCAL" to AtomicLong(0), "REDACT_THEN_CLOUD" to AtomicLong(0),
        "CLOUD" to AtomicLong(0), "FUNCTION_GEMMA" to AtomicLong(0),
    )
    private val scoreBuckets = mutableMapOf("0.0-0.35" to AtomicLong(0), "0.35-0.70" to AtomicLong(0), "0.70-1.0" to AtomicLong(0))
    private val totalLatencySum = AtomicLong(0)
    private val detectionLatencySum = AtomicLong(0)
    private val cloudWithEntitiesCount = AtomicLong(0)
    private val cloudTotalCount = AtomicLong(0)

    suspend fun process(query: String, visual: VisualInput? = null): DemoPipelineResult {
        val startedAt = System.currentTimeMillis()
        val index = requestCounter.incrementAndGet()

        val classification = classifier.classify(query)

        val mode = piiOrchestrator.modeFor(classification.label)
        val pii = piiOrchestrator.detectWithVisual(query, visual, mode)

        val (routing, scoring) = if (classification.label == RequestLabel.DEVICE_ACTION) {
            RoutingDecision(RoutingAction.FUNCTION_GEMMA, 0f, "stage1") to
                ScoringBreakdown(0f, 0f, 0f, 0f, "CLOUD")
        } else {
            val breakdown = SensitivityScorer.computeBreakdown(pii.entities, pii.contextualSignals, classification.label)
            policyEngine.evaluate(query, pii.entities, pii.contextualSignals, breakdown.finalScore) to breakdown
        }

        val execution = when (routing.action) {
            RoutingAction.FUNCTION_GEMMA -> {
                val call = functionGemmaBackend.resolveAction(query)
                ExecutionResult.Action(actionExecutor.execute(call))
            }
            RoutingAction.LOCAL -> ExecutionResult.Text(localLlm.generate(query))
            RoutingAction.REDACT_THEN_CLOUD -> {
                val c = cloud ?: return earlyError(query, classification, pii, scoring, routing, index, startedAt, "CLOUD_API_URL / CLOUD_API_KEY not set")
                val redacted = redactor.redact(query, pii.entities)
                ExecutionResult.Text(redactor.restore(c.complete(redacted.redacted), redacted.mapping))
            }
            RoutingAction.CLOUD -> {
                val c = cloud ?: return earlyError(query, classification, pii, scoring, routing, index, startedAt, "CLOUD_API_URL / CLOUD_API_KEY not set")
                ExecutionResult.Text(c.complete(query))
            }
        }

        val totalMs = System.currentTimeMillis() - startedAt
        updateMetrics(classification.tierId, routing, scoring.finalScore, totalMs, pii.detectionLatencyMs, pii.entities.isNotEmpty())

        return DemoPipelineResult(
            query = query,
            classification = classification,
            piiDetection = pii,
            scoring = scoring,
            routing = routing,
            execution = execution,
            totalLatencyMs = totalMs,
            requestIndex = index,
        )
    }

    fun aggregateMetrics(): AggregateMetrics {
        val total = requestCounter.get()
        val cloudTotal = cloudTotalCount.get()
        return AggregateMetrics(
            totalRequests = total,
            tierHits = tierHits.mapValues { it.value.get() },
            routingDistribution = routingCounts.mapValues { it.value.get() },
            scoreHistogram = scoreBuckets.mapValues { it.value.get() },
            avgTotalLatencyMs = if (total > 0) totalLatencySum.get().toDouble() / total else 0.0,
            avgDetectionLatencyMs = if (total > 0) detectionLatencySum.get().toDouble() / total else 0.0,
            piiLeakRateEstimate = if (cloudTotal > 0) cloudWithEntitiesCount.get().toDouble() / cloudTotal else 0.0,
        )
    }

    private fun updateMetrics(tierId: Int, routing: RoutingDecision, score: Float, totalMs: Long, detectionMs: Long, hasEntities: Boolean) {
        val tierKey = when (tierId) { 0 -> "REGEX"; 2 -> "FUNCTION_GEMMA"; else -> "MOBILEBERT" }
        tierHits[tierKey]?.incrementAndGet()
        routingCounts[routing.action.name]?.incrementAndGet()
        val bucket = when { score >= 0.70f -> "0.70-1.0"; score >= 0.35f -> "0.35-0.70"; else -> "0.0-0.35" }
        scoreBuckets[bucket]?.incrementAndGet()
        totalLatencySum.addAndGet(totalMs)
        detectionLatencySum.addAndGet(detectionMs)
        if (routing.action == RoutingAction.CLOUD || routing.action == RoutingAction.REDACT_THEN_CLOUD) {
            cloudTotalCount.incrementAndGet()
            if (hasEntities && routing.action == RoutingAction.CLOUD) cloudWithEntitiesCount.incrementAndGet()
        }
    }

    private fun earlyError(
        query: String, classification: ClassificationResult, pii: PiiDetectionResult,
        scoring: ScoringBreakdown, routing: RoutingDecision, index: Long, startedAt: Long, msg: String,
    ) = DemoPipelineResult(
        query, classification, pii, scoring, routing,
        ExecutionResult.Error(msg), System.currentTimeMillis() - startedAt, index,
    )
}
