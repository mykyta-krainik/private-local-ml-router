package com.example.privacyrouter.policy

import com.example.privacyrouter.model.EntityRule
import com.example.privacyrouter.model.PiiType
import com.example.privacyrouter.model.PolicyConfig
import com.example.privacyrouter.model.ScoreThresholds
import com.example.privacyrouter.model.Signal
import com.example.privacyrouter.model.SignalRule
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.InputStream

class PolicyConfigLoader {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @OptIn(ExperimentalStdlibApi::class)
    private val adapter = moshi.adapter<Dto>()

    fun load(json: String): PolicyConfig = fromDto(
        adapter.fromJson(json) ?: error("Invalid policy JSON")
    )

    fun load(stream: InputStream): PolicyConfig = stream.bufferedReader().use { load(it.readText()) }

    fun loadFromFile(file: File): PolicyConfig = file.inputStream().use { load(it) }

    private fun fromDto(dto: Dto): PolicyConfig = PolicyConfig(
        version = dto.version,
        defaultAction = dto.defaultAction,
        scoreThresholds = ScoreThresholds(
            local = dto.scoreThresholds.local,
            redactThenCloud = dto.scoreThresholds.redact_then_cloud,
            cloud = dto.scoreThresholds.cloud,
        ),
        entityRules = dto.entityRules.map { EntityRule(PiiType.valueOf(it.type), it.action, it.override) },
        signalRules = dto.signalRules.map { SignalRule(Signal.valueOf(it.signal), it.action, it.override) },
        allowList = dto.allowList,
        denyList = dto.denyList,
    )

    @JsonClass(generateAdapter = false)
    internal data class Dto(
        val version: Int,
        val defaultAction: String,
        val scoreThresholds: ThresholdsDto,
        val entityRules: List<EntityRuleDto>,
        val signalRules: List<SignalRuleDto>,
        val allowList: List<String>,
        val denyList: List<String>,
    )

    @JsonClass(generateAdapter = false)
    internal data class ThresholdsDto(val local: Float, val redact_then_cloud: Float, val cloud: Float)

    @JsonClass(generateAdapter = false)
    internal data class EntityRuleDto(val type: String, val action: String, val override: Boolean)

    @JsonClass(generateAdapter = false)
    internal data class SignalRuleDto(val signal: String, val action: String, val override: Boolean)
}
