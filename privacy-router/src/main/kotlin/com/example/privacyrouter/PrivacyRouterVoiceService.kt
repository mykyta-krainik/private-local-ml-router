package com.example.privacyrouter

import android.service.voice.VoiceInteractionService
import android.util.Log
import com.example.privacyrouter.pipeline.PrivacyRouterPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class PrivacyRouterVoiceService : VoiceInteractionService() {

    private val job = SupervisorJob()
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + job)

    lateinit var pipeline: PrivacyRouterPipeline
        private set

    override fun onCreate() {
        super.onCreate()
        pipeline = PrivacyRouterPipeline.build(this)
        sharedPipeline = pipeline
        // TODO: warm up downstream models here (classifier, NER, FunctionGemma,
        //  local LLM) to avoid cold-start latency on first user request.
        Log.i(TAG, "PrivacyRouterVoiceService ready")
    }

    override fun onDestroy() {
        sharedPipeline = null
        job.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PrivacyRouterVoice"

        @Volatile
        var sharedPipeline: PrivacyRouterPipeline? = null
            private set
    }
}
