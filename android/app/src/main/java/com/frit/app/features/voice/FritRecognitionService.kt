package com.frit.app.features.voice

import android.speech.RecognitionService
import android.content.Intent
import android.speech.SpeechRecognizer

/**
 * Empty implementation to satisfy VoiceInteractionService requirements.
 * Actual voice processing is handled in VoiceEngine.
 */
class FritRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
