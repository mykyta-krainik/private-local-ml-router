package com.example.privacyrouter.pipeline.stage3

import com.example.privacyrouter.model.DetectionTier
import com.example.privacyrouter.model.PiiEntity
import com.example.privacyrouter.model.PiiType
import com.example.privacyrouter.model.PolicyConfig
import com.example.privacyrouter.model.RoutingAction
import com.example.privacyrouter.model.Signal
import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyEngineTest {

    private val engine = PolicyEngine(PolicyConfig.default())

    @Test
    fun `allow-list wins over everything`() {
        val decision = engine.evaluate(
            query = "what's the weather tomorrow?",
            entities = listOf(entity(PiiType.HEALTH)),
            signals = setOf(Signal.HEALTH_CONTEXT),
            score = 0.95f,
        )
        assertEquals(RoutingAction.CLOUD, decision.action)
        assertEquals("allow:weather", decision.firedRule)
    }

    @Test
    fun `deny-list forces local`() {
        val decision = engine.evaluate(
            query = "schedule a call with my doctor",
            entities = emptyList(),
            signals = emptySet(),
            score = 0.05f,
        )
        assertEquals(RoutingAction.LOCAL, decision.action)
        assertEquals("deny:my doctor", decision.firedRule)
    }

    @Test
    fun `entity override routes health to local`() {
        val decision = engine.evaluate(
            query = "help me plan the evening",
            entities = listOf(entity(PiiType.HEALTH)),
            signals = emptySet(),
            score = 0.10f,
        )
        assertEquals(RoutingAction.LOCAL, decision.action)
        assertEquals("entity:HEALTH", decision.firedRule)
    }

    @Test
    fun `score threshold fallback picks redact for mid scores`() {
        val decision = engine.evaluate(
            query = "what's 2+2",
            entities = emptyList(),
            signals = emptySet(),
            score = 0.40f,
        )
        assertEquals(RoutingAction.REDACT_THEN_CLOUD, decision.action)
        assertEquals("score", decision.firedRule)
    }

    @Test
    fun `score threshold fallback picks cloud for low scores`() {
        val decision = engine.evaluate(
            query = "tell me a joke",
            entities = emptyList(),
            signals = emptySet(),
            score = 0.10f,
        )
        assertEquals(RoutingAction.CLOUD, decision.action)
    }

    private fun entity(type: PiiType) = PiiEntity(
        span = 0..0,
        text = "x",
        type = type,
        confidence = 0.9f,
        source = DetectionTier.TIER_1,
    )
}
