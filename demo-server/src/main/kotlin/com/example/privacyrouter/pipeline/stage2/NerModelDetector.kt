package com.example.privacyrouter.pipeline.stage2

import com.example.privacyrouter.model.DetectionTier
import com.example.privacyrouter.model.PiiEntity
import com.example.privacyrouter.model.PiiType

/**
 * JVM Tier 1 NER detector. Uses a capitalised-token heuristic (standing in for
 * DistilBERT-NER) plus the full Presidio regex recognizers.
 */
class NerModelDetector {

    fun detect(query: String): List<PiiEntity> =
        heuristicNer(query) + PresidioRegexRecognizers.detect(query)

    private fun heuristicNer(query: String): List<PiiEntity> {
        val result = mutableListOf<PiiEntity>()
        val tokenPattern = Regex("""\b[A-Z][a-zA-Z]+(?:\s+[A-Z][a-zA-Z]+)*\b""")
        for (match in tokenPattern.findAll(query)) {
            if (match.range.first == 0) continue
            result += PiiEntity(
                span = match.range,
                text = match.value,
                type = PiiType.PERSON,
                confidence = 0.55f,
                source = DetectionTier.TIER_1,
            )
        }
        return result
    }
}
