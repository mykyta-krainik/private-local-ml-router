package com.example.privacyrouter.pipeline.stage2

import android.content.Context
import android.util.Log
import com.example.privacyrouter.ml.TfLiteLoader
import com.example.privacyrouter.ml.WordPieceTokenizer
import com.example.privacyrouter.interfaces.NerDetectorBackend
import com.example.privacyrouter.model.DetectionTier
import com.example.privacyrouter.model.PiiEntity
import com.example.privacyrouter.model.PiiType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.Closeable
import kotlin.math.exp

/**
 * Tier 1 NER detector. Uses NNAPI-delegated TFLite inference when a DistilBERT-NER
 * `.tflite` model and matching vocab are bundled in assets; otherwise applies a
 * capitalized-token heuristic. Always unions results with [PresidioRegexRecognizers].
 *
 * Expected model I/O:
 *   inputs:  input_ids [1, SEQ_LEN], attention_mask [1, SEQ_LEN]
 *   outputs: logits    [1, SEQ_LEN, NUM_TAGS]  (BIO tags, order in [TAGS])
 */
class NerModelDetector(
    private val context: Context,
    private val modelAssetPath: String = "ner_model.tflite",
    private val vocabAssetPath: String = "ner_vocab.txt",
    private val seqLen: Int = 128,
) : NerDetectorBackend, Closeable {

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
        }.onFailure { Log.i(TAG, "NER asset missing; using heuristic: ${it.message}") }
        interpreter = built.getOrNull()?.first
        tokenizer = built.getOrNull()?.second
    }

    override suspend fun detect(text: String): List<PiiEntity> = detectSync(text)

    fun detectSync(query: String): List<PiiEntity> {
        val modelEntities = interpreter?.let { engine ->
            tokenizer?.let { tok ->
                runCatching { runInference(engine, tok, query) }
                    .getOrElse {
                        Log.w(TAG, "NER inference failed; using heuristic", it)
                        heuristicNer(query)
                    }
            }
        } ?: heuristicNer(query)
        return modelEntities + PresidioRegexRecognizers.detect(query)
    }

    private fun runInference(
        engine: Interpreter,
        tok: WordPieceTokenizer,
        query: String,
    ): List<PiiEntity> {
        val encoded = tok.encode(query, seqLen)
        val inputIds = arrayOf(encoded.inputIds)
        val mask = arrayOf(encoded.attentionMask)
        val logits = Array(1) { Array(seqLen) { FloatArray(TAGS.size) } }

        val inputs = arrayOf<Any>(inputIds, mask)
        val outputs = mutableMapOf<Int, Any>(0 to logits)
        engine.runForMultipleInputsOutputs(inputs, outputs)

        // Token-aligned BIO decoding. Without per-token offsets (not preserved by the
        // WordPieceTokenizer above) we fall back to word-level span approximation by
        // re-tokenizing the query to whitespace-split words and walking the logits
        // alongside them. This is sufficient for the prototype; for production, keep
        // offsets during tokenization.
        val words = query.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val result = mutableListOf<PiiEntity>()
        var tokenIdx = 1 // skip [CLS]
        var charCursor = 0
        var currentTagIdx = 0
        var currentStart = -1
        var currentScoreSum = 0f
        var currentCount = 0

        for (word in words) {
            charCursor = query.indexOf(word, charCursor)
            if (charCursor < 0) break
            val wordEnd = charCursor + word.length
            if (tokenIdx >= seqLen - 1) break
            val probs = softmax(logits[0][tokenIdx])
            val bestTag = probs.indices.maxBy { probs[it] }
            val tag = TAGS[bestTag]
            val type = tagToType(tag)
            if (type != null && tag.startsWith("B-")) {
                flush(result, query, currentTagIdx, currentStart, charCursor, currentScoreSum, currentCount)
                currentTagIdx = bestTag
                currentStart = charCursor
                currentScoreSum = probs[bestTag]
                currentCount = 1
            } else if (type != null && tag.startsWith("I-") && currentStart >= 0) {
                currentScoreSum += probs[bestTag]
                currentCount += 1
            } else {
                flush(result, query, currentTagIdx, currentStart, charCursor, currentScoreSum, currentCount)
                currentStart = -1
                currentScoreSum = 0f
                currentCount = 0
            }
            charCursor = wordEnd
            tokenIdx += 1
        }
        flush(result, query, currentTagIdx, currentStart, charCursor, currentScoreSum, currentCount)
        return result
    }

    private fun flush(
        out: MutableList<PiiEntity>,
        query: String,
        tagIdx: Int,
        start: Int,
        endExclusive: Int,
        scoreSum: Float,
        count: Int,
    ) {
        if (start < 0 || count == 0) return
        val type = tagToType(TAGS[tagIdx]) ?: return
        val end = (endExclusive - 1).coerceAtLeast(start)
        out += PiiEntity(
            span = start..end,
            text = query.substring(start, endExclusive.coerceAtMost(query.length)),
            type = type,
            confidence = scoreSum / count,
            source = DetectionTier.TIER_1,
        )
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.max()
        val exps = FloatArray(logits.size) { exp((logits[it] - max).toDouble()).toFloat() }
        val sum = exps.sum()
        return FloatArray(logits.size) { exps[it] / sum }
    }

    private fun tagToType(tag: String): PiiType? = when {
        tag.endsWith("PER") -> PiiType.PERSON
        tag.endsWith("LOC") -> PiiType.LOCATION
        tag.endsWith("ORG") -> PiiType.ORGANIZATION
        tag.endsWith("MISC") -> PiiType.MISC
        else -> null
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

    override fun close() {
        interpreter?.close()
        nnApiDelegate?.close()
    }

    companion object {
        private const val TAG = "NerModelDetector"

        /** BIO tag order — must match the exported NER model's label encoder. */
        val TAGS: Array<String> = arrayOf(
            "O",
            "B-PER", "I-PER",
            "B-LOC", "I-LOC",
            "B-ORG", "I-ORG",
            "B-MISC", "I-MISC",
        )
    }
}
