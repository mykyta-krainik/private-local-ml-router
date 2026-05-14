package com.example.privacyrouter.pipeline.stage2

import com.example.privacyrouter.interfaces.NerDetectorBackend
import com.example.privacyrouter.model.DetectionTier
import com.example.privacyrouter.model.PiiEntity
import com.example.privacyrouter.model.PiiType

/** JVM Tier 1 NER stub — capitalised-token heuristic + Presidio regex recognizers. */
class NerModelDetector : NerDetectorBackend {

    override suspend fun detect(text: String): List<PiiEntity> =
        heuristicNer(text) + PresidioRegexRecognizers.detect(text)

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
