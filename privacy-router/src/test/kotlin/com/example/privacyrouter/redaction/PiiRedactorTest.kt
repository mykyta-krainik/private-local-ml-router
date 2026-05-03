package com.example.privacyrouter.redaction

import com.example.privacyrouter.model.DetectionTier
import com.example.privacyrouter.model.PiiEntity
import com.example.privacyrouter.model.PiiType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiiRedactorTest {

    private val redactor = PiiRedactor()

    @Test
    fun `redaction replaces spans with typed tokens`() {
        val query = "Email alice@example.com about the meeting"
        val entity = PiiEntity(
            span = 6..22,
            text = "alice@example.com",
            type = PiiType.EMAIL,
            confidence = 0.95f,
            source = DetectionTier.TIER_0,
        )
        val result = redactor.redact(query, listOf(entity))
        assertTrue(result.redacted.contains("[EMAIL_"))
        assertEquals("alice@example.com", result.mapping.values.single())
    }

    @Test
    fun `restore undoes redaction`() {
        val query = "Call Bob at 555-1234"
        val entities = listOf(
            PiiEntity(5..7, "Bob", PiiType.PERSON, 0.9f, DetectionTier.TIER_1),
            PiiEntity(12..19, "555-1234", PiiType.PHONE, 0.85f, DetectionTier.TIER_0),
        )
        val redacted = redactor.redact(query, entities)
        val restored = redactor.restore(redacted.redacted, redacted.mapping)
        assertEquals(query, restored)
    }

    @Test
    fun `empty entity list is a no-op`() {
        val result = redactor.redact("hello world", emptyList())
        assertEquals("hello world", result.redacted)
        assertTrue(result.mapping.isEmpty())
    }
}
