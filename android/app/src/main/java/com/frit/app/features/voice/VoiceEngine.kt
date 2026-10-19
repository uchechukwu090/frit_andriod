package com.frit.app.features.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import android.util.Base64
import com.frit.app.core.Diag
import com.frit.app.data.NetworkClient

object VoiceEngine {
    interface VoiceListener {
        fun onReady()
        fun onResult(text: String)
        fun onError(err: String)
    }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var appCtx: Context? = null

    @Volatile private var isRecording = false
    @Volatile private var stopRequested = false
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var currentListener: VoiceListener? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val MIN_PCM_BYTES = SAMPLE_RATE * 2 * 1 / 3

    fun setBaseUrl(url: String) { NetworkClient.setBaseUrl(url) }
    fun status(): String = if (isTtsReady) "Voice Engine Active" else "Voice Engine Initializing"

    fun init(context: Context) {
        appCtx = context.applicationContext
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        // Always pin speech to English so the TTS can never drift
                        // into a non-English engine default (some engines default
                        // to Chinese voices when no English voice is present).
                        val enVoices = tts?.voices?.filter { it.locale.language == "en" }
                        val pick = enVoices?.firstOrNull { v ->
                            v.name.contains("neural", true) || v.name.contains("natural", true) ||
                            v.name.contains("enhanced", true) || v.quality >= 400 ||
                            !v.isNetworkConnectionRequired
                        } ?: enVoices?.firstOrNull()
                        if (pick != null) {
                            tts?.voice = pick
                        } else {
                            val res = tts?.setLanguage(Locale.US)
                            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                                tts?.setLanguage(Locale.UK)
                            }
                        }
                    } catch (_: Exception) {}
                    try {
                        tts?.setSpeechRate(0.95f)
                        tts?.setPitch(0.95f)
                    } catch (_: Exception) {}
                    isTtsReady = true
                }
            }
        }
    }

    /**
     * Decides whether STT output is actually speech the user said. Some engines
     * (Vosk's US model on quiet audio, multilingual Whisper on noise) return
     * non-blank filler like "." or CJK/Cyrillic glyphs instead of an empty
     * result — without this guard voice mode would "type" (and the model could
     * answer in a foreign language) from pure silence.
     */
    fun isMeaningfulTranscript(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.trim()
        if (t.length <= 2) {
            // "." / "…" / "-" / single letters are typical engine filler.
            return t.any(Char::isLetterOrDigit) && t.length >= 2
        }
        // A real English word (>=3 letters) means it heard actual speech.
        val englishWord = t.split(Regex("\\s+")).any { w -> w.length >= 3 && w.all { it.isLetter() } }
        if (englishWord) return true
        // No real English word: a multilingual Whisper often hallucinates another
        // script (Chinese/Japanese/Cyrillic) on noise or a heavy accent.
        val foreignScript = t.any { c ->
            c.code in 0x4E00..0x9FFF || c.code in 0x3040..0x30FF || c.code in 0x0400..0x04FF
        }
        return !foreignScript
    }

    // --- TTS session bookkeeping (audit F-40) --------------------------------
    // Every speak() call gets its own utterance-id namespace and only THAT
    // namespace may complete the call. Before, every call used the literal id
    // "frit_tts_last", so the tts.stop() that interrupts an older utterance
    // made the NEWEST listener fire the NEW call's completion early — FRIT
    // started listening again while it was still talking.
    private var speakSeq = 0
    private var activeSpeakSeq = 0

    /**
     * Human-style speak: streams sentence-by-sentence (QUEUE_ADD) so the user
     * hears the first sentence while the rest queues — feels live, not
     * type-then-robot. Short pauses between sentences via utterance pacing.
     */
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (!isTtsReady) {
            onComplete?.invoke()
            return
        }
        // Guard against engine voice drift before every utterance.
        try {
            if (tts?.voice?.locale?.language != "en") {
                val res = tts?.setLanguage(Locale.US)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.UK)
                }
            }
        } catch (e: Exception) { Diag.warn("VoiceEngine", "tts_language", e) }
        // Split into speakable chunks (~200 chars, sentence boundaries).
        val chunks = text.split(Regex("(?<=[.!?])\\s+")).flatMap { s ->
            if (s.length <= 220) listOf(s) else s.chunked(200)
        }.filter { it.isNotBlank() }
        if (chunks.isEmpty()) { onComplete?.invoke(); return }

        val seq = ++speakSeq
        activeSpeakSeq = seq
        val lastId = "frit_tts_${seq}_last"

        // Interrupt whatever was still queued. The previous session's
        // completion is deliberately suppressed (activeSpeakSeq already moved
        // on) — the new session owns the speaker from here.
        tts?.stop()

        if (onComplete != null) {
            val fired = java.util.concurrent.atomic.AtomicBoolean(false)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == lastId && seq == activeSpeakSeq && fired.compareAndSet(false, true)) {
                        scope.launch { onComplete() }
                    }
                }
                override fun onError(utteranceId: String?) {
                    if (utteranceId == lastId && seq == activeSpeakSeq && fired.compareAndSet(false, true)) {
                        scope.launch { onComplete() }
                    }
                }
            })
        }
        chunks.forEachIndexed { i, c ->
            val id = "frit_tts_${seq}_" + if (i == chunks.lastIndex) "last" else i.toString()
            tts?.speak(c, TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    /**
     * Stop the current utterance.
     *
     * The speak-completion callback is NOT fired: an explicit stop means the
     * caller owns the UI state from here on. Before, the interrupted utterance
     * reported "done", so pressing Stop (or tapping the mic to barge in)
     * immediately re-opened the microphone — the user's half-sentence was
     * cancelled by FRIT's own voice loop.
     */
    fun stopSpeaking() {
        activeSpeakSeq = 0
        tts?.stop()
    }

    /** True while the mic is actively capturing. */
    fun isRecordingActive(): Boolean = isRecording

    @Volatile private var wakePausedByVoice = false

    fun startRecording(listener: VoiceListener) {
        // Barge-in: any new recording cuts ongoing TTS first, so tapping the
        // mic while FRIT is speaking stops the speech and opens the mic
        // immediately for the user's new utterance (no voice-to-voice model,
        // so the mic is the only interruption channel).
        stopSpeaking()
        // Any existing recording is cancelled first so we never stack mic owners.
        stopRecording()
        // Free the mic for this recording: the wake-word engine holds it open,
        // which would make this AudioRecord read silence.
        if (!wakePausedByVoice) {
            com.frit.app.features.wake.FritWakeWordService.pauseIfRunning()
            wakePausedByVoice = true
        }
        currentListener = listener
        isRecording = true
        stopRequested = false
        recordingJob = scope.launch {
            listener.onReady()
            recordAndTranscribe { text ->
                if (text.startsWith("Voice error:") || text.startsWith("Transcription error")) {
                    listener.onError(text)
                } else {
                    listener.onResult(text)
                }
            }
        }
    }

    // 60s cap: long explanations must survive; the 2s-silence VAD above
    // still ends short utterances early, so this only affects long speech.
    suspend fun recordAndTranscribe(durationMs: Int = 60000, onResult: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                    .coerceAtLeast(4096)

                // Mic hand-off settle: startRecording() just told the wake-word
                // engine to stop+release ITS AudioRecord (same exclusive
                // VOICE_RECOGNITION source). Opening a new AudioRecord on that
                // source before the OS has actually torn down the previous one
                // can silently hand back a recorder that reads only silence —
                // this was the "tap mic, no more speech-to-text" bug. A short
                // wait here is cheap insurance; it only runs once per tap.
                if (wakePausedByVoice) delay(250)

                audioRecord?.stop()
                audioRecord?.release()

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, // Better for ASR
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
                )
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord = null
                    isRecording = false
                    withContext(Dispatchers.Main) { onResult("Voice error: mic not available") }
                    return@withContext
                }

                val pcmData = ByteArrayOutputStream()
                val buffer = ShortArray(bufferSize)
                audioRecord?.startRecording()
                isRecording = true
                val startTime = System.currentTimeMillis()

                var silenceCount = 0
                val silenceThreshold = 500 // Max amplitude to consider as silence
                val maxSilenceFrames = (SAMPLE_RATE / bufferSize) * 2 // ~2 seconds of silence

                // Loop is exit-gated by BOTH stopRequested and isRecording so a
                // tap immediately stops capture (no stuck mic / auto-restart).
                while (isRecording && !stopRequested && (System.currentTimeMillis() - startTime) < durationMs) {
                    val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (stopRequested) break
                    if (read > 0) {
                        var maxAmplitude = 0
                        for (i in 0 until read) {
                            val s = buffer[i]
                            val absS = Math.abs(s.toInt())
                            if (absS > maxAmplitude) maxAmplitude = absS
                            pcmData.write(s.toInt() and 0xFF)
                            pcmData.write((s.toInt() shr 8) and 0xFF)
                        }

                        // Simple VAD
                        if (maxAmplitude < silenceThreshold) {
                            silenceCount++
                        } else {
                            silenceCount = 0
                        }

                        if (silenceCount > maxSilenceFrames && pcmData.size() > MIN_PCM_BYTES) {
                            Log.i("VoiceEngine", "Silence detected, stopping early")
                            break
                        }
                    }
                }

                // Abort flag: if the user tapped to stop, DO NOT transcribe.
                val aborted = stopRequested
                val captureComplete = !stopRequested

                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                isRecording = false

                if (aborted) {
                    // User stopped manually — report a blank result so callers
                    // reset their UI state without sending anything to the server.
                    withContext(Dispatchers.Main) { onResult("") }
                    return@withContext
                }

                val rawPcm = pcmData.toByteArray()
                if (rawPcm.size < MIN_PCM_BYTES) {
                    withContext(Dispatchers.Main) { onResult("") }
                    return@withContext
                }

                val shorts = ShortArray(rawPcm.size / 2) { i ->
                    ((rawPcm[i * 2 + 1].toInt() shl 8) or (rawPcm[i * 2].toInt() and 0xFF)).toShort()
                }

                val wavBytes = buildWavFromPcm(rawPcm, SAMPLE_RATE)
                val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
                val result = sendToWhisper(base64Audio)
                if (result.isBlank()) {
                    val ctx = appCtx
                    val offline: String? = if (ctx != null) {
                        try { OfflineSttEngine.transcribePcm(ctx, shorts, SAMPLE_RATE) } catch (_: Exception) { null }
                    } else null
                    withContext(Dispatchers.Main) {
                        if (!offline.isNullOrBlank()) onResult(offline)
                        else onResult("Transcription error: the server returned no text.")
                    }
                } else if (result.startsWith("Network error") || result.startsWith("Transcription error")) {
                    // OFFLINE FALLBACK: server unreachable -> Vosk tiny model + system STT fallback
                    val ctx = appCtx
                    val offline: String? = if (ctx != null) {
                        try { OfflineSttEngine.transcribePcm(ctx, shorts, SAMPLE_RATE) } catch (_: Exception) { null }
                    } else null
                    withContext(Dispatchers.Main) {
                        if (!offline.isNullOrBlank()) onResult(offline)
                        else onResult(result + " (offline STT unavailable)")
                    }
                } else {
                    withContext(Dispatchers.Main) { onResult(result) }
                }

            } catch (e: Exception) {
                Log.e("VoiceEngine", "Recording/transcription error", e)
                isRecording = false
                try { audioRecord?.release() } catch (_: Exception) {}
                audioRecord = null
                withContext(Dispatchers.Main) { onResult("Voice error: ${e.message}") }
            } finally {
                // Recording ended on its own (silence / duration / done): hand the
                // mic back to the wake-word engine if we paused it for this capture.
                if (wakePausedByVoice) {
                    wakePausedByVoice = false
                    com.frit.app.features.wake.FritWakeWordService.resumeIfRunning()
                }
            }
        }
    }

    suspend fun sendToWhisper(base64Audio: String): String {
        return try {
            val response = NetworkClient.transcribeBase64(base64Audio)
            if (response.startsWith("{")) {
                val json = JSONObject(response)
                when {
                    json.has("text") -> json.getString("text").trim()
                    json.has("error") -> "Transcription error: ${json.optString("error")}"
                    json.optString("status") == "error" ->
                        "Transcription error: ${json.optString("message").ifBlank { json.optString("error") }}"
                    json.has("message") -> "Transcription error: ${json.getString("message")}"
                    else -> response
                }
            } else response
        } catch (e: Exception) {
            "Network error: ${e.message}"
        }
    }

    /**
     * Immediately stop recording: cancel the capture job, release the AudioRecord,
     * and suppress transcription. The listener receives a blank result (""), never
     * a partial transcription, so the UI resets cleanly.
     */
    fun stopRecording() {
        stopRequested = true
        isRecording = false
        try {
            // Releasing the AudioRecord unblocks a pending read() quickly.
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (_: Exception) {}
        recordingJob?.cancel()
        recordingJob = null
        currentListener = null
        if (wakePausedByVoice) {
            wakePausedByVoice = false
            com.frit.app.features.wake.FritWakeWordService.resumeIfRunning()
        }
    }

    fun shutdown() {
        stopRecording()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    fun buildWavFromPcm(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE, channels: Int = 1, bitDepth: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitDepth / 8
        val blockAlign = channels * bitDepth / 8
        val dataSize = pcm.size
        val totalSize = 36 + dataSize
        val wav = ByteArray(44 + dataSize)

        fun writeInt(offset: Int, value: Int) {
            wav[offset] = (value and 0xFF).toByte()
            wav[offset+1] = ((value shr 8) and 0xFF).toByte()
            wav[offset+2] = ((value shr 16) and 0xFF).toByte()
            wav[offset+3] = ((value shr 24) and 0xFF).toByte()
        }
        fun writeShort(offset: Int, value: Int) {
            wav[offset] = (value and 0xFF).toByte()
            wav[offset+1] = ((value shr 8) and 0xFF).toByte()
        }

        "RIFF".toByteArray().copyInto(wav, 0)
        writeInt(4, totalSize)
        "WAVE".toByteArray().copyInto(wav, 8)
        "fmt ".toByteArray().copyInto(wav, 12)
        writeInt(16, 16)
        writeShort(20, 1)
        writeShort(22, channels)
        writeInt(24, sampleRate)
        writeInt(28, byteRate)
        writeShort(32, blockAlign)
        writeShort(34, bitDepth)
        "data".toByteArray().copyInto(wav, 36)
        writeInt(40, dataSize)
        pcm.copyInto(wav, 44)
        return wav
    }
}
