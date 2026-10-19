package com.frit.app.features.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.frit.app.R
import com.frit.app.core.sanitizeExtras
import com.frit.app.data.NetworkClient

/**
 * FritWakeWordService — listens for a custom wake word ("Hey Frit") using the
 * fully-on-device LocalWakeWordEngine (MFCC + DTW). No Picovoice dependency, no
 * cloud, no network. Pairs with the local model brain.
 *
 * Flow:
 * 1. LocalWakeWordEngine captures audio from the microphone (VAD-verified).
 * 2. On wake word match, launches FritVoiceOverlayService (the Gemini-like overlay).
 * 3. The overlay auto-starts listening (Siri mode) for the user's command.
 */
class FritWakeWordService : Service() {
    companion object {
        const val CHANNEL_ID = "FritWakeWordChannel"
        const val ACTION_WAKE = "com.frit.app.WAKE_DETECTED"
        const val ACTION_STOP = "com.frit.app.WAKE_STOP"
        const val ACTION_TRAIN = "com.frit.app.WAKE_TRAIN"

        var isRunning = false
            private set

        // Mic-ownership arbitration (Google-style yield, userspace version):
        // - deliberateHold = our own voice overlay/recording owns the mic.
        // - yieldActive = a FOREIGN app currently holds the mic; the yield
        //   watcher resumes us when it frees it.
        // NOTE: no public API reveals WHICH app holds a recording config
        // (package/UID getters are hidden), so foreign use is inferred by
        // source/count (see isForeignMicActive), and resume always revalidates
        // live instead of trusting a possibly-stale callback flag.
        @Volatile private var deliberateHold = false
        @Volatile private var yieldActive = false
        @Volatile private var appCtx: Context? = null

        /** Optional callback invoked (main thread) after wake-word training completes. */
        var trainingComplete: (() -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, FritWakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FritWakeWordService::class.java))
        }

        /** The overlay takes the mic while it's recording — pause wake-word listening. */
        fun pauseIfRunning() {
            deliberateHold = true
            LocalWakeWordEngine.pause()
        }

        /** Give the mic back to wake-word listening once the overlay closes. */
        fun resumeIfRunning() {
            deliberateHold = false
            if (!isRunning) return
            if (yieldActive) return // watcher owns the resume while foreign-held
            // Brief delay so closing the overlay doesn't re-trigger on audio release artifacts
            Thread {
                try { Thread.sleep(1200) } catch (_: InterruptedException) {}
                if (!isRunning || deliberateHold || yieldActive) return@Thread
                // Live revalidation (a callback flag could be stale if configs
                // changed while we held no recorder): only reopen into a free
                // mic, else defer to the yield watcher.
                if (isMicForeignHeldNow()) { yieldActive = true; return@Thread }
                LocalWakeWordEngine.resume()
            }.start()
        }

        /** True if a FOREIGN app holds the mic right now (live OS query). */
        fun isMicForeignHeldNow(): Boolean {
            val ctx = appCtx ?: return false
            return try {
                val am = ctx.getSystemService(AudioManager::class.java) ?: return false
                isForeignMicActive(am.activeRecordingConfigurations)
            } catch (_: Exception) { false }
        }

        /**
         * Foreign-mic inference. While listening we hold exactly one recorder
         * (VOICE_RECOGNITION, or MIC on fallback devices) — anything beyond
         * that, anything on an unrelated source (camcorder, VoIP, hotword…),
         * or anything the framework silenced is someone else's capture.
         */
        fun isForeignMicActive(configs: List<AudioRecordingConfiguration>): Boolean {
            if (configs.isEmpty()) return false
            if (configs.any { it.isClientSilenced }) return true
            // Yield-state aware: when we are paused/stopped we hold ZERO
            // recorders, so ANY live config is foreign (WhatsApp voice note,
            // call, camera). The old count-only check mistook a single
            // foreign MIC for "ours" once we had released, resumed after 2s
            // and stole the mic mid-recording.
            val holdingMic = try { LocalWakeWordEngine.isRunning() } catch (_: Exception) { false }
            if (!holdingMic) return true
            var ours = 0
            for (c in configs) {
                val src = try { c.clientAudioSource } catch (_: Exception) { MediaRecorder.AudioSource.DEFAULT }
                if (src == MediaRecorder.AudioSource.VOICE_RECOGNITION || src == MediaRecorder.AudioSource.MIC) ours++
                else return true
            }
            return ours > 1
        }

        /** Start the training flow: capture two utterances of the wake phrase. */
        fun train(context: Context, onComplete: () -> Unit) {
            trainingComplete = onComplete
            val intent = Intent(context, FritWakeWordService::class.java).apply { action = ACTION_TRAIN }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        appCtx = this
        promoteToForeground()
        LocalWakeWordEngine.init(this)
        installMicYield()
        ensureListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TRAIN -> {
                beginTraining()
                return START_STICKY
            }
        }
        val baseUrl = intent?.getStringExtra("base_url")
        if (baseUrl != null) NetworkClient.setBaseUrl(baseUrl)

        ensureListening()
        return START_STICKY
    }

    private fun promoteToForeground() {
        createNotificationChannel()
        try {
            val notif = buildNotification("Starting wake word engine...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                }
                try {
                    startForeground(1, notif, fgsType)
                } catch (_: Exception) {
                    startForeground(1, notif)
                }
            } else {
                startForeground(1, notif)
            }
        } catch (e: Exception) {
            android.util.Log.e("FritWakeWord", "startForeground failed", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        uninstallMicYield()
        appCtx = null
        LocalWakeWordEngine.stop()
        super.onDestroy()
    }

    // ---------- Google-style mic yield (userspace) ----------
    // The OS tells us (API 29+) whenever any app starts/stops capturing.
    // Another app grabs the mic -> we pause AND release ours (same as the
    // overlay handoff, so the other app gets real audio, not a fight).
    // Mic freed -> we resume after a 2s backoff. Our own overlay's
    // recordings are ignored here (same package) so deliberate pauses are
    // never mistaken for foreign ones.
    private val yieldHandler = Handler(Looper.getMainLooper())
    private var yieldResumeRunnable: Runnable? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null

    private fun installMicYield() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val am = getSystemService(AudioManager::class.java) ?: return
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                onMicConfigsChanged(isForeignMicActive(configs))
            }
        }
        recordingCallback = cb
        try { am.registerAudioRecordingCallback(cb, yieldHandler) }
        catch (_: Exception) { recordingCallback = null }
    }

    private fun uninstallMicYield() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        yieldResumeRunnable?.let { yieldHandler.removeCallbacks(it); yieldResumeRunnable = null }
        try {
            val am = getSystemService(AudioManager::class.java)
            recordingCallback?.let { am?.unregisterAudioRecordingCallback(it) }
        } catch (_: Exception) {}
        recordingCallback = null
        yieldActive = false
    }

    private fun onMicConfigsChanged(foreignActive: Boolean) {
        if (foreignActive) {
            yieldResumeRunnable?.let { yieldHandler.removeCallbacks(it); yieldResumeRunnable = null }
            if (LocalWakeWordEngine.isRunning()) {
                yieldActive = true
                LocalWakeWordEngine.pause()
                updateNotification("Paused — another app is using the mic")
            }
        } else if (yieldActive) {
            yieldActive = false
            if (deliberateHold || !isRunning) return
            val r = Runnable {
                yieldResumeRunnable = null
                if (!isRunning || deliberateHold || yieldActive) return@Runnable
                if (isMicForeignHeldNow()) { yieldActive = true; return@Runnable }
                LocalWakeWordEngine.resume()
                if (LocalWakeWordEngine.isRunning()) updateNotification("Listening for wake word...")
            }
            yieldResumeRunnable = r
            yieldHandler.postDelayed(r, 2000)
        }
    }

    private fun ensureListening() {
        if (LocalWakeWordEngine.isRunning()) return
        if (!LocalWakeWordEngine.isTrained()) {
            updateNotification("Wake word not trained — open FRIT settings to train.")
            return
        }
        LocalWakeWordEngine.startListening(wakeListener)
        updateNotification("Listening for wake word...")
    }

    private fun beginTraining() {
        updateNotification("Wake word training — say the phrase 2x")
        LocalWakeWordEngine.startTraining(wakeListener)
    }

    private val wakeListener = object : LocalWakeWordEngine.Listener {
        override fun onWakeWordDetected() {
            activateFrit()
        }

        override fun onTrainingStep(step: Int, message: String) {
            updateNotification(message)
            Handler(Looper.getMainLooper()).post {
                when (step) {
                    2 -> Toast.makeText(this@FritWakeWordService, "Say it once more, clearly.", Toast.LENGTH_LONG).show()
                    0 -> {
                        Toast.makeText(this@FritWakeWordService, "Wake word trained. Listening.", Toast.LENGTH_LONG).show()
                        val prefs = getSharedPreferences("frit_prefs", MODE_PRIVATE)
                        prefs.edit().putBoolean("wake_enabled", true).apply()
                        // Training finished -> engine is already in DETECT mode.
                        updateNotification("Listening for wake word...")
                        trainingComplete?.invoke()
                        trainingComplete = null
                    }
                    else -> Toast.makeText(this@FritWakeWordService, message, Toast.LENGTH_LONG).show()
                }
            }
        }

        override fun onError(message: String) {
            if (message.contains("not trained")) {
                updateNotification("Wake word not trained — open FRIT settings to train.")
            }
        }
    }

    private fun activateFrit() {
        updateNotification("Wake word detected! Activating FRIT overlay...")
        // Release the mic so the overlay's own recording works.
        LocalWakeWordEngine.pause()

        try {
            val serviceIntent = Intent(applicationContext, com.frit.app.features.voice.FritVoiceOverlayService::class.java).apply {
                putExtra("auto_listen", true) // Start listening immediately
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            // Android 12+ ForegroundServiceStartNotAllowedException when triggered
            // from background + missing overlay permission both land here.
            // Fall back to a full-screen notification tap instead of silently dying.
            android.util.Log.e("FritWakeWord", "overlay start blocked", e)
            updateNotification("Tap to open FRIT (overlay blocked in background)")
        }

        // Note: no ACTION_WAKE broadcast anymore — it made the in-app chat ALSO
        // start recording on top of the overlay (double mic read = silence).
        updateNotification("Listening for wake word...")
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FritWakeWordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        // Tap opens the app: on Android 12+ a background trigger can be
        // blocked from launching the overlay directly (ForegroundServiceStart-
        // NotAllowed), so the notification itself becomes the entry point.
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, com.frit.app.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FRIT")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_frit_notification)
            .setContentIntent(openIntent)
            .setOngoing(true).setSilent(true)
            .addAction(0, "Stop", stopIntent)
            .build()
            .sanitizeExtras()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "FRIT Wake Word", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
}