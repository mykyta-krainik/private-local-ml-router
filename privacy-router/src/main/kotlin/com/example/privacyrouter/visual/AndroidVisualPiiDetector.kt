package com.example.privacyrouter.visual

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.privacyrouter.interfaces.VisualPiiDetectorBackend
import com.example.privacyrouter.model.DetectionTier
import com.example.privacyrouter.model.PiiEntity
import com.example.privacyrouter.model.VisualDetectionTier
import com.example.privacyrouter.model.VisualInput
import com.example.privacyrouter.model.VisualPiiDetectionResult
import com.example.privacyrouter.model.VisualPiiEntity
import com.example.privacyrouter.pipeline.stage2.NerModelDetector
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * Android implementation of [VisualPiiDetectorBackend].
 *
 * Three-tier cascade:
 *   Tier 0 — ML Kit: face detection ([MlKitFaceDetector]) + OCR text extraction
 *             ([MlKitOcrBridge] → fed to existing NER for text-in-image PII).
 *   Tier 1 — YOLO: YOLOv8-nano TFLite ([YoloPiiDetector]) for all 7 visual PII
 *             classes, escalated when Tier 0 fires or the Stage 1 label hints at
 *             personal content.
 *   Tier 2 — Audio: Silero VAD + Whisper Tiny ([AudioPiiDetector]) for video
 *             inputs with an audio payload (pcm16 supplied via [VisualInput.WithAudio]).
 */
class AndroidVisualPiiDetector(
    context: Context,
    private val nerDetector: NerModelDetector? = null,
) : VisualPiiDetectorBackend, Closeable {

    private val faceDetector = MlKitFaceDetector(context)
    private val ocrBridge = MlKitOcrBridge()
    private val yoloDetector = YoloPiiDetector(context)
    private val audioDetector = AudioPiiDetector(context)

    override suspend fun detect(input: VisualInput): VisualPiiDetectionResult {
        val startedAt = System.currentTimeMillis()
        val bitmap = input.toBitmap() ?: return VisualPiiDetectionResult.empty()
        val pcm16 = (input as? VisualInput.WithAudio)?.pcm16

        return withContext(Dispatchers.Default) {
            // Tier 0 — always runs
            val facesDeferred = async { faceDetector.detect(bitmap) }
            val ocrTextDeferred = async { ocrBridge.extractText(bitmap) }

            val faces = facesDeferred.await()
            val ocrText = ocrTextDeferred.await()
            val tier0Fired = faces.isNotEmpty() || ocrText.isNotBlank()

            val ocrEntities: List<PiiEntity> = if (ocrText.isNotBlank() && nerDetector != null) {
                runCatching { nerDetector.detectSync(ocrText) }
                    .onFailure { Log.w(TAG, "NER on OCR text failed", it) }
                    .getOrElse { emptyList() }
            } else emptyList()

            // Tier 1 — YOLO, runs when Tier 0 detected anything
            val yoloEntities: List<VisualPiiEntity> = if (tier0Fired) {
                runCatching { yoloDetector.detect(bitmap) }
                    .onFailure { Log.w(TAG, "YOLO detection failed", it) }
                    .getOrElse { emptyList() }
            } else emptyList()

            // Tier 2 — audio, only when audio payload is present
            val audioResult = if (pcm16 != null) {
                runCatching { audioDetector.detect(pcm16) }
                    .onFailure { Log.w(TAG, "Audio detection failed", it) }
                    .getOrElse { VisualPiiDetectionResult.empty() }
            } else VisualPiiDetectionResult.empty()

            val allVisual = faces + yoloEntities + audioResult.visualEntities
            val allOcrEntities = ocrEntities + audioResult.ocrDerivedTextEntities

            val tiersUsed = buildSet {
                if (faces.isNotEmpty()) add(VisualDetectionTier.TIER_0_FACE)
                if (ocrText.isNotBlank()) add(VisualDetectionTier.TIER_0_OCR)
                if (yoloEntities.isNotEmpty()) add(VisualDetectionTier.TIER_1_YOLO)
                if (audioResult.tiersUsed.isNotEmpty()) addAll(audioResult.tiersUsed)
            }

            VisualPiiDetectionResult(
                visualEntities = allVisual,
                ocrDerivedTextEntities = allOcrEntities,
                latencyMs = System.currentTimeMillis() - startedAt,
                tiersUsed = tiersUsed,
            )
        }
    }

    private fun VisualInput.toBitmap(): Bitmap? = when (this) {
        is VisualInput.ImageBytes -> runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.onFailure { Log.w(TAG, "Failed to decode image bytes", it) }.getOrNull()
        is VisualInput.FilePath -> runCatching {
            BitmapFactory.decodeFile(path)
        }.onFailure { Log.w(TAG, "Failed to decode file: $path", it) }.getOrNull()
        is VisualInput.ImageUrl -> {
            Log.w(TAG, "ImageUrl not supported on Android; use ImageBytes instead")
            null
        }
        is VisualInput.WithAudio -> runCatching {
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        }.onFailure { Log.w(TAG, "Failed to decode image bytes (WithAudio)", it) }.getOrNull()
    }

    override fun close() {
        faceDetector.close()
        ocrBridge.close()
        yoloDetector.close()
        audioDetector.close()
    }

    companion object {
        private const val TAG = "AndroidVisualPiiDetector"
    }
}
