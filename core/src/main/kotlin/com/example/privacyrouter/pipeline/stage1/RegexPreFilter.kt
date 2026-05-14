package com.example.privacyrouter.pipeline.stage1

import com.example.privacyrouter.model.RequestLabel

object RegexPreFilter {

    private data class PatternEntry(val pattern: Regex, val label: RequestLabel, val description: String)

    private val patterns = listOf(
        PatternEntry(Regex("set (a )?(?:timer|alarm)", RegexOption.IGNORE_CASE), RequestLabel.DEVICE_ACTION, "set timer/alarm"),
        PatternEntry(Regex("turn (on|off) (?:the )?(?:flashlight|wifi|bluetooth)", RegexOption.IGNORE_CASE), RequestLabel.DEVICE_ACTION, "toggle device"),
        PatternEntry(Regex("add (?:a )?(?:calendar )?event", RegexOption.IGNORE_CASE), RequestLabel.DEVICE_ACTION, "add calendar event"),
        PatternEntry(Regex("call (?:my )?\\w+", RegexOption.IGNORE_CASE), RequestLabel.DEVICE_ACTION, "make call"),
        PatternEntry(Regex("play .+ (?:on|via) \\w+", RegexOption.IGNORE_CASE), RequestLabel.DEVICE_ACTION, "play media"),
        PatternEntry(Regex("send (?:a )?(?:sms|text|message) to", RegexOption.IGNORE_CASE), RequestLabel.DEVICE_ACTION, "send sms"),
        PatternEntry(Regex("remind me to", RegexOption.IGNORE_CASE), RequestLabel.DEVICE_ACTION, "set reminder"),
    )

    /** Returns the matched label and the pattern description, or null if no match. */
    fun match(query: String): Pair<RequestLabel, String>? {
        for (entry in patterns) {
            if (entry.pattern.containsMatchIn(query)) return entry.label to entry.description
        }
        return null
    }
}
