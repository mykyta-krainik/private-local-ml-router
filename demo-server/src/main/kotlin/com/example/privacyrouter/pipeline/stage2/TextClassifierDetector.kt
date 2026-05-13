package com.example.privacyrouter.pipeline.stage2

import com.example.privacyrouter.model.DetectionTier
import com.example.privacyrouter.model.PiiEntity
import com.example.privacyrouter.model.PiiType

/**
 * JVM Tier 0 detector. Replicates the entity types the Android system TextClassifier
 * recognises (email, phone, URL, address, date-time) via pure regex, so Tier 0 is
 * meaningful even without an Android device.
 */
class TextClassifierDetector {

    private data class Recognizer(val type: PiiType, val pattern: Regex, val confidence: Float)

    private val recognizers = listOf(
        Recognizer(
            PiiType.EMAIL,
            Regex("""\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b"""),
            0.95f,
        ),
        Recognizer(
            PiiType.PHONE,
            Regex("""\+?(?:\d[\s\-.()/]?){7,15}\d"""),
            0.80f,
        ),
        Recognizer(
            PiiType.MISC, // URL
            Regex("""https?://[^\s/$.?#][^\s]*""", RegexOption.IGNORE_CASE),
            0.90f,
        ),
        Recognizer(
            PiiType.ADDRESS,
            Regex("""\b\d{1,5}\s+[A-Za-z0-9\s,'.]{4,60}(?:St|Ave|Rd|Blvd|Dr|Ln|Ct|Way|Pl)\b""",
                RegexOption.IGNORE_CASE),
            0.72f,
        ),
        Recognizer(
            PiiType.ADDRESS,
            Regex("""\b\d{5}(?:-\d{4})?\b"""),
            0.65f,
        ),
        Recognizer(
            PiiType.DATE_TIME,
            Regex(
                """\b(?:\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4}|\d{4}[/\-\.]\d{1,2}[/\-\.]\d{1,2}""" +
                    """|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\.?\s+\d{1,2},?\s+\d{4}""" +
                    """|\d{1,2}:\d{2}(?::\d{2})?(?:\s*[AP]M)?)\b""",
                RegexOption.IGNORE_CASE,
            ),
            0.82f,
        ),
    )

    fun detect(query: String): List<PiiEntity> = recognizers.flatMap { rec ->
        rec.pattern.findAll(query).map { match ->
            PiiEntity(
                span = match.range,
                text = match.value,
                type = rec.type,
                confidence = rec.confidence,
                source = DetectionTier.TIER_0,
            )
        }
    }
}
