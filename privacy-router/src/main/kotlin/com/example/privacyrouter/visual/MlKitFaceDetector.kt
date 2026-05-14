package com.example.privacyrouter.visual

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.privacyrouter.model.BoundingBox
import com.example.privacyrouter.model.VisualDetectionTier
import com.example.privacyrouter.model.VisualPiiEntity
import com.example.privacyrouter.model.VisualPiiType
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.Closeable

class MlKitFaceDetector(context: Context) : Closeable {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.1f)
            .build(),
    )

    fun detect(bitmap: Bitmap): List<VisualPiiEntity> = try {
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces = Tasks.await(detector.process(image))
        faces.map { face ->
            val bounds = face.boundingBox
            val bw = bitmap.width.toFloat()
            val bh = bitmap.height.toFloat()
            VisualPiiEntity(
                type = VisualPiiType.FACE,
                confidence = face.trackingId?.let { 0.92f } ?: 0.85f,
                source = VisualDetectionTier.TIER_0_FACE,
                boundingBox = BoundingBox(
                    x = bounds.left / bw,
                    y = bounds.top / bh,
                    width = bounds.width() / bw,
                    height = bounds.height() / bh,
                ),
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "Face detection failed", e)
        emptyList()
    }

    override fun close() {
        runCatching { detector.close() }
    }

    companion object {
        private const val TAG = "MlKitFaceDetector"
    }
}
