package com.example.privacyrouter

import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class PrivacyRouterSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: android.os.Bundle?): VoiceInteractionSession =
        PrivacyRouterSession(this)
}
