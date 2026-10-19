package com.frit.app.features.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.frit.app.core.Diag
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * OfflineSttEngine — hybrid on-device speech-to-text.
 *
 * Tier 1: Vosk offline STT engine (bundled tiny/small model vosk-model-small-en-us-0.15)
 * Tier 2: Android system SpeechRecognizer with EXTRA_PREFER_OFFLINE
 */
object OfflineSttEngine {

    fun isAvailable(context: Context): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context) || VoskSttEngine.isModelReady(context)

    suspend fun transcribePcm(
        context: Context,
        shorts: ShortArray,
        sampleRate: Int = 16000
    ): String? {
        // 1. System SpeechRecognizer — acoustic models adapt to the user's
        //    accent (Google's English covers Nigerian English far better than
        //    Vosk's US-only small model) and it returns a no-match error on
        //    silence instead of filler garbage.
        val system = try { transcribeOnce(context) } catch (e: Exception) { Diag.warn("OfflineStt", "system_engine", e); null }
        if (!system.isNullOrBlank() && VoiceEngine.isMeaningfulTranscript(system)) return system

        // 2. Vosk bundled model (last resort, fully offline)
        val voskResult = try { VoskSttEngine.transcribePcm(context, shorts, sampleRate) } catch (e: Exception) { Diag.warn("OfflineStt", "vosk_engine", e); null }
        if (!voskResult.isNullOrBlank() && VoiceEngine.isMeaningfulTranscript(voskResult)) return voskResult
        return null
    }

    suspend fun transcribeOnce(
        context: Context,
        timeoutMs: Long = 12_000L
    ): String? = suspendCancellableCoroutine { cont ->
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val recognizer: SpeechRecognizer? = try {
            SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        } catch (_: Exception) { null }
        if (recognizer == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        // Prefer the device locale when it's English (en-NG / en-GB / en-US all
        // match a Nigerian accent), otherwise force English.
        val def = Locale.getDefault()
        val lang = if (def.language.equals("en", true)) def.toString() else "en-US"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        var done = false
        fun finish(v: String?) {
            if (done) return
            done = true
            try { recognizer.stopListening(); recognizer.destroy() } catch (_: Exception) {}
            if (cont.isActive) cont.resume(v)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(r: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) { finish(null) }
            override fun onResults(r: Bundle?) {
                val list = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                finish(list?.firstOrNull()?.takeIf { it.isNotBlank() })
            }
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        cont.invokeOnCancellation { try { recognizer.destroy() } catch (_: Exception) {} }
        try {
            recognizer.startListening(intent)
        } catch (_: Exception) { finish(null); return@suspendCancellableCoroutine }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { finish(null) }, timeoutMs
        )
    }
}
