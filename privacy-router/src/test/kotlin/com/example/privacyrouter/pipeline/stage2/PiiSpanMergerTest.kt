package com.example.privacyrouter.pipeline.stage2

import com.example.privacyrouter.model.DetectionTier
import com.example.privacyrouter.model.PiiEntity
import com.example.privacyrouter.model.PiiType
import org.junit.Assert.assertEquals
import org.junit.Test

class PiiSpanMergerTest {

    @Test
    fun `non-overlapping entities are kept intact`() {
        val a = entity(0..3, PiiType.PERSON, 0.8f)
        val b = entity(10..15, PiiType.EMAIL, 0.9f)
        val merged = PiiSpanMerger.merge(listOf(a), listOf(b))
        assertEquals(2, merged.size)
    }

    @Test
    fun `exact overlap keeps higher confidence annotation`() {
        val low = entity(5..10, PiiType.MISC, 0.4f)
        val high = entity(5..10, PiiType.PERSON, 0.95f)
        val merged = PiiSpanMerger.merge(listOf(low), listOf(high))
        assertEquals(1, merged.size)
        assertEquals(PiiType.PERSON, merged[0].type)
        assertEquals(0.95f, merged[0].confidence, 1e-4f)
    }

    @Test
    fun `partial overlap produces union span and higher-conf type`() {
        val left = entity(0..5, PiiType.PERSON, 0.70f)
        val right = entity(3..8, PiiType.ORGANIZATION, 0.80f)
        val merged = PiiSpanMerger.merge(listOf(left), listOf(right))
        assertEquals(1, merged.size)
        assertEquals(0..8, merged[0].span)
        assertEquals(PiiType.ORGANIZATION, merged[0].type)
    }

    private fun entity(span: IntRange, type: PiiType, confidence: Float) = PiiEntity(
        span = span,
        text = "x".repeat(span.last - span.first + 1),
        type = type,
        confidence = confidence,
        source = DetectionTier.TIER_1,
    )
}
