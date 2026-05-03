package com.example.privacyrouter.pipeline.stage1

import com.example.privacyrouter.model.RequestLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactSensitivityGuardTest {

    @Test
    fun `calling therapist upgrades to personal`() {
        val result = ContactSensitivityGuard.guard("call my therapist", RequestLabel.DEVICE_ACTION)
        assertEquals(RequestLabel.PERSONAL_QUERY, result)
    }

    @Test
    fun `non-sensitive contact stays device action`() {
        val result = ContactSensitivityGuard.guard("call my mom", RequestLabel.DEVICE_ACTION)
        assertEquals(RequestLabel.DEVICE_ACTION, result)
    }

    @Test
    fun `guard does not escalate non-device labels`() {
        val result = ContactSensitivityGuard.guard(
            "my therapist said something",
            RequestLabel.FACTUAL_QUERY,
        )
        assertEquals(RequestLabel.FACTUAL_QUERY, result)
    }
}
