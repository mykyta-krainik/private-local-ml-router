package com.example.privacyrouter.ml

import android.content.Context
import java.io.BufferedReader

/**
 * Minimal WordPiece tokenizer compatible with BERT/MobileBERT/DistilBERT vocab files
 * (one token per line). Supports lowercase uncased models with `[CLS]` / `[SEP]` /
 * `[PAD]` / `[UNK]` special tokens.
 */
class WordPieceTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val doLowerCase: Boolean,
) {
    private val clsId = vocab["[CLS]"] ?: 101
    private val sepId = vocab["[SEP]"] ?: 102
    private val padId = vocab["[PAD]"] ?: 0
    private val unkId = vocab["[UNK]"] ?: 100

    fun encode(text: String, maxLen: Int): Encoded {
        val normalized = if (doLowerCase) text.lowercase() else text
        val ids = ArrayList<Int>(maxLen)
        ids += clsId
        for (word in normalized.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            if (ids.size >= maxLen - 1) break
            ids += tokenizeWord(word)
            if (ids.size >= maxLen - 1) break
        }
        if (ids.size >= maxLen) {
            ids.subList(maxLen - 1, ids.size).clear()
        }
        ids += sepId
        val mask = IntArray(maxLen)
        val paddedIds = IntArray(maxLen)
        for (i in 0 until maxLen) {
            if (i < ids.size) {
                paddedIds[i] = ids[i]
                mask[i] = 1
            } else {
                paddedIds[i] = padId
                mask[i] = 0
            }
        }
        return Encoded(inputIds = paddedIds, attentionMask = mask, tokenTypeIds = IntArray(maxLen))
    }

    private fun tokenizeWord(word: String): List<Int> {
        if (vocab.containsKey(word)) return listOf(vocab.getValue(word))
        val pieces = mutableListOf<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var match: String? = null
            while (start < end) {
                val candidate = if (start == 0) word.substring(start, end)
                else "##" + word.substring(start, end)
                if (vocab.containsKey(candidate)) {
                    match = candidate
                    break
                }
                end--
            }
            if (match == null) {
                pieces += unkId
                return pieces
            }
            pieces += vocab.getValue(match)
            start = end
        }
        return pieces
    }

    data class Encoded(
        val inputIds: IntArray,
        val attentionMask: IntArray,
        val tokenTypeIds: IntArray,
    )

    companion object {
        fun fromAssets(
            context: Context,
            vocabAssetPath: String,
            doLowerCase: Boolean = true,
        ): WordPieceTokenizer {
            val vocab = HashMap<String, Int>(30000)
            context.assets.open(vocabAssetPath).bufferedReader().use { reader: BufferedReader ->
                reader.forEachLine { line ->
                    val token = line.trim()
                    if (token.isNotEmpty()) vocab[token] = vocab.size
                }
            }
            return WordPieceTokenizer(vocab, doLowerCase)
        }
    }
}
