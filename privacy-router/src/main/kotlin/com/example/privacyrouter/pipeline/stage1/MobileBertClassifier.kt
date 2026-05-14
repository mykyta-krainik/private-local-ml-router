package com.example.privacyrouter.pipeline.stage1

import android.content.Context
import android.util.Log
import com.example.privacyrouter.ml.TfLiteLoader
import com.example.privacyrouter.ml.WordPieceTokenizer
import com.example.privacyrouter.interfaces.RequestClassifierBackend
import com.example.privacyrouter.model.RequestLabel
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.Closeable
import kotlin.math.exp

/**
 * MobileBERT intent classifier. Uses NNAPI-delegated TFLite inference when the
 * `.tflite` model and matching vocab are bundled in assets; otherwise falls back to a
 * lightweight keyword heuristic so the pipeline remains runnable end-to-end.
 *
 * Expected model I/O:
 *   inputs:  input_ids [1, SEQ_LEN], attention_mask [1, SEQ_LEN], token_type_ids [1, SEQ_LEN]
 *   outputs: logits    [1, NUM_LABELS]
 *
 * [LABELS] order must match the training label encoder.
 */
class MobileBertClassifier(
    private val context: Context,
    private val modelAssetPath: String = "mobilbert_classifier.tflite",
    private val vocabAssetPath: String = "mobilbert_vocab.txt",
    private val seqLen: Int = 64,
) : RequestClassifierBackend, Closeable {

    private val nnApiDelegate: NnApiDelegate? by lazy {
        runCatching { NnApiDelegate() }.getOrNull()
    }
    private val interpreter: Interpreter?
    private val tokenizer: WordPieceTokenizer?

    init {
        val built = runCatching {
            val buffer = TfLiteLoader.loadFromAssets(context, modelAssetPath)
            val options = Interpreter.Options().apply {
                nnApiDelegate?.let { addDelegate(it) }
                setNumThreads(2)
            }
            Interpreter(buffer, options) to
                WordPieceTokenizer.fromAssets(context, vocabAssetPath)
        }.onFailure { Log.i(TAG, "MobileBERT asset missing; using heuristic: ${it.message}") }
        interpreter = built.getOrNull()?.first
        tokenizer = built.getOrNull()?.second
    }

    override suspend fun classify(text: String): Pair<RequestLabel, Float> {
        val result = classifyInternal(text)
        return result.label to result.confidence
    }

    fun classifyInternal(query: String): com.example.privacyrouter.model.ClassificationResult {
        val engine = interpreter
        val tok = tokenizer
        if (engine != null && tok != null) {
            return runCatching { runInference(engine, tok, query) }
                .getOrElse {
                    Log.w(TAG, "MobileBERT inference failed; falling back to heuristic", it)
                    heuristic(query)
                }
        }
        return heuristic(query)
    }

    private fun heuristic(query: String) = com.example.privacyrouter.model.ClassificationResult(
        label = when {
            personalKeywords.any { query.lowercase().contains(it) } -> RequestLabel.PERSONAL_QUERY
            factualKeywords.any { query.lowercase().contains(it) } -> RequestLabel.FACTUAL_QUERY
            conversationalKeywords.any { query.lowercase().contains(it) } -> RequestLabel.CONVERSATIONAL
            else -> RequestLabel.AMBIGUOUS
        },
        confidence = 0.50f,
        tierId = TIER_ID,
    )

    private fun runInference(
        engine: Interpreter,
        tok: WordPieceTokenizer,
        query: String,
    ): ClassificationResult {
        val encoded = tok.encode(query, seqLen)
        val inputIds = arrayOf(encoded.inputIds)
        val mask = arrayOf(encoded.attentionMask)
        val types = arrayOf(encoded.tokenTypeIds)
        val logits = Array(1) { FloatArray(LABELS.size) }

        val inputs = arrayOf<Any>(inputIds, mask, types)
        val outputs = mutableMapOf<Int, Any>(0 to logits)
        engine.runForMultipleInputsOutputs(inputs, outputs)

        val probs = softmax(logits[0])
        var bestIdx = 0
        for (i in probs.indices) if (probs[i] > probs[bestIdx]) bestIdx = i
        return ClassificationResult(LABELS[bestIdx], probs[bestIdx], TIER_ID)
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.max()
        val exps = FloatArray(logits.size) { exp((logits[it] - max).toDouble()).toFloat() }
        val sum = exps.sum()
        return FloatArray(logits.size) { exps[it] / sum }
    }


    override fun close() {
        interpreter?.close()
        nnApiDelegate?.close()
    }

    companion object {
        private const val TAG = "MobileBertClassifier"
        const val TIER_ID: Int = 1
        const val CONFIDENCE_THRESHOLD: Float = 0.75f

        /** Label order — must match the training-time label encoder. */
        val LABELS: Array<RequestLabel> = arrayOf(
            RequestLabel.DEVICE_ACTION,
            RequestLabel.PERSONAL_QUERY,
            RequestLabel.FACTUAL_QUERY,
            RequestLabel.CONVERSATIONAL,
            RequestLabel.AMBIGUOUS,
        )

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
