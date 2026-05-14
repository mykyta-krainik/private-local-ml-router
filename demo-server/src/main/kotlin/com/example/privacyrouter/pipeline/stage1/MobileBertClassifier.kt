package com.example.privacyrouter.pipeline.stage1

import com.example.privacyrouter.interfaces.RequestClassifierBackend
import com.example.privacyrouter.model.RequestLabel

/** JVM stub — keyword heuristic replacing the Android TFLite/NNAPI classifier. */
class MobileBertClassifier : RequestClassifierBackend {

    override suspend fun classify(text: String): Pair<RequestLabel, Float> {
        val q = text.lowercase()
        return when {
            personalKeywords.any { q.contains(it) } -> RequestLabel.PERSONAL_QUERY to 0.80f
            factualKeywords.any { q.contains(it) } -> RequestLabel.FACTUAL_QUERY to 0.78f
            conversationalKeywords.any { q.contains(it) } -> RequestLabel.CONVERSATIONAL to 0.72f
            else -> RequestLabel.AMBIGUOUS to 0.50f
        }
    }

    companion object {
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
