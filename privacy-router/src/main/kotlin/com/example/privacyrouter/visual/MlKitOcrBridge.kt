package com.example.privacyrouter.visual

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable

/**
 * Wraps ML Kit Text Recognition v2 to extract all text from an image frame.
 * The returned string is fed into the existing NER/regex text pipeline so
 * OCR-derived PII entities flow through the normal Stage 2 path.
 */
class MlKitOcrBridge : Closeable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun extractText(bitmap: Bitmap): String = try {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = Tasks.await(recognizer.process(image))
        result.textBlocks.joinToString(" ") { it.text }
    } catch (e: Exception) {
        Log.w(TAG, "OCR failed", e)
        ""
    }

    override fun close() {
        runCatching { recognizer.close() }
    }

    companion object {
        private const val TAG = "MlKitOcrBridge"
    }
}
