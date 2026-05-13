package com.example.privacyrouter.pipeline.stage2

import com.example.privacyrouter.model.DetectionTier
import com.example.privacyrouter.model.PiiEntity
import com.example.privacyrouter.model.PiiType

/**
 * Kotlin port of a subset of Presidio's structured PII recognizers. Pure regex — no
 * model. Used alongside Tier 0 TextClassifier to catch credit cards, SSNs, passports,
 * and medical record identifiers that TextClassifier misses.
 */
object PresidioRegexRecognizers {

    private data class Recognizer(
        val type: PiiType,
        val pattern: Regex,
        val confidence: Float,
    )

    private val recognizers = listOf(
        Recognizer(
            PiiType.FINANCIAL,
            Regex("""\b(?:\d[ -]?){13,16}\b"""),
            0.80f,
        ),
        Recognizer(
            PiiType.FINANCIAL,
            Regex("""\b\d{3}-\d{2}-\d{4}\b"""),
            0.90f,
        ),
        Recognizer(
            PiiType.PHONE,
            Regex("""\+?\d[\d\s\-().]{6,14}\d"""),
            0.70f,
        ),
        Recognizer(
            PiiType.EMAIL,
            Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"""),
            0.95f,
        ),
        Recognizer(
            PiiType.MISC,
            Regex("""\b[A-Z]{1,2}\d{6,9}\b"""),
            0.60f,
        ),
        Recognizer(
            PiiType.HEALTH,
            Regex("""\bMRN[:#\s-]*\d{5,10}\b""", RegexOption.IGNORE_CASE),
            0.85f,
        ),
    )

    fun detect(query: String): List<PiiEntity> = recognizers.flatMap { rec ->
        rec.pattern.findAll(query).map { match ->
            PiiEntity(
                span = match.range,
                text = match.value,
                type = rec.type,
                confidence = rec.confidence,
                source = DetectionTier.TIER_1,
            )
        }
    }
}
