package com.frit.app.features.call

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.telecom.TelecomManager
import android.util.Base64
import android.util.Log
import com.frit.app.core.ChatSession
import com.frit.app.core.IntegrationCoordinator
import com.frit.app.features.memory.MemoryEngine
import com.frit.app.features.voice.VoiceEngine
import com.frit.app.features.wake.FritWakeWordService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * CallConversationManager — FRIT answers a ringing call and HOLDS a live
 * voice conversation with the caller (greet -> listen -> think -> speak ->
 * hang up), then reports a summary into chat + memory.
 *
 * HOW IT WORKS ON STOCK ANDROID (no root, no system app):
 * Speakerphone acoustic bridge. We answer via Telecom, force the
 * loudspeaker on, and run HALF-DUPLEX turns:
 *   caller voice -> loudspeaker -> our mic (VOICE_COMMUNICATION + HW echo
 *     canceler) -> STT -> brain -> TTS -> loudspeaker -> phone mic -> caller.
 * This is exactly how every speakerphone call already behaves — the caller
 * hears our TTS through the normal uplink, no privileged permission needed.
 *
 * HONEST LIMITS (do not promise beyond these):
 * - Half-duplex only: FRIT never listens while speaking (kills feedback).
 * - Needs a reasonably quiet room; heavy noise breaks STT like any speaker call.
 * - TTS quality over a GSM uplink is telephone-grade, not studio.
 * - UPGRADE PATH: Shizuku (shell UID 2000, wireless-debug pairing, no root)
 *   carries CAPTURE_AUDIO_OUTPUT, which unlocks DIGITAL downlink capture
 *   (VOICE_DOWNLINK, scrcpy/cally-proven). Slot it in here later; the turn
 *   loop below doesn't care where PCM comes from.
 */
object CallConversationManager {

    private const val TAG = "CallConversation"
    private const val SAMPLE_RATE = 16000
    private const val MAX_TURNS = 20
    private const val MAX_DURATION_MS = 5 * 60 * 1000L
    private const val LISTEN_WINDOW_MS = 10_000L
    private const val SILENCE_END_MS = 1500L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var job: Job? = null
    val inConversation: Boolean get() = job?.isActive == true
    /** Fired when the loop ends for any reason (service uses it to stopSelf). */
    @Volatile var onDone: (() -> Unit)? = null

    fun start(context: Context, incomingNumber: String, outgoing: Boolean = false) {
        if (inConversation) {
            Log.w(TAG, "Conversation already active, ignoring new trigger")
            return
        }
        AppHolder.ctx = context.applicationContext
        job = scope.launch { runConversation(context.applicationContext, incomingNumber, outgoing) }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runConversation(ctx: Context, number: String, outgoing: Boolean) {
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val prevSpeaker = audio.isSpeakerphoneOn
        val startedAt = System.currentTimeMillis()
        val label = if (number.isBlank()) "unknown number" else number
        var startedConversation = false
        // Keep the wake-word engine off our mic for the whole call.
        FritWakeWordService.pauseIfRunning()
        try {
            withContext(Dispatchers.Main) {
                if (outgoing) ChatSession.addUser("📞 Outgoing call to $label — FRIT speaking on the call")
                else ChatSession.addUser("📞 Incoming call from $label — FRIT answering with voice conversation")
            }
            // 1. Incoming: answer. Outgoing: wait until the other side actually answers.
            if (outgoing) {
                if (!waitForAnswer(ctx)) {
                    withContext(Dispatchers.Main) {
                        ChatSession.addAssistant("The outgoing call to $label wasn't answered or was declined — no conversation held.")
                    }
                    return
                }
            } else {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                        tm.acceptRingingCall()
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "acceptRingingCall denied", e)
                    withContext(Dispatchers.Main) {
                        ChatSession.addAssistant("Couldn't answer the call — ANSWER_PHONE_CALLS permission missing.")
                    }
                    return
                } catch (e: Exception) {
                    // Already answered (e.g. receiver answered first) — carry on.
                    Log.i(TAG, "acceptRingingCall skipped: ${e.message}")
                }
            }
            delay(2000)
            try { audio.isSpeakerphoneOn = true } catch (_: Exception) {}
            startedConversation = true

            // 2. Greet (caller hears this through the normal speakerphone uplink).
            speakBlocking(
                if (outgoing) "Hello, this is FRIT, the assistant, calling on behalf of the owner. How can I help you?"
                else "Hello! This is FRIT, the assistant. How can I help you?"
            )

            var silenceStreak = 0
            var turn = 0
            while (turn < MAX_TURNS && System.currentTimeMillis() - startedAt < MAX_DURATION_MS) {
                turn++
                val heard = listenOnce()
                if (heard.isNullOrBlank()) {
                    silenceStreak++
                    if (silenceStreak >= 3) {
                        speakBlocking("I still can't hear you, so I'll hang up now. Goodbye!")
                        break
                    }
                    speakBlocking(if (silenceStreak == 2) "Are you still there? Please speak up." else "Sorry, I didn't catch that. Could you repeat?")
                    continue
                }
                silenceStreak = 0
                val low = heard.lowercase()
                withContext(Dispatchers.Main) { ChatSession.addUser("📞 Caller: $heard") }
                if (isGoodbye(low)) {
                    speakBlocking("Goodbye! Have a great day.")
                    withContext(Dispatchers.Main) { ChatSession.addAssistant("📞 Call ended by caller request.") }
                    break
                }
                // Full brain: FRIT can act on the phone mid-call, not just chat.
                val reply = try {
                    IntegrationCoordinator.process(heard, ChatSession.compactHistory())
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { "Sorry, I ran into an error: ${e.message}" }
                val clean = reply.replace(Regex("\\s+"), " ").trim().take(600)
                withContext(Dispatchers.Main) { ChatSession.addAssistant("📞 FRIT (on call): $clean") }
                speakBlocking(clean.ifBlank { "Done." })
            }
            if (turn >= MAX_TURNS || System.currentTimeMillis() - startedAt >= MAX_DURATION_MS) {
                speakBlocking("We've reached the time limit. Goodbye!")
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Conversation cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Conversation error", e)
        } finally {
            if (startedConversation) hangup(ctx)
            try { audio.isSpeakerphoneOn = prevSpeaker } catch (_: Exception) {}
            FritWakeWordService.resumeIfRunning()
            job = null
            try {
                MemoryEngine.add("call_handled: $label (${if (outgoing) "outgoing" else "incoming"}), conversation ended normally")
            } catch (_: Exception) {}
            try { onDone?.invoke() } catch (_: Exception) {}
        }
    }

    /**
     * Outgoing calls: poll TelephonyManager until the other side answers.
     * Returns true when connected (or the line is still live), false when the
     * call clearly died first (declined / nobody answered).
     *
     * Permission-aware: without READ_PHONE_STATE, callState throws — the old
     * code treated that as "answered" instantly and FRIT spoke into ringing.
     * Now it falls back to a fixed 15s pick-up wait instead.
     */
    private suspend fun waitForAnswer(ctx: Context): Boolean {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        val canReadState = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.READ_PHONE_STATE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!canReadState) {
            Log.w(TAG, "READ_PHONE_STATE denied — fixed 15s answer-wait fallback")
            repeat(30) { delay(500) }
            return try { tm.callState != android.telephony.TelephonyManager.CALL_STATE_IDLE } catch (_: Exception) { true }
        }
        var sawRingOrOffhook = false
        val deadline = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < deadline) {
            val state = try { tm.callState } catch (_: Exception) { return true }
            when (state) {
                android.telephony.TelephonyManager.CALL_STATE_OFFHOOK -> {
                    // Answered — let the carrier audio path settle before greeting.
                    delay(2500)
                    return true
                }
                android.telephony.TelephonyManager.CALL_STATE_RINGING -> { sawRingOrOffhook = true }
                android.telephony.TelephonyManager.CALL_STATE_IDLE -> {
                    if (sawRingOrOffhook) return false   // was live, now gone -> declined
                }
            }
            delay(500)
        }
        // Timeout: treat a still-live line as answerable rather than dead air.
        return try { tm.callState != android.telephony.TelephonyManager.CALL_STATE_IDLE } catch (_: Exception) { true }
    }

    private fun isGoodbye(t: String): Boolean =
        t.contains("goodbye") || t.contains("good bye") || t == "bye" ||
            t.contains("hang up") || t.contains("that's all") || t.contains("thats all") ||
            (t.contains("thank") && t.contains("bye"))

    fun hangup(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                tm.endCall()
            }
        } catch (e: Exception) {
            Log.e(TAG, "endCall failed", e)
        }
    }

    private suspend fun speakBlocking(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        VoiceEngine.init(AppHolder.ctx ?: return@suspendCancellableCoroutine cont.resume(Unit))
        VoiceEngine.speak(text) { if (cont.isActive) cont.resume(Unit) }
        cont.invokeOnCancellation { VoiceEngine.stopSpeaking() }
    }

    /**
     * One half-duplex listen turn: VOICE_COMMUNICATION mic + hardware echo
     * canceler, ends on ~1.5s trailing silence or 10s cap. Returns STT text
     * (server Whisper; the mic is ours this turn so SpeechRecognizer would
     * fight our own AudioRecord — server STT only on the call path).
     */
    private suspend fun listenOnce(): String? = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)
        var record: AudioRecord? = null
        var aec: AcousticEchoCanceler? = null
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) return@withContext null
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(record.audioSessionId)
                    aec?.enabled = true
                }
            } catch (_: Exception) {}
            record.startRecording()
            val out = ByteArrayOutputStream()
            val buf = ShortArray(minBuf)
            val start = System.currentTimeMillis()
            var lastSpeechAt = start
            var heardSpeech = false
            while (System.currentTimeMillis() - start < LISTEN_WINDOW_MS) {
                val n = record.read(buf, 0, minBuf)
                if (n <= 0) { delay(20); continue }
                var peak = 0
                for (i in 0 until n) {
                    val a = kotlin.math.abs(buf[i].toInt())
                    if (a > peak) peak = a
                    out.write(buf[i].toInt() and 0xFF)
                    out.write((buf[i].toInt() shr 8) and 0xFF)
                }
                if (peak > 900) { heardSpeech = true; lastSpeechAt = System.currentTimeMillis() }
                if (heardSpeech && System.currentTimeMillis() - lastSpeechAt > SILENCE_END_MS) break
            }
            val pcm = out.toByteArray()
            if (!heardSpeech || pcm.size < SAMPLE_RATE) return@withContext null
            val wav = VoiceEngine.buildWavFromPcm(pcm, SAMPLE_RATE)
            val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
            val serverText = VoiceEngine.sendToWhisper(b64)
            if (serverText.isNotBlank() && VoiceEngine.isMeaningfulTranscript(serverText) &&
                !serverText.startsWith("Network error") &&
                !serverText.startsWith("Transcription error") &&
                !serverText.startsWith("Voice error")
            ) return@withContext serverText.trim()
            // Server STT failed (common mid-call when data is weak) — fall back
            // to the on-device Vosk model on the SAME captured audio instead of
            // reporting silence. Without this every offline turn counted as a
            // missed turn and FRIT hung up on a talking caller.
            val fallback = try {
                val actx = AppHolder.ctx
                if (actx != null) {
                    val shorts = ShortArray(pcm.size / 2) { i ->
                        ((pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 0xFF)).toShort()
                    }
                    com.frit.app.features.voice.OfflineSttEngine.transcribePcm(actx, shorts, SAMPLE_RATE)
                } else null
            } catch (_: Exception) { null }
            if (!fallback.isNullOrBlank() && VoiceEngine.isMeaningfulTranscript(fallback)) fallback.trim()
            else null
        } catch (e: Exception) {
            Log.e(TAG, "listenOnce failed", e)
            null
        } finally {
            try { record?.stop(); record?.release() } catch (_: Exception) {}
            try { aec?.release() } catch (_: Exception) {}
        }
    }

    /** Tiny holder so static contexts (receiver) can init engine singletons. */
    object AppHolder {
        @Volatile var ctx: Context? = null
    }
}
