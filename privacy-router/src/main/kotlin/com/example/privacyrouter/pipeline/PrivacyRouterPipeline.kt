package com.example.privacyrouter.pipeline

import android.content.Context
import com.example.privacyrouter.execution.ActionExecutor
import com.example.privacyrouter.execution.CloudApiClient
import com.example.privacyrouter.execution.ExecutionResult
import com.example.privacyrouter.execution.FunctionGemmaEngine
import com.example.privacyrouter.execution.LocalLlmEngine
import com.example.privacyrouter.model.ClassificationResult
import com.example.privacyrouter.model.PolicyConfig
import com.example.privacyrouter.model.RawInput
import com.example.privacyrouter.model.RequestLabel
import com.example.privacyrouter.model.RoutingAction
import com.example.privacyrouter.model.RoutingDecision
import com.example.privacyrouter.pipeline.stage1.MobileBertClassifier
import com.example.privacyrouter.pipeline.stage1.RequestClassifier
import com.example.privacyrouter.pipeline.stage2.NerModelDetector
import com.example.privacyrouter.pipeline.stage2.PiiDetectionOrchestrator
import com.example.privacyrouter.pipeline.stage2.TextClassifierDetector
import com.example.privacyrouter.pipeline.stage3.PolicyEngine
import com.example.privacyrouter.pipeline.stage3.SensitivityScorer
import com.example.privacyrouter.redaction.PiiRedactor

data class PipelineResult(
    val input: RawInput,
    val classification: ClassificationResult,
    val routing: RoutingDecision,
    val execution: ExecutionResult,
    val totalLatencyMs: Long,
)

/**
 * Orchestrates all four stages and dispatches to the chosen execution path. Instances
 * should be warmed up once in [com.example.privacyrouter.PrivacyRouterVoiceService.onCreate]
 * to avoid cold-start latency on the first user request.
 */
class PrivacyRouterPipeline(
    private val classifier: RequestClassifier,
    private val piiOrchestrator: PiiDetectionOrchestrator,
    private val policyEngine: PolicyEngine,
    private val redactor: PiiRedactor,
    private val functionGemma: FunctionGemmaEngine,
    private val actionExecutor: ActionExecutor,
    private val localLlm: LocalLlmEngine,
    private val cloud: CloudApiClient?,
) {

    suspend fun process(input: RawInput): PipelineResult {
        val startedAt = System.currentTimeMillis()
        val query = input.transcript

        val classification = classifier.classify(query)
        val pii = piiOrchestrator.detect(query, piiOrchestrator.modeFor(classification.label))

        val routing = if (classification.label == RequestLabel.DEVICE_ACTION) {
            RoutingDecision(RoutingAction.FUNCTION_GEMMA, sensitivityScore = 0f, firedRule = "stage1")
        } else {
            val score = SensitivityScorer.compute(pii.entities, pii.contextualSignals, classification.label)
            policyEngine.evaluate(query, pii.entities, pii.contextualSignals, score)
        }

        val execution = when (routing.action) {
            RoutingAction.FUNCTION_GEMMA -> {
                val call = functionGemma.resolveAction(query)
                ExecutionResult.Action(actionExecutor.execute(call))
            }
            RoutingAction.LOCAL -> ExecutionResult.Text(localLlm.generate(query))
            RoutingAction.REDACT_THEN_CLOUD -> {
                val c = cloud ?: return errorResult(input, classification, routing, startedAt,
                    "cloud client not configured")
                val redacted = redactor.redact(query, pii.entities)
                val reply = c.complete(redacted.redacted)
                ExecutionResult.Text(redactor.restore(reply, redacted.mapping))
            }
            RoutingAction.CLOUD -> {
                val c = cloud ?: return errorResult(input, classification, routing, startedAt,
                    "cloud client not configured")
                ExecutionResult.Text(c.complete(query))
            }
        }

        return PipelineResult(
            input = input,
            classification = classification,
            routing = routing,
            execution = execution,
            totalLatencyMs = System.currentTimeMillis() - startedAt,
        )
    }

    private fun errorResult(
        input: RawInput,
        classification: ClassificationResult,
        routing: RoutingDecision,
        startedAt: Long,
        message: String,
    ) = PipelineResult(
        input = input,
        classification = classification,
        routing = routing,
        execution = ExecutionResult.Error(message),
        totalLatencyMs = System.currentTimeMillis() - startedAt,
    )

    companion object {
        fun build(
            context: Context,
            cloud: CloudApiClient? = null,
            policy: PolicyConfig = PolicyConfig.default(),
        ): PrivacyRouterPipeline {
            val appCtx = context.applicationContext
            return PrivacyRouterPipeline(
                classifier = RequestClassifier(MobileBertClassifier(appCtx)),
                piiOrchestrator = PiiDetectionOrchestrator(
                    tier0 = TextClassifierDetector(appCtx),
                    tier1 = NerModelDetector(appCtx),
                ),
                policyEngine = PolicyEngine(policy),
                redactor = PiiRedactor(),
                functionGemma = FunctionGemmaEngine(appCtx),
                actionExecutor = ActionExecutor(appCtx),
                localLlm = LocalLlmEngine(appCtx),
                cloud = cloud,
            )
        }
    }
}
