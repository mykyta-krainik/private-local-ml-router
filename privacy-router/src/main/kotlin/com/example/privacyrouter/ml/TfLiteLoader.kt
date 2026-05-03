package com.example.privacyrouter.ml

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

object TfLiteLoader {
    /**
     * Memory-maps a .tflite model from assets/ into a direct ByteBuffer suitable for
     * `org.tensorflow.lite.Interpreter`.
     */
    fun loadFromAssets(context: Context, assetPath: String): ByteBuffer {
        val afd = context.assets.openFd(assetPath)
        return afd.use { descriptor ->
            val inputStream = java.io.FileInputStream(descriptor.fileDescriptor)
            val channel = inputStream.channel
            channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
                .order(ByteOrder.nativeOrder())
        }
    }
}
