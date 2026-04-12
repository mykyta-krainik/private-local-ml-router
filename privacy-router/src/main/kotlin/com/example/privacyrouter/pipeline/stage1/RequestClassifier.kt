package com.example.privacyrouter.pipeline.stage1

import com.example.privacyrouter.model.ClassificationResult
import com.example.privacyrouter.model.RequestLabel

/**
 * Two-tier classifier orchestrator. Tier 0 regex → Tier 1 MobileBERT. When Tier 1
 * confidence falls below [MobileBertClassifier.CONFIDENCE_THRESHOLD] the label is
 * downgraded to AMBIGUOUS, which the pipeline may resolve via a FunctionGemma Tier 2
 * fallback (wired in step e).
 */
class RequestClassifier(
    private val mobileBert: MobileBertClassifier,
) {
    fun classify(query: String): ClassificationResult {
        RegexPreFilter.match(query)?.let { label ->
            val guarded = ContactSensitivityGuard.guard(query, label)
            return ClassificationResult(guarded, confidence = 0.99f, tierId = 0)
        }

        val result = mobileBert.classify(query)
        val guarded = ContactSensitivityGuard.guard(query, result.label)
        return if (result.confidence < MobileBertClassifier.CONFIDENCE_THRESHOLD) {
            ClassificationResult(RequestLabel.AMBIGUOUS, result.confidence, result.tierId)
        } else {
            result.copy(label = guarded)
        }
    }
}
