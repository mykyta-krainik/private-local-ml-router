package com.example.privacyrouter.pipeline.stage1

import com.example.privacyrouter.execution.FunctionGemmaEngine
import com.example.privacyrouter.model.ClassificationResult
import com.example.privacyrouter.model.RequestLabel

/**
 * Three-tier classifier orchestrator:
 *   Tier 0 — regex pre-filter (< 1 ms)
 *   Tier 1 — MobileBERT fine-tuned classifier (10–20 ms on NNAPI)
 *   Tier 2 — FunctionGemma fallback, invoked only when Tier 1 confidence is below
 *            [MobileBertClassifier.CONFIDENCE_THRESHOLD]
 */
class RequestClassifier(
    private val mobileBert: MobileBertClassifier,
    private val functionGemma: FunctionGemmaEngine? = null,
) {
    fun classify(query: String): ClassificationResult {
        RegexPreFilter.match(query)?.let { label ->
            val guarded = ContactSensitivityGuard.guard(query, label)
            return ClassificationResult(guarded, confidence = 0.99f, tierId = 0)
        }

        val tier1 = mobileBert.classify(query)
        if (tier1.confidence >= MobileBertClassifier.CONFIDENCE_THRESHOLD) {
            return tier1.copy(label = ContactSensitivityGuard.guard(query, tier1.label))
        }

        val fallback = functionGemma?.classifyRequest(query)?.let { call ->
            val category = call.args["category"] as? String
            val confidence = (call.args["confidence"] as? Number)?.toFloat() ?: tier1.confidence
            val label = runCatching { RequestLabel.valueOf(category.orEmpty()) }
                .getOrDefault(RequestLabel.AMBIGUOUS)
            ClassificationResult(
                label = ContactSensitivityGuard.guard(query, label),
                confidence = confidence,
                tierId = TIER_2,
            )
        }
        return fallback ?: ClassificationResult(
            label = RequestLabel.AMBIGUOUS,
            confidence = tier1.confidence,
            tierId = tier1.tierId,
        )
    }

    companion object {
        const val TIER_2: Int = 2
    }
}
