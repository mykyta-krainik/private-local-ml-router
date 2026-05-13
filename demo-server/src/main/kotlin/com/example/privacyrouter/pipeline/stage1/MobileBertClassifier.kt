package com.example.privacyrouter.pipeline.stage1

import com.example.privacyrouter.model.ClassificationResult
import com.example.privacyrouter.model.RequestLabel

/** JVM stub — keyword heuristic replacing the Android TFLite/NNAPI classifier. */
class MobileBertClassifier {

    fun classify(query: String): ClassificationResult {
        val q = query.lowercase()
        return when {
            personalKeywords.any { q.contains(it) } ->
                ClassificationResult(RequestLabel.PERSONAL_QUERY, 0.80f, TIER_ID)
            factualKeywords.any { q.contains(it) } ->
                ClassificationResult(RequestLabel.FACTUAL_QUERY, 0.78f, TIER_ID)
            conversationalKeywords.any { q.contains(it) } ->
                ClassificationResult(RequestLabel.CONVERSATIONAL, 0.72f, TIER_ID)
            else ->
                ClassificationResult(RequestLabel.AMBIGUOUS, 0.50f, TIER_ID)
        }
    }

    companion object {
        const val TIER_ID: Int = 1
        const val CONFIDENCE_THRESHOLD: Float = 0.75f

        private val personalKeywords = listOf(
            "my doctor", "my therapist", "my bank", "my balance",
            "my medication", "my prescription", "i feel", "symptoms",
            "my salary", "my rent", "my mortgage", "my insurance",
        )
        private val factualKeywords = listOf(
            "what is", "who is", "when was", "where is", "define",
            "translate", "weather", "temperature",
        )
        private val conversationalKeywords = listOf(
            "tell me a joke", "how are you", "what's up", "tell me a story",
        )
    }
}
