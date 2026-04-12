package com.example.privacyrouter

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.privacyrouter.execution.ExecutionResult
import com.example.privacyrouter.model.RawInput
import com.example.privacyrouter.pipeline.PipelineResult
import com.example.privacyrouter.pipeline.PrivacyRouterPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PrivacyRouterSession(context: Context) : VoiceInteractionSession(context) {

    private val job: Job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var recognizer: SpeechRecognizer? = null

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startListening()
    }

    override fun onHide() {
        recognizer?.destroy()
        recognizer = null
        super.onHide()
    }

    private fun startListening() {
        val sr = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val transcript = results
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                dispatch(transcript)
            }

            override fun onError(error: Int) {
                Log.w(TAG, "SpeechRecognizer error: $error")
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        sr.startListening(intent)
    }

    fun dispatch(transcript: String) {
        val pipeline = resolvePipeline() ?: run {
            Log.w(TAG, "pipeline not ready")
            return
        }
        val input = RawInput(transcript = transcript, timestampMs = System.currentTimeMillis())
        scope.launch {
            val result = pipeline.process(input)
            onPipelineResult(result)
        }
    }

    private fun resolvePipeline(): PrivacyRouterPipeline? =
        pipelineOverride ?: PrivacyRouterVoiceService.sharedPipeline

    /** Test-only injection point. */
    var pipelineOverride: PrivacyRouterPipeline? = null

    private fun onPipelineResult(result: PipelineResult) {
        Log.i(
            TAG,
            "label=${result.classification.label} action=${result.routing.action} " +
                "score=${result.routing.sensitivityScore} latencyMs=${result.totalLatencyMs}",
        )
        when (val exec = result.execution) {
            is ExecutionResult.Text -> Log.i(TAG, "response: ${exec.body.take(200)}")
            is ExecutionResult.Action -> Log.i(TAG, "action result: ${exec.result}")
            is ExecutionResult.Error -> Log.w(TAG, "execution error: ${exec.message}")
        }
    }

    override fun onDestroy() {
        job.cancel()
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PrivacyRouterSession"
    }
}
