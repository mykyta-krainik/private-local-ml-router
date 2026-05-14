package com.example.privacyrouter.interfaces

import com.example.privacyrouter.execution.ActionResult
import com.example.privacyrouter.execution.FunctionCall
import com.example.privacyrouter.model.PiiEntity
import com.example.privacyrouter.model.RequestLabel
import com.example.privacyrouter.model.VisualInput
import com.example.privacyrouter.model.VisualPiiDetectionResult

interface TextClassifierBackend {
    suspend fun detect(text: String): List<PiiEntity>
}

interface NerDetectorBackend {
    suspend fun detect(text: String): List<PiiEntity>
}

interface RequestClassifierBackend {
    suspend fun classify(text: String): Pair<RequestLabel, Float>
}

interface LlmBackend {
    suspend fun generate(prompt: String): String
}

interface FunctionCallingBackend {
    suspend fun resolveAction(query: String): FunctionCall
    suspend fun classifyRequest(query: String): FunctionCall
}

interface ActionBackend {
    fun execute(call: FunctionCall): ActionResult
}

interface VisualPiiDetectorBackend {
    suspend fun detect(input: VisualInput): VisualPiiDetectionResult
}
