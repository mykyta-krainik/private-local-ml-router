package com.example.privacyrouter.execution

import com.example.privacyrouter.interfaces.LlmBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** JVM Path B engine — calls an Ollama-compatible OpenAI chat/completions endpoint. */
class LocalLlmEngine(
    private val ollamaUrl: String = System.getenv("OLLAMA_URL") ?: "http://localhost:11434",
    private val model: String = System.getenv("OLLAMA_MODEL") ?: "gemma2:4b",
) : LlmBackend {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val body = """
            {"model":"$model","messages":[{"role":"user","content":${prompt.jsonString()}}],"stream":false}
        """.trimIndent().toRequestBody(json)

        val request = Request.Builder()
            .url("$ollamaUrl/v1/chat/completions")
            .post(body)
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching stub(prompt)
                val raw = response.body?.string() ?: return@runCatching stub(prompt)
                Regex(""""content"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                    .find(raw)?.groupValues?.get(1)?.unescape() ?: stub(prompt)
            }
        }.getOrDefault(stub(prompt))
    }

    private fun stub(prompt: String) = "[local-llm stub — Ollama not reachable] ${prompt.take(100)}"

    private fun String.jsonString(): String = buildString(length + 2) {
        append('"')
        for (ch in this@jsonString) when (ch) {
            '\\' -> append("\\\\"); '"' -> append("\\\"")
            '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> append(ch)
        }
        append('"')
    }

    private fun String.unescape(): String =
        replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")
}
