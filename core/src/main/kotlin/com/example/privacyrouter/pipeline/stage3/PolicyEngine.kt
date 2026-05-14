package com.example.privacyrouter.pipeline.stage3

import com.example.privacyrouter.model.PiiEntity
import com.example.privacyrouter.model.PolicyConfig
import com.example.privacyrouter.model.RoutingAction
import com.example.privacyrouter.model.RoutingDecision
import com.example.privacyrouter.model.Signal

class PolicyEngine(private val policy: PolicyConfig = PolicyConfig.default()) {

    fun evaluate(
        query: String,
        entities: List<PiiEntity>,
        signals: Set<Signal>,
        score: Float,
    ): RoutingDecision {
        policy.allowList
            .firstOrNull { query.contains(it, ignoreCase = true) }
            ?.let { return decision(RoutingAction.CLOUD, score, "allow:$it") }

        policy.denyList
            .firstOrNull { query.contains(it, ignoreCase = true) }
            ?.let { return decision(RoutingAction.LOCAL, score, "deny:$it") }

        for (entity in entities) {
            val rule = policy.entityRules.firstOrNull { it.type == entity.type && it.override }
            if (rule != null) return decision(RoutingAction.from(rule.action), score, "entity:${entity.type}")
        }

        for (signal in signals) {
            val rule = policy.signalRules.firstOrNull { it.signal == signal && it.override }
            if (rule != null) return decision(RoutingAction.from(rule.action), score, "signal:$signal")
        }

        val action = when {
            score >= policy.scoreThresholds.local -> RoutingAction.LOCAL
            score >= policy.scoreThresholds.redactThenCloud -> RoutingAction.REDACT_THEN_CLOUD
            else -> RoutingAction.CLOUD
        }
        return decision(action, score, firedRule = "score")
    }

    private fun decision(action: RoutingAction, score: Float, firedRule: String?) =
        RoutingDecision(action, score, firedRule)
}
