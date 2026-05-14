package com.example.privacyrouterdemo.api

import com.example.privacyrouter.execution.ActionResult
import com.example.privacyrouter.execution.ExecutionResult
import com.example.privacyrouter.model.DetectionTier
import com.example.privacyrouter.model.PiiEntity
import com.example.privacyrouter.model.Signal
import com.example.privacyrouter.model.VisualInput
import com.example.privacyrouterdemo.DemoPipeline
import com.example.privacyrouterdemo.DemoPipelineResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider

data class ProcessRequest(val text: String = "")

data class ProcessResponse(
    val stages: Stages,
    val totalLatencyMs: Long,
    val requestIndex: Long,
)

data class Stages(
    val stage1_classification: ClassificationDto,
    val stage2_piiDetection: PiiDetectionDto,
    val stage3_scoring: ScoringDto,
    val stage3_routing: RoutingDto,
    val execution: ExecutionDto,
)

data class ClassificationDto(
    val tierId: Int,
    val tier: String,
    val label: String,
    val confidence: Float,
    val matchedPattern: String?,
    val classificationLatencyMs: Long,
)

data class PiiDetectionDto(
    val mode: String,
    val entities: List<EntityDto>,
    val signals: List<String>,
    val tiersUsed: List<String>,
    val latencyMs: Long,
    val tier0EntityCount: Int,
    val tier1EntityCount: Int,
    val sharedEntityCount: Int,
    val visualEntityCount: Int,
    val visualEntities: List<VisualEntityDto>,
)

data class EntityDto(
    val text: String,
    val type: String,
    val confidence: Float,
    val source: String,
)

data class VisualEntityDto(
    val type: String,
    val confidence: Float,
    val source: String,
)

data class ScoringDto(
    val entityScore: Float,
    val signalBoost: Float,
    val labelMultiplier: Float,
    val finalScore: Float,
    val scoreZone: String,
)

data class RoutingDto(
    val action: String,
    val sensitivityScore: Float,
    val firedRule: String?,
)

data class ExecutionDto(
    val type: String,
    val body: String?,
    val functionDescription: String?,
    val error: String?,
)

fun Route.processRoute(pipeline: DemoPipeline) {
    post("/api/process") {
        val req = runCatching { call.receive<ProcessRequest>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
            return@post
        }
        if (req.text.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "'text' must not be empty"))
            return@post
        }
        val result = pipeline.process(req.text)
        call.respond(HttpStatusCode.OK, result.toResponse())
    }

    post("/api/process/visual") {
        var text = ""
        var imageBytes: ByteArray? = null
        var mimeType = "image/jpeg"

        call.receiveMultipart().forEachPart { part ->
            when {
                part is PartData.FormItem && part.name == "text" -> text = part.value
                part is PartData.FileItem && part.name == "image" -> {
                    imageBytes = part.streamProvider().use { it.readBytes() }
                    mimeType = part.contentType?.toString() ?: "image/jpeg"
                }
                else -> {}
            }
            part.dispose()
        }

        if (text.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "'text' field must not be empty"))
            return@post
        }

        val visual = imageBytes?.let { VisualInput.ImageBytes(it, mimeType) }
        val result = pipeline.process(text, visual)
        call.respond(HttpStatusCode.OK, result.toResponse())
    }
}

private fun DemoPipelineResult.toResponse() = ProcessResponse(
    stages = Stages(
        stage1_classification = ClassificationDto(
            tierId = classification.tierId,
            tier = when (classification.tierId) { 0 -> "REGEX"; 2 -> "FUNCTION_GEMMA_FALLBACK"; else -> "MOBILEBERT_HEURISTIC" },
            label = classification.label.name,
            confidence = classification.confidence,
            matchedPattern = classification.matchedPattern,
            classificationLatencyMs = classification.classificationLatencyMs,
        ),
        stage2_piiDetection = PiiDetectionDto(
            mode = piiDetection.tiersUsed.toModeLabel(),
            entities = piiDetection.entities.filter { it.source != com.example.privacyrouter.model.DetectionTier.VISUAL }.map { it.toDto() },
            signals = piiDetection.contextualSignals.map(Signal::name),
            tiersUsed = piiDetection.tiersUsed.map(DetectionTier::name),
            latencyMs = piiDetection.detectionLatencyMs,
            tier0EntityCount = piiDetection.tier0EntityCount,
            tier1EntityCount = piiDetection.tier1EntityCount,
            sharedEntityCount = piiDetection.sharedEntityCount,
            visualEntityCount = piiDetection.visualEntityCount,
            visualEntities = piiDetection.entities
                .filter { it.source == com.example.privacyrouter.model.DetectionTier.VISUAL }
                .map { VisualEntityDto(it.text.removePrefix("[visual:").removeSuffix("]").uppercase(), it.confidence, it.source.name) },
        ),
        stage3_scoring = ScoringDto(
            entityScore = scoring.entityScore,
            signalBoost = scoring.signalBoost,
            labelMultiplier = scoring.labelMultiplier,
            finalScore = scoring.finalScore,
            scoreZone = scoring.scoreZone,
        ),
        stage3_routing = RoutingDto(
            action = routing.action.name,
            sensitivityScore = routing.sensitivityScore,
            firedRule = routing.firedRule,
        ),
        execution = execution.toDto(),
    ),
    totalLatencyMs = totalLatencyMs,
    requestIndex = requestIndex,
)

private fun PiiEntity.toDto() = EntityDto(text = text, type = type.name, confidence = confidence, source = source.name)

private fun Set<DetectionTier>.toModeLabel(): String = when {
    isEmpty() -> "SKIP"
    size == 1 && contains(DetectionTier.TIER_0) -> "TIER_0_ONLY"
    contains(DetectionTier.CONTEXTUAL) -> "FULL"
    contains(DetectionTier.VISUAL) && !contains(DetectionTier.CONTEXTUAL) -> "PARALLEL"
    else -> "PARALLEL"
}

private fun ExecutionResult.toDto(): ExecutionDto = when (this) {
    is ExecutionResult.Text -> ExecutionDto("text", body, null, null)
    is ExecutionResult.Action -> when (val r = result) {
        is ActionResult.Success -> ExecutionDto("action", null, r.description, null)
        is ActionResult.Failure -> ExecutionDto("action_error", null, null, r.reason)
        is ActionResult.Unknown -> ExecutionDto("action_unknown", null, null, "Unknown: ${r.function}")
    }
    is ExecutionResult.Error -> ExecutionDto("error", null, null, message)
}
