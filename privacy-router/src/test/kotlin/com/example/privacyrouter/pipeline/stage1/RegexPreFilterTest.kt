package com.example.privacyrouter.pipeline.stage1

import com.example.privacyrouter.model.RequestLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegexPreFilterTest {

    @Test
    fun `device action queries match`() {
        val samples = listOf(
            "set a timer for 5 minutes",
            "Set alarm for 7am",
            "turn on the flashlight",
            "add a calendar event for dinner",
            "call my mom",
            "play lo-fi on Spotify",
            "remind me to buy milk",
        )
        samples.forEach {
            assertEquals(
                "expected DEVICE_ACTION for: $it",
                RequestLabel.DEVICE_ACTION, RegexPreFilter.match(it),
            )
        }
    }

    @Test
    fun `non-device queries do not match`() {
        val samples = listOf(
            "what is the capital of France",
            "tell me about my health",
            "how are you today",
        )
        samples.forEach { assertNull(RegexPreFilter.match(it)) }
    }
}
