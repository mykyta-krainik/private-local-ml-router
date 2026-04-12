package com.example.privacyrouter.pipeline.stage2

import android.content.Context
import com.example.privacyrouter.model.DetectionTier
import com.example.privacyrouter.model.PiiEntity
import com.example.privacyrouter.model.PiiType
import java.io.Closeable

/**
 * Tier 1 NER detector. Placeholder — the real engine loads a DistilBERT-NER TFLite
 * model from assets/ner_model.tflite via NNAPI. Until the asset is dropped in, it
 * applies a small capitalized-token heuristic plus the Presidio regex recognizers so
 * the pipeline yields plausible entities end-to-end.
 */
class NerModelDetector(
    private val context: Context,
    private val modelAssetPath: String = "ner_model.tflite",
) : Closeable {

    private val modelAvailable: Boolean = runCatching {
        context.assets.open(modelAssetPath).use { true }
    }.getOrDefault(false)

    fun detect(query: String): List<PiiEntity> {
        val nerEntities = if (modelAvailable) {
            // TODO: run TFLite DistilBERT-NER with NNAPI delegate, map BIO tags to
            //  PiiType (PER→PERSON, LOC→LOCATION, ORG→ORGANIZATION, MISC→MISC).
            heuristicNer(query)
        } else {
            heuristicNer(query)
        }
        return nerEntities + PresidioRegexRecognizers.detect(query)
    }

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

    override fun close() { /* no-op until interpreter is wired */ }
}
