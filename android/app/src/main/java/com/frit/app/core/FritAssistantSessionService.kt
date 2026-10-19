package com.frit.app.core

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.frit.app.features.voice.FritVoiceOverlayService

class FritAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(bundle: Bundle?): VoiceInteractionSession {
        return FritAssistantSession(this)
    }

    class FritAssistantSession(context: android.content.Context) : VoiceInteractionSession(context) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            // When the assistant is triggered (e.g. long press home), start our overlay
            FritVoiceOverlayService.start(context)
            finish() // Finish this session so the overlay service takes over
        }
    }
}
