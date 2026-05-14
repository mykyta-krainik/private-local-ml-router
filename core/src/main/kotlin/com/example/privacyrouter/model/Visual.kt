package com.example.privacyrouter.model

enum class VisualPiiType {
    FACE,
    DOCUMENT_ID,
    CARD_PAYMENT,
    LICENSE_PLATE,
    SCREEN,
    MEDICAL_DOC,
    HANDWRITTEN_FORM;

    fun toPiiType(): PiiType = when (this) {
        FACE -> PiiType.PERSON
        DOCUMENT_ID -> PiiType.MISC
        CARD_PAYMENT -> PiiType.FINANCIAL
        LICENSE_PLATE -> PiiType.MISC
        SCREEN -> PiiType.MISC
        MEDICAL_DOC -> PiiType.HEALTH
        HANDWRITTEN_FORM -> PiiType.MISC
    }
}

enum class VisualDetectionTier {
    TIER_0_FACE,
    TIER_0_OCR,
    TIER_1_YOLO,
    TIER_2_AUDIO,
}

data class BoundingBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

data class VisualPiiEntity(
    val type: VisualPiiType,
    val confidence: Float,
    val source: VisualDetectionTier,
    val boundingBox: BoundingBox? = null,
) {
    fun toPiiEntity(): PiiEntity = PiiEntity(
        span = 0..0,
        text = "[visual:${type.name.lowercase()}]",
        type = type.toPiiType(),
        confidence = confidence,
        source = DetectionTier.VISUAL,
    )
}

data class VisualPiiDetectionResult(
    val visualEntities: List<VisualPiiEntity>,
    val ocrDerivedTextEntities: List<PiiEntity>,
    val latencyMs: Long,
    val tiersUsed: Set<VisualDetectionTier>,
) {
    companion object {
        fun empty() = VisualPiiDetectionResult(emptyList(), emptyList(), 0L, emptySet())
    }
}

sealed class VisualInput {
    data class ImageBytes(val bytes: ByteArray, val mimeType: String) : VisualInput()
    data class ImageUrl(val url: String) : VisualInput()
    data class FilePath(val path: String) : VisualInput()
    /** Video frame + audio track for Tier 2 (Silero VAD + Whisper) processing. */
    data class WithAudio(val imageBytes: ByteArray, val mimeType: String, val pcm16: ShortArray) : VisualInput()
}
