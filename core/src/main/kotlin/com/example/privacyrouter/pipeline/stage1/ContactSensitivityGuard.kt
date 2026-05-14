package com.example.privacyrouter.pipeline.stage1

import com.example.privacyrouter.model.RequestLabel

object ContactSensitivityGuard {

    private val sensitiveRoles = setOf(
        "therapist", "doctor", "lawyer", "psychiatrist",
        "counselor", "sponsor", "broker", "accountant", "pastor",
    )

    fun guard(query: String, label: RequestLabel): RequestLabel {
        if (label == RequestLabel.DEVICE_ACTION &&
            sensitiveRoles.any { query.contains(it, ignoreCase = true) }
        ) {
            return RequestLabel.PERSONAL_QUERY
        }
        return label
    }
}
