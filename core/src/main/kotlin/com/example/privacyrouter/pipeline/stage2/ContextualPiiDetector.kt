package com.example.privacyrouter.pipeline.stage2

import com.example.privacyrouter.model.Signal

object ContextualPiiDetector {

    private val sensitiveRoles = Regex(
        "my (doctor|therapist|lawyer|accountant|sponsor|psychiatrist|counselor|broker|pastor)",
        RegexOption.IGNORE_CASE,
    )
    private val implicitLocation = Regex(
        "at (home|work|the office|my usual place|my place)",
        RegexOption.IGNORE_CASE,
    )

    private val healthKeywords = listOf(
        "pain", "symptom", "diagnosis", "prescription", "medication",
        "dosage", "blood pressure", "anxiety", "depression", "therapy",
    )

    private val financialKeywords = listOf(
        "balance", "transfer", "deposit", "withdraw", "loan",
        "mortgage", "credit card", "debit", "salary", "invoice",
    )

    fun detect(query: String): Set<Signal> {
        val signals = mutableSetOf<Signal>()
        if (sensitiveRoles.containsMatchIn(query)) signals += Signal.SENSITIVE_ROLE
        if (implicitLocation.containsMatchIn(query)) signals += Signal.IMPLICIT_LOCATION
        if (healthKeywords.any { query.contains(it, ignoreCase = true) }) signals += Signal.HEALTH_CONTEXT
        if (financialKeywords.any { query.contains(it, ignoreCase = true) }) signals += Signal.FINANCIAL_CONTEXT
        return signals
    }
}
