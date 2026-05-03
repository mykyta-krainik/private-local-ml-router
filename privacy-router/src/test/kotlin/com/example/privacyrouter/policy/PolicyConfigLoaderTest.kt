package com.example.privacyrouter.policy

import com.example.privacyrouter.model.PiiType
import com.example.privacyrouter.model.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyConfigLoaderTest {

    private val json = """
    {
      "version": 2,
      "defaultAction": "cloud",
      "scoreThresholds": { "local": 0.8, "redact_then_cloud": 0.4, "cloud": 0.0 },
      "entityRules": [
        { "type": "HEALTH", "action": "route_local", "override": true },
        { "type": "PERSON", "action": "redact_then_cloud", "override": false }
      ],
      "signalRules": [
        { "signal": "HEALTH_CONTEXT", "action": "route_local", "override": true }
      ],
      "allowList": ["weather"],
      "denyList": ["my bank"]
    }
    """.trimIndent()

    @Test
    fun `loader parses the plan's JSON shape`() {
        val config = PolicyConfigLoader().load(json)
        assertEquals(2, config.version)
        assertEquals("cloud", config.defaultAction)
        assertEquals(0.8f, config.scoreThresholds.local, 1e-4f)
        assertEquals(0.4f, config.scoreThresholds.redactThenCloud, 1e-4f)
        assertEquals(2, config.entityRules.size)
        assertTrue(config.entityRules.any { it.type == PiiType.HEALTH && it.override })
        assertEquals(Signal.HEALTH_CONTEXT, config.signalRules.single().signal)
        assertEquals(listOf("weather"), config.allowList)
        assertEquals(listOf("my bank"), config.denyList)
    }
}
