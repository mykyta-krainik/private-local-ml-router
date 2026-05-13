package com.example.privacyrouterdemo

import com.example.privacyrouter.execution.ActionExecutor
import com.example.privacyrouter.execution.ActionResult
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
import com.example.privacyrouter.pipeline.stage1.MobileBertClassifier
import com.example.privacyrouter.pipeline.stage1.RequestClassifier
import com.example.privacyrouter.pipeline.stage2.NerModelDetector
import com.example.privacyrouter.pipeline.stage2.PiiDetectionOrchestrator
import com.example.privacyrouter.pipeline.stage2.TextClassifierDetector
import com.example.privacyrouter.pipeline.stage3.PolicyEngine
import com.example.privacyrouter.pipeline.stage3.ScoringBreakdown
import com.example.privacyrouter.pipeline.stage3.SensitivityScorer
import com.example.privacyrouter.redaction.PiiRedactor

data class DemoPipelineResult(
    val query: String,
    val classification: ClassificationResult,
    val classificationTierLabel: String,
    val piiDetection: PiiDetectionResult,
    val scoring: ScoringBreakdown,
    val routing: RoutingDecision,
    val execution: ExecutionResult,
    val totalLatencyMs: Long,
)

class DemoPipeline(
    policy: PolicyConfig = PolicyConfig.default(),
    cloudApiUrl: String? = System.getenv("CLOUD_API_URL"),
    cloudApiKey: String? = System.getenv("CLOUD_API_KEY"),
) {
    private val classifier = RequestClassifier(MobileBertClassifier(), FunctionGemmaEngine())
    private val piiOrchestrator = PiiDetectionOrchestrator(TextClassifierDetector(), NerModelDetector())
    private val policyEngine = PolicyEngine(policy)
    private val redactor = PiiRedactor()
    private val functionGemma = FunctionGemmaEngine()
    private val actionExecutor = ActionExecutor()
    private val localLlm = LocalLlmEngine()
    private val cloud: CloudApiClient? = if (!cloudApiUrl.isNullOrBlank() && !cloudApiKey.isNullOrBlank())
        CloudApiClient(cloudApiUrl, cloudApiKey) else null

    suspend fun process(query: String): DemoPipelineResult {
        val startedAt = System.currentTimeMillis()

        val classification = classifier.classify(query)
        val tierLabel = when (classification.tierId) {
            0 -> "REGEX"
            1 -> "MOBILEBERT_HEURISTIC"
            2 -> "FUNCTION_GEMMA_FALLBACK"
            else -> "UNKNOWN"
        }

        val mode = piiOrchestrator.modeFor(classification.label)
        val pii = piiOrchestrator.detect(query, mode)

        val (routing, scoring) = if (classification.label == RequestLabel.DEVICE_ACTION) {
            RoutingDecision(RoutingAction.FUNCTION_GEMMA, 0f, "stage1") to
                ScoringBreakdown(0f, 0f, 0f, 0f)
        } else {
            val breakdown = SensitivityScorer.computeBreakdown(
                pii.entities, pii.contextualSignals, classification.label,
            )
            policyEngine.evaluate(
                query, pii.entities, pii.contextualSignals, breakdown.finalScore,
            ) to breakdown
        }

        val execution = when (routing.action) {
            RoutingAction.FUNCTION_GEMMA -> {
                val call = functionGemma.resolveAction(query)
                ExecutionResult.Action(actionExecutor.execute(call))
            }
            RoutingAction.LOCAL -> ExecutionResult.Text(localLlm.generate(query))
            RoutingAction.REDACT_THEN_CLOUD -> {
                val c = cloud
                    ?: return DemoPipelineResult(
                        query, classification, tierLabel, pii, scoring,
                        routing, ExecutionResult.Error("CLOUD_API_URL / CLOUD_API_KEY not set"),
                        System.currentTimeMillis() - startedAt,
                    )
                val redacted = redactor.redact(query, pii.entities)
                ExecutionResult.Text(redactor.restore(c.complete(redacted.redacted), redacted.mapping))
            }
            RoutingAction.CLOUD -> {
                val c = cloud
                    ?: return DemoPipelineResult(
                        query, classification, tierLabel, pii, scoring,
                        routing, ExecutionResult.Error("CLOUD_API_URL / CLOUD_API_KEY not set"),
                        System.currentTimeMillis() - startedAt,
                    )
                ExecutionResult.Text(c.complete(query))
            }
        }

        return DemoPipelineResult(
            query = query,
            classification = classification,
            classificationTierLabel = tierLabel,
            piiDetection = pii,
            scoring = scoring,
            routing = routing,
            execution = execution,
            totalLatencyMs = System.currentTimeMillis() - startedAt,
        )
    }

}
