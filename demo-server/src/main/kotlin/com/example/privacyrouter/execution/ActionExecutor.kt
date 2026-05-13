package com.example.privacyrouter.execution

/** JVM stub — describes the device action that would fire on a real Android device. */
class ActionExecutor {

    fun execute(call: FunctionCall): ActionResult {
        val description = when (call.function) {
            "set_timer" -> "Set timer: ${call.args["amount"]} ${call.args["unit"]}(s)"
            "set_alarm" -> "Set alarm: ${call.args["hour"]}:${
                call.args["minute"].toString().padStart(2, '0')} ${call.args["meridiem"]}"
            "create_calendar_event" -> "Create calendar event: \"${call.args["title"]}\""
            "make_phone_call" -> "Call contact: ${call.args["contact"] ?: call.args["number"]}"
            "send_sms" -> "Send SMS: \"${call.args["body"]}\""
            "toggle_flashlight" -> "Toggle flashlight ${if (call.args["on"] == true) "ON" else "OFF"}"
            "unknown" -> "Unrecognised action: ${call.args["raw"]}"
            else -> "${call.function}(${call.args.entries.joinToString { "${it.key}=${it.value}" }})"
        }
        return ActionResult.Success(description)
    }
}
