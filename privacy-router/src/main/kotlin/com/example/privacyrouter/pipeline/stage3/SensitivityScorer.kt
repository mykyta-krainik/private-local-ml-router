package com.example.privacyrouter.pipeline.stage3

import com.example.privacyrouter.model.PiiEntity
import com.example.privacyrouter.model.PiiType
import com.example.privacyrouter.model.RequestLabel
import com.example.privacyrouter.model.Signal

object SensitivityScorer {

    private val BASE_WEIGHTS = mapOf(
        PiiType.HEALTH to 1.00f,
        PiiType.FINANCIAL to 0.95f,
        PiiType.PHONE to 0.90f,
        PiiType.ADDRESS to 0.90f,
        PiiType.EMAIL to 0.80f,
        PiiType.PERSON to 0.75f,
        PiiType.LOCATION to 0.65f,
        PiiType.ORGANIZATION to 0.45f,
        PiiType.DATE_TIME to 0.30f,
        PiiType.MISC to 0.25f,
    )

    private val SIGNAL_BOOSTS = mapOf(
        Signal.HEALTH_CONTEXT to 0.35f,
        Signal.FINANCIAL_CONTEXT to 0.30f,
        Signal.SENSITIVE_ROLE to 0.25f,
        Signal.IMPLICIT_LOCATION to 0.15f,
    )

    private val LABEL_MULTIPLIERS = mapOf(
        RequestLabel.PERSONAL_QUERY to 1.20f,
        RequestLabel.AMBIGUOUS to 1.10f,
        RequestLabel.CONVERSATIONAL to 1.00f,
        RequestLabel.FACTUAL_QUERY to 0.80f,
        RequestLabel.DEVICE_ACTION to 0.00f,
    )

    private const val MAX_SIGNAL_BOOST = 0.50f

    fun compute(
        entities: List<PiiEntity>,
        signals: Set<Signal>,
        label: RequestLabel,
    ): Float {
        val entityScore = aggregateEntityScore(entities)
        val signalBoost = contextualBoost(signals)
        val multiplier = LABEL_MULTIPLIERS.getValue(label)
        return ((entityScore + signalBoost) * multiplier).coerceIn(0.0f, 1.0f)
    }

    private fun aggregateEntityScore(entities: List<PiiEntity>): Float {
        if (entities.isEmpty()) return 0.0f
        val weighted = entities.map { weight(it) }
        val max = weighted.max()
        val rest = weighted.sortedDescending().drop(1).sumOf { (it * 0.15f).toDouble() }.toFloat()
        return (max + rest).coerceAtMost(1.0f)
    }

    private fun weight(entity: PiiEntity): Float =
        BASE_WEIGHTS.getValue(entity.type) * entity.confidence

    private fun contextualBoost(signals: Set<Signal>): Float =
        signals.sumOf { SIGNAL_BOOSTS.getValue(it).toDouble() }
            .toFloat()
            .coerceAtMost(MAX_SIGNAL_BOOST)
}
