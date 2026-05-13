package com.example.privacyrouter.pipeline.stage2

import com.example.privacyrouter.model.PiiEntity

/**
 * Deduplicates overlapping PII spans detected by different tiers.
 * Exact-match spans keep the higher-confidence annotation; partial overlaps
 * are merged into the union span with the higher-confidence type.
 */
object PiiSpanMerger {

    fun merge(a: List<PiiEntity>, b: List<PiiEntity>): List<PiiEntity> {
        val all = (a + b).sortedBy { it.span.first }
        val out = mutableListOf<PiiEntity>()
        for (entity in all) {
            val idx = out.indexOfFirst { overlaps(it.span, entity.span) }
            if (idx == -1) {
                out += entity
            } else {
                val existing = out[idx]
                val winner = if (entity.confidence > existing.confidence) entity else existing
                val unionSpan = minOf(existing.span.first, entity.span.first)..
                    maxOf(existing.span.last, entity.span.last)
                out[idx] = winner.copy(span = unionSpan)
            }
        }
        return out
    }

    private fun overlaps(a: IntRange, b: IntRange): Boolean =
        a.first <= b.last && b.first <= a.last
}
