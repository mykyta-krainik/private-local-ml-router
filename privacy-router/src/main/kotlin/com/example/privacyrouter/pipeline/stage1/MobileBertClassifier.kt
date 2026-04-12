package com.example.privacyrouter.pipeline.stage1

import android.content.Context
import com.example.privacyrouter.model.ClassificationResult
import com.example.privacyrouter.model.RequestLabel
import java.io.Closeable

/**
 * MobileBERT intent classifier. Placeholder implementation: the real engine loads a
 * TFLite model from assets/mobilbert_classifier.tflite with an NNAPI delegate. Until the
 * asset is dropped in, this falls back to a lightweight keyword heuristic so the
 * pipeline remains runnable end-to-end.
 */
class MobileBertClassifier(
    private val context: Context,
    private val modelAssetPath: String = "mobilbert_classifier.tflite",
) : Closeable {

    private val modelAvailable: Boolean = runCatching {
        context.assets.open(modelAssetPath).use { true }
    }.getOrDefault(false)

    fun classify(query: String): ClassificationResult {
        if (modelAvailable) {
            // TODO: tokenize with the model's vocab, run Interpreter inference on NNAPI,
            //  softmax the logits, and return (argmax label, max probability).
            return heuristic(query)
        }
        return heuristic(query)
    }

    private fun heuristic(query: String): ClassificationResult {
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

    override fun close() { /* no-op until interpreter is wired */ }

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
