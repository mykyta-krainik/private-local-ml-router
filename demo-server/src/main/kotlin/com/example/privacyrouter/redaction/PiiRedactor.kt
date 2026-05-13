package com.example.privacyrouter.redaction

import com.example.privacyrouter.model.PiiEntity

data class RedactedQuery(
    val redacted: String,
    val mapping: Map<String, String>,
)

class PiiRedactor {

    fun redact(query: String, entities: List<PiiEntity>): RedactedQuery {
        if (entities.isEmpty()) return RedactedQuery(query, emptyMap())

        val mapping = linkedMapOf<String, String>()
        val sb = StringBuilder(query)
        val sorted = entities.sortedByDescending { it.span.first }
        var counter = entities.size

        for (entity in sorted) {
            val token = "[${entity.type}_$counter]"
            counter--
            mapping[token] = entity.text
            val start = entity.span.first.coerceAtLeast(0)
            val endExclusive = (entity.span.last + 1).coerceAtMost(sb.length)
            if (start < endExclusive) {
                sb.replace(start, endExclusive, token)
            }
        }
        return RedactedQuery(sb.toString(), mapping.toMap())
    }

    fun restore(response: String, mapping: Map<String, String>): String {
        var restored = response
        for ((token, original) in mapping) {
            restored = restored.replace(token, original)
        }
        return restored
    }
}
