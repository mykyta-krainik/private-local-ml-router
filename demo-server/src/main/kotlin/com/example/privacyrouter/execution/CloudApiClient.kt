package com.example.privacyrouter.execution

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class CloudApiClient(
    private val endpoint: String,
    private val apiKey: String,
    private val client: OkHttpClient = defaultClient(),
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun complete(prompt: String): String = withContext(Dispatchers.IO) {
        val body = """{"prompt":${prompt.toJsonString()}}""".toRequestBody(jsonMedia)
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Cloud API HTTP ${response.code}: ${response.message}")
            }
            response.body?.string().orEmpty()
        }
    }

    private fun String.toJsonString(): String = buildString(length + 2) {
        append('"')
        for (ch in this@toJsonString) when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
        append('"')
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
