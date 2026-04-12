package com.example.privacyrouter.execution

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract

class ActionExecutor(private val context: Context) {

    fun execute(call: FunctionCall): ActionResult = try {
        when (call.function) {
            "create_calendar_event" -> createCalendarEvent(call.args)
            "set_alarm" -> setAlarm(call.args)
            "set_timer" -> setTimer(call.args)
            "make_phone_call" -> makePhoneCall(call.args)
            "send_sms" -> sendSms(call.args)
            "toggle_flashlight" -> ActionResult.Failure("flashlight requires CameraManager wiring")
            else -> ActionResult.Unknown(call.function)
        }
    } catch (t: Throwable) {
        ActionResult.Failure(t.message ?: t::class.simpleName ?: "unknown error")
    }

    private fun startActivity(intent: Intent): ActionResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ActionResult.Success
    }

    private fun createCalendarEvent(args: Map<String, Any?>): ActionResult {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            args["title"]?.let { putExtra(CalendarContract.Events.TITLE, it.toString()) }
            args["description"]?.let {
                putExtra(CalendarContract.Events.DESCRIPTION, it.toString())
            }
            args["location"]?.let {
                putExtra(CalendarContract.Events.EVENT_LOCATION, it.toString())
            }
            (args["beginMs"] as? Long)?.let {
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it)
            }
        }
        return startActivity(intent)
    }

    private fun setAlarm(args: Map<String, Any?>): ActionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            (args["hour"] as? Int)?.let { putExtra(AlarmClock.EXTRA_HOUR, it) }
            (args["minute"] as? Int)?.let { putExtra(AlarmClock.EXTRA_MINUTES, it) }
            args["message"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it.toString()) }
        }
        return startActivity(intent)
    }

    private fun setTimer(args: Map<String, Any?>): ActionResult {
        val amount = (args["amount"] as? Int) ?: 5
        val unit = (args["unit"] as? String) ?: "minute"
        val seconds = when (unit.lowercase()) {
            "second", "seconds" -> amount
            "hour", "hours" -> amount * 3600
            else -> amount * 60
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        return startActivity(intent)
    }

    private fun makePhoneCall(args: Map<String, Any?>): ActionResult {
        val number = args["number"]?.toString() ?: return ActionResult.Failure("missing number")
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
        return startActivity(intent)
    }

    private fun sendSms(args: Map<String, Any?>): ActionResult {
        val number = args["number"]?.toString() ?: ""
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
            args["body"]?.let { putExtra("sms_body", it.toString()) }
        }
        return startActivity(intent)
    }
}
