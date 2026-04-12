package com.example.privacyrouter.model

data class ScoreThresholds(
    val local: Float,
    val redactThenCloud: Float,
    val cloud: Float,
)

data class EntityRule(
    val type: PiiType,
    val action: String,
    val override: Boolean,
)

data class SignalRule(
    val signal: Signal,
    val action: String,
    val override: Boolean,
)

data class PolicyConfig(
    val version: Int,
    val defaultAction: String,
    val scoreThresholds: ScoreThresholds,
    val entityRules: List<EntityRule>,
    val signalRules: List<SignalRule>,
    val allowList: List<String>,
    val denyList: List<String>,
) {
    companion object {
        fun default(): PolicyConfig = PolicyConfig(
            version = 1,
            defaultAction = "redact_then_cloud",
            scoreThresholds = ScoreThresholds(local = 0.70f, redactThenCloud = 0.35f, cloud = 0.0f),
            entityRules = listOf(
                EntityRule(PiiType.HEALTH, "route_local", override = true),
                EntityRule(PiiType.FINANCIAL, "route_local", override = true),
                EntityRule(PiiType.PERSON, "redact_then_cloud", override = false),
                EntityRule(PiiType.LOCATION, "cloud", override = false),
            ),
            signalRules = listOf(
                SignalRule(Signal.HEALTH_CONTEXT, "route_local", override = true),
                SignalRule(Signal.FINANCIAL_CONTEXT, "route_local", override = true),
            ),
            allowList = listOf("weather", "news", "translation"),
            denyList = listOf("my doctor", "my bank", "my therapist"),
        )
    }
}
