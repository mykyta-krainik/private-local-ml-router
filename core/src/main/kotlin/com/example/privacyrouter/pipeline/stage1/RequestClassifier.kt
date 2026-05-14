package com.example.privacyrouter.pipeline.stage1

import com.example.privacyrouter.interfaces.FunctionCallingBackend
import com.example.privacyrouter.interfaces.RequestClassifierBackend
import com.example.privacyrouter.model.ClassificationResult
import com.example.privacyrouter.model.RequestLabel

private const val CONFIDENCE_THRESHOLD = 0.75f
private const val TIER_1 = 1
private const val TIER_2 = 2

class RequestClassifier(
    private val backend: RequestClassifierBackend,
    private val functionGemma: FunctionCallingBackend? = null,
) {
    suspend fun classify(query: String): ClassificationResult {
        val start = System.currentTimeMillis()

        RegexPreFilter.match(query)?.let { (label, pattern) ->
            val guarded = ContactSensitivityGuard.guard(query, label)
            return ClassificationResult(
                label = guarded,
                confidence = 0.99f,
                tierId = 0,
                matchedPattern = pattern,
                classificationLatencyMs = System.currentTimeMillis() - start,
            )
        }

        val (label, confidence) = backend.classify(query)
        if (confidence >= CONFIDENCE_THRESHOLD) {
            return ClassificationResult(
                label = ContactSensitivityGuard.guard(query, label),
                confidence = confidence,
                tierId = TIER_1,
                matchedPattern = null,
                classificationLatencyMs = System.currentTimeMillis() - start,
            )
        }

        val fallback = functionGemma?.classifyRequest(query)?.let { call ->
            val category = call.args["category"] as? String
            val fallbackConfidence = (call.args["confidence"] as? Number)?.toFloat() ?: confidence
            val fallbackLabel = runCatching { RequestLabel.valueOf(category.orEmpty()) }
                .getOrDefault(RequestLabel.AMBIGUOUS)
            ClassificationResult(
                label = ContactSensitivityGuard.guard(query, fallbackLabel),
                confidence = fallbackConfidence,
                tierId = TIER_2,
                matchedPattern = null,
                classificationLatencyMs = System.currentTimeMillis() - start,
            )
        }
        return fallback ?: ClassificationResult(
            label = RequestLabel.AMBIGUOUS,
            confidence = confidence,
            tierId = TIER_1,
            matchedPattern = null,
            classificationLatencyMs = System.currentTimeMillis() - start,
        )
    }
}
