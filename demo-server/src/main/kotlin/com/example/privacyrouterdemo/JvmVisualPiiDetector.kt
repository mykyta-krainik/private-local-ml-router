package com.example.privacyrouterdemo

import com.example.privacyrouter.interfaces.VisualPiiDetectorBackend
import com.example.privacyrouter.model.BoundingBox
import com.example.privacyrouter.model.VisualDetectionTier
import com.example.privacyrouter.model.VisualInput
import com.example.privacyrouter.model.VisualPiiDetectionResult
import com.example.privacyrouter.model.VisualPiiEntity
import com.example.privacyrouter.model.VisualPiiType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * JVM visual PII detector stub.
 *
 * When VISUAL_DETECTION_URL is set, posts the image as base64 JSON to:
 *   POST $VISUAL_DETECTION_URL/detect
 *   { "image_base64": "...", "mime_type": "image/jpeg" }
 *
 * Expected response:
 *   { "detections": [{ "type": "FACE", "confidence": 0.91, "x": 0.1, "y": 0.2, "w": 0.15, "h": 0.2 }] }
 *
 * Returns empty result if the env var is unset or the call fails.
 */
class JvmVisualPiiDetector(
    private val detectionUrl: String? = System.getenv("VISUAL_DETECTION_URL"),
) : VisualPiiDetectorBackend {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override suspend fun detect(input: VisualInput): VisualPiiDetectionResult {
        val url = detectionUrl ?: return VisualPiiDetectionResult.empty()
        val start = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            runCatching {
                val (b64, mime) = toBase64(input)
                val body = """{"image_base64":"$b64","mime_type":"$mime"}""".toRequestBody(jsonMedia)
                val req = Request.Builder().url("$url/detect").post(body).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use VisualPiiDetectionResult.empty()
                    val raw = resp.body?.string() ?: return@use VisualPiiDetectionResult.empty()
                    parseDetections(raw, System.currentTimeMillis() - start)
                }
            }.getOrDefault(VisualPiiDetectionResult.empty())
        }
    }

    private fun toBase64(input: VisualInput): Pair<String, String> = when (input) {
        is VisualInput.ImageBytes -> Base64.getEncoder().encodeToString(input.bytes) to input.mimeType
        is VisualInput.FilePath -> {
            val bytes = java.io.File(input.path).readBytes()
            val mime = when {
                input.path.endsWith(".png", true) -> "image/png"
                input.path.endsWith(".gif", true) -> "image/gif"
                else -> "image/jpeg"
            }
            Base64.getEncoder().encodeToString(bytes) to mime
        }
        is VisualInput.ImageUrl -> "" to "image/jpeg"
        is VisualInput.WithAudio -> Base64.getEncoder().encodeToString(input.imageBytes) to input.mimeType
    }

    private fun parseDetections(raw: String, latencyMs: Long): VisualPiiDetectionResult {
        val entities = mutableListOf<VisualPiiEntity>()
        val pattern = Regex(""""type"\s*:\s*"(\w+)"[^}]*"confidence"\s*:\s*([\d.]+)(?:[^}]*"x"\s*:\s*([\d.]+)[^}]*"y"\s*:\s*([\d.]+)[^}]*"w"\s*:\s*([\d.]+)[^}]*"h"\s*:\s*([\d.]+))?""")
        for (match in pattern.findAll(raw)) {
            val type = runCatching { VisualPiiType.valueOf(match.groupValues[1]) }.getOrNull() ?: continue
            val confidence = match.groupValues[2].toFloatOrNull() ?: continue
            val bbox = if (match.groupValues[3].isNotEmpty()) BoundingBox(
                x = match.groupValues[3].toFloat(),
                y = match.groupValues[4].toFloat(),
                width = match.groupValues[5].toFloat(),
                height = match.groupValues[6].toFloat(),
            ) else null
            entities += VisualPiiEntity(type = type, confidence = confidence, source = VisualDetectionTier.TIER_1_YOLO, boundingBox = bbox)
        }
        return VisualPiiDetectionResult(
            visualEntities = entities,
            ocrDerivedTextEntities = emptyList(),
            latencyMs = latencyMs,
            tiersUsed = if (entities.isNotEmpty()) setOf(VisualDetectionTier.TIER_1_YOLO) else emptySet(),
        )
    }
}
