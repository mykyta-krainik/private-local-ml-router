package com.example.privacyrouter.pipeline.stage1

import com.example.privacyrouter.model.RequestLabel

object RegexPreFilter {

    private val deviceActionPatterns = listOf(
        Regex("set (a )?(?:timer|alarm)", RegexOption.IGNORE_CASE),
        Regex("turn (on|off) (?:the )?(?:flashlight|wifi|bluetooth)", RegexOption.IGNORE_CASE),
        Regex("add (?:a )?(?:calendar )?event", RegexOption.IGNORE_CASE),
        Regex("call (?:my )?\\w+", RegexOption.IGNORE_CASE),
        Regex("play .+ (?:on|via) \\w+", RegexOption.IGNORE_CASE),
        Regex("send (?:a )?(?:sms|text|message) to", RegexOption.IGNORE_CASE),
        Regex("remind me to", RegexOption.IGNORE_CASE),
    )

    fun match(query: String): RequestLabel? {
        if (deviceActionPatterns.any { it.containsMatchIn(query) }) {
            return RequestLabel.DEVICE_ACTION
        }
        return null
    }
}
