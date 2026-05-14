package com.example.privacyrouter.visual

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.privacyrouter.ml.TfLiteLoader
import com.example.privacyrouter.model.BoundingBox
import com.example.privacyrouter.model.VisualDetectionTier
import com.example.privacyrouter.model.VisualPiiEntity
import com.example.privacyrouter.model.VisualPiiType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * YOLOv8-nano TFLite detector for visual PII classes.
 *
 * Expected model: `yolov8n_pii.tflite`
 *   Input:  [1, 640, 640, 3] FLOAT32 (normalized 0..1)
 *   Output: [1, 7, 8400] FLOAT32 (cx, cy, w, h, cls0..cls6 — YOLO transposed format)
 *
 * Class order must match the training export (see training/stage2b_visual/train_yolov8n.py):
 *   0=FACE, 1=DOCUMENT_ID, 2=CARD_PAYMENT, 3=LICENSE_PLATE,
 *   4=SCREEN, 5=MEDICAL_DOC, 6=HANDWRITTEN_FORM
 */
class YoloPiiDetector(
    private val context: Context,
    private val modelAssetPath: String = "yolov8n_pii.tflite",
    private val confidenceThreshold: Float = 0.45f,
    private val nmsThreshold: Float = 0.5f,
    private val inputSize: Int = 640,
) : Closeable {

    private val nnApiDelegate: NnApiDelegate? by lazy {
        runCatching { NnApiDelegate() }.getOrNull()
    }

    private val interpreter: Interpreter? = runCatching {
        val buffer = TfLiteLoader.loadFromAssets(context, modelAssetPath)
        val options = Interpreter.Options().apply {
            nnApiDelegate?.let { addDelegate(it) }
            setNumThreads(2)
        }
        Interpreter(buffer, options)
    }.onFailure { Log.i(TAG, "YOLO asset missing; detector inactive: ${it.message}") }.getOrNull()

    fun detect(bitmap: Bitmap): List<VisualPiiEntity> {
        val engine = interpreter ?: return emptyList()
        return runCatching { runInference(engine, bitmap) }
            .onFailure { Log.w(TAG, "YOLO inference failed", it) }
            .getOrElse { emptyList() }
    }

    private fun runInference(engine: Interpreter, bitmap: Bitmap): List<VisualPiiEntity> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val inputBuffer = bitmapToFloatBuffer(resized)

        // Output shape: [1, NUM_CLASSES+4, 8400]
        val numClasses = CLASS_TYPES.size
        val numBoxes = 8400
        val output = Array(1) { Array(numClasses + 4) { FloatArray(numBoxes) } }
        engine.run(inputBuffer, output)

        val raw = output[0]
        val detections = mutableListOf<VisualPiiEntity>()

        for (i in 0 until numBoxes) {
            val cx = raw[0][i]; val cy = raw[1][i]
            val w = raw[2][i]; val h = raw[3][i]

            var bestClass = -1; var bestScore = confidenceThreshold
            for (c in 0 until numClasses) {
                val score = raw[4 + c][i]
                if (score > bestScore) { bestScore = score; bestClass = c }
            }
            if (bestClass < 0) continue

            detections += VisualPiiEntity(
                type = CLASS_TYPES[bestClass],
                confidence = bestScore,
                source = VisualDetectionTier.TIER_1_YOLO,
                boundingBox = BoundingBox(
                    x = (cx - w / 2f) / inputSize,
                    y = (cy - h / 2f) / inputSize,
                    width = w / inputSize,
                    height = h / inputSize,
                ),
            )
        }

        return nms(detections)
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).apply {
            order(ByteOrder.nativeOrder())
        }
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            buf.putFloat(((pixel shr 16 and 0xFF) / 255f))
            buf.putFloat(((pixel shr 8 and 0xFF) / 255f))
            buf.putFloat(((pixel and 0xFF) / 255f))
        }
        buf.rewind()
        return buf
    }

    private fun nms(detections: List<VisualPiiEntity>): List<VisualPiiEntity> {
        val sorted = detections.sortedByDescending { it.confidence }
        val kept = mutableListOf<VisualPiiEntity>()
        val suppressed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            kept += sorted[i]
            val a = sorted[i].boundingBox ?: continue
            for (j in i + 1 until sorted.size) {
                val b = sorted[j].boundingBox ?: continue
                if (iou(a, b) > nmsThreshold) suppressed[j] = true
            }
        }
        return kept
    }

    private fun iou(a: BoundingBox, b: BoundingBox): Float {
        val ax2 = a.x + a.width; val ay2 = a.y + a.height
        val bx2 = b.x + b.width; val by2 = b.y + b.height
        val ix = maxOf(0f, minOf(ax2, bx2) - maxOf(a.x, b.x))
        val iy = maxOf(0f, minOf(ay2, by2) - maxOf(a.y, b.y))
        val inter = ix * iy
        val union = a.width * a.height + b.width * b.height - inter
        return if (union <= 0f) 0f else inter / union
    }

    override fun close() {
        interpreter?.close()
        nnApiDelegate?.close()
    }

    companion object {
        private const val TAG = "YoloPiiDetector"

        val CLASS_TYPES: Array<VisualPiiType> = arrayOf(
            VisualPiiType.FACE,
            VisualPiiType.DOCUMENT_ID,
            VisualPiiType.CARD_PAYMENT,
            VisualPiiType.LICENSE_PLATE,
            VisualPiiType.SCREEN,
            VisualPiiType.MEDICAL_DOC,
            VisualPiiType.HANDWRITTEN_FORM,
        )
    }
}
