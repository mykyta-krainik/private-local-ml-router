package com.example.privacyrouter.pipeline.stage3

import com.example.privacyrouter.model.DetectionTier
import com.example.privacyrouter.model.PiiEntity
import com.example.privacyrouter.model.PiiType
import com.example.privacyrouter.model.RequestLabel
import com.example.privacyrouter.model.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitivityScorerTest {

    @Test
    fun `device action label zeroes the score`() {
        val entity = entity(PiiType.PERSON, 0.9f)
        val score = SensitivityScorer.compute(listOf(entity), emptySet(), RequestLabel.DEVICE_ACTION)
        assertEquals(0.0f, score, 1e-4f)
    }

    @Test
    fun `empty input yields zero`() {
        val score = SensitivityScorer.compute(emptyList(), emptySet(), RequestLabel.FACTUAL_QUERY)
        assertEquals(0.0f, score, 1e-4f)
    }

    @Test
    fun `health entity with personal label dominates`() {
        val entities = listOf(entity(PiiType.HEALTH, 0.9f))
        val score = SensitivityScorer.compute(entities, emptySet(), RequestLabel.PERSONAL_QUERY)
        assertTrue("expected routing-to-local score, got $score", score >= 0.70f)
    }

    @Test
    fun `contextual health signal escalates factual query`() {
        val score = SensitivityScorer.compute(
            entities = emptyList(),
            signals = setOf(Signal.HEALTH_CONTEXT),
            label = RequestLabel.FACTUAL_QUERY,
        )
        assertTrue(score > 0.0f)
        assertTrue(score <= 1.0f)
    }

    @Test
    fun `signal boost caps at 0_5`() {
        val score = SensitivityScorer.compute(
            entities = emptyList(),
            signals = setOf(
                Signal.HEALTH_CONTEXT,
                Signal.FINANCIAL_CONTEXT,
                Signal.SENSITIVE_ROLE,
                Signal.IMPLICIT_LOCATION,
            ),
            label = RequestLabel.CONVERSATIONAL,
        )
        assertEquals(0.50f, score, 1e-4f)
    }

    @Test
    fun `score is clamped to 1_0`() {
        val entities = List(5) { entity(PiiType.HEALTH, 1.0f) }
        val score = SensitivityScorer.compute(
            entities,
            setOf(Signal.HEALTH_CONTEXT, Signal.FINANCIAL_CONTEXT),
            RequestLabel.PERSONAL_QUERY,
        )
        assertEquals(1.0f, score, 1e-4f)
    }

    private fun entity(type: PiiType, confidence: Float) = PiiEntity(
        span = 0..0,
        text = "x",
        type = type,
        confidence = confidence,
        source = DetectionTier.TIER_1,
    )
}
