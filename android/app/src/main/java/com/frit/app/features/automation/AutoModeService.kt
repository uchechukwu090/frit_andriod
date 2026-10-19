package com.frit.app.features.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.frit.app.R
import com.frit.app.core.ChatSession
import com.frit.app.core.Diag
import com.frit.app.core.sanitizeExtras
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * AutoMode Phase 1 — FRIT harnesses the phone while this runs:
 * - Auto-answers incoming calls (flips the existing auto_answer_calls gate
 *   ON, restores the user's prior value on exit — the receiver + call
 *   conversation service do the actual work).
 * - Keeps the watchers alive (wake-word service if the user enabled it).
 * - Live status line on its notification every tick: foreground app,
 *   battery, active scheduled tasks.
 * - Announces start/stop once in chat history.
 *
 * Tick = 60s. Doze can stretch ticks on idle phones — best-effort harness,
 * not a realtime guarantee. Phase 2 candidates: NotificationListener-based
 * message triage, per-contact call policy, auto-reply drafts.
 */
class AutoModeService : Service() {
    companion object {
        const val CHANNEL_ID = "FritAutoModeChannel"
        const val ACTION_STOP = "com.frit.app.AUTOMODE_STOP"
        private const val NOTIF_ID = 33 // distinct from FritBackgroundService (id 3): two FGS sharing one id clobber each other's notification
        private const val TICK_MS = 60_000L
        // Agency sweep cadence: every 15 min each SELECTED app gets an
        // autonomous turn (open, review since last check, decide: reply / wait
        // / escalate). Token-hungry by design — this is the "agent it" loop.
        private const val SWEEP_MS = 15 * 60 * 1000L
        private const val TAG = "AutoMode"
        // One chat note per watched app per 30 min on focus transitions.
        const val FG_NOTE_COOLDOWN_MS = 30 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, AutoModeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutoModeService::class.java))
        }

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences("frit_prefs", Context.MODE_PRIVATE)
                .getBoolean("automode_enabled", false)
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var loop: Job? = null
    private var sweepJob: Job? = null
    private var prevAutoAnswer = false
    // Foreground-watch: which watched app (if any) was foreground last tick,
    // plus per-app chat-note cooldown so focus transitions don't spam chat.
    private var lastFgPkg: String? = null
    private val lastFgNote = mutableMapOf<String, Long>()

    override fun onCreate() {
        super.onCreate()
        // Profile/memory may never have been init'ed if the service started
        // without MainActivity (boot, notification stop/start) — init here so
        // sweeps still see the owner's personality + feedback.
        try { com.frit.app.core.UserProfile.init(this) } catch (e: Exception) { Diag.warn("AutoMode", "profile_init", e) }
        try { com.frit.app.features.memory.MemoryEngine.init(this) } catch (e: Exception) { Diag.warn("AutoMode", "memory_init", e) }
        promoteToForeground("AutoMode starting…")
        val prefs = getSharedPreferences("frit_prefs", MODE_PRIVATE)
        prevAutoAnswer = prefs.getBoolean("auto_answer_calls", false)
        prefs.edit().putBoolean("auto_answer_calls", true).apply()

        // Watchers alive: wake-word service if the user ever enabled it.
        if (prefs.getBoolean("wake_enabled", false)) {
            try { com.frit.app.features.wake.FritWakeWordService.start(this) } catch (e: Exception) {
                Log.w(TAG, "wake service start failed", e)
            }
        }
        try { AutomationScheduler.init(this) } catch (e: Exception) { Diag.warn("AutoMode", "scheduler_init", e) }

        announce("AutoMode ON — I'm harnessing the phone: calls auto-answered, watchers supervised, scheduled tasks running.")
        loop = scope.launch {
            while (isActive) {
                try { updateStatus() } catch (e: Exception) { Log.w(TAG, "tick failed", e) }
                delay(TICK_MS)
            }
        }
        sweepJob = scope.launch {
            delay(60_000L) // let the phone settle after toggle
            while (isActive) {
                try { runAppSweep() } catch (e: Exception) { Log.w(TAG, "sweep failed", e) }
                delay(SWEEP_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground("AutoMode active")
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences("frit_prefs", MODE_PRIVATE).edit().putBoolean("automode_enabled", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loop?.cancel()
        sweepJob?.cancel()
        scope.cancel()
        try {
            getSharedPreferences("frit_prefs", MODE_PRIVATE).edit()
                .putBoolean("auto_answer_calls", prevAutoAnswer).apply()
        } catch (e: Exception) { Diag.warn("AutoMode", "restore_auto_answer", e) }
        announce("AutoMode OFF — call answering restored to your prior setting.")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateStatus() {
        val fgPkg = try {
            com.frit.app.features.market.FritAccessibilityService.getCurrentApp().ifBlank { "unknown" }
        } catch (_: Exception) { "unknown" }
        val watched = try { AutoModeApps.selected(this) } catch (_: Exception) { emptySet<String>() }
        // Focus tracking: a watched app in the foreground is "under watch"
        // even with zero notifications — transitions noted in chat (cooled).
        val watchingFg = fgPkg != "unknown" && watched.contains(fgPkg)
        if (watchingFg && fgPkg != lastFgPkg) {
            val now = System.currentTimeMillis()
            if (now - (lastFgNote[fgPkg] ?: 0L) > FG_NOTE_COOLDOWN_MS) {
                lastFgNote[fgPkg] = now
                announce("👁 ${labelOf(fgPkg)} in focus — watching for anything needing you.")
            }
        }
        lastFgPkg = fgPkg
        val batt = batteryPct().let { if (it >= 0) "$it%" else "?" }
        val tasks = try { AutomationScheduler.listActiveTasks().size } catch (_: Exception) { 0 }
        val fgLabel = if (fgPkg == "unknown") "unknown" else labelOf(fgPkg)
        val watchBit = if (watchingFg) " · 👁 $fgLabel" else ""
        promoteToForeground("AutoMode · $fgLabel · batt $batt · $tasks task(s)$watchBit")
    }

    private fun labelOf(pkg: String): String = try {
        val ai = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(ai)?.toString() ?: pkg
    } catch (_: Exception) { pkg }

    private fun batteryPct(): Int = try {
        val bm = getSystemService(BatteryManager::class.java)
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it >= 0 } ?: -1
    } catch (_: Exception) { -1 }

    // ---------- Agency sweep: agent the selected apps, don't just watch ----
    // Every SWEEP_MS each checked app gets an autonomous turn with an agency
    // brief + continuity state (last check + last summary persisted per app).
    // The agent decides like the owner: reply/act now, wait silently, or
    // message the owner in chat for a decision. Quiet sweeps stay out of chat
    // (log only); anything acted-on or owner-addressed is posted.
    private suspend fun runAppSweep() {
        if (batteryPct() in 0..19) { Log.i(TAG, "sweep skipped: battery low"); return }
        val watched = try { AutoModeApps.selected(this) } catch (_: Exception) { emptySet<String>() }
        if (watched.isEmpty()) return
        SessionWakeLock.wakeScreen(this)
        SessionWakeLock.acquireFull(this)
        try {
            for (pkg in watched) {
                try { sweepOneApp(pkg) } catch (e: Exception) { Log.w(TAG, "sweep $pkg failed", e) }
            }
        } finally {
            SessionWakeLock.release()
        }
    }

    // Official-context apps: the owner expects a formal representative there.
    private fun appToneHint(pkg: String, label: String): String {
        val hay = "$pkg $label".lowercase()
        val official = listOf("mail", "gmail", "outlook", "linkedin", "bank", "opay", "palmpay", "moniepoint", "kuda", "mt5", "metatrader", "exness", "office", "docs", "classroom")
        val chatty = listOf("whatsapp", "telegram", "messenger", "sms", "mms", "instagram", "facebook", "twitter", "tiktok", "snapchat")
        return when {
            official.any { it in hay } -> "Act OFFICIAL in $label (formal, full sentences, no slang) — this is an official context."
            chatty.any { it in hay } -> "In $label mirror the THREAD's live tone (neutral default; official only if the thread is formal; casual if the chat is casual). Never force a fixed persona."
            else -> "Default to warm neutral in $label; mirror the screen's tone if it clearly leans official or casual."
        }
    }

    private suspend fun sweepOneApp(pkg: String) {
        val prefs = getSharedPreferences("frit_prefs", MODE_PRIVATE)
        val label = labelOf(pkg)
        val stateKey = "automode_state_$pkg"
        val prev = try { JSONObject(prefs.getString(stateKey, "{}")) } catch (_: Exception) { JSONObject() }
        val lastCheck = prev.optLong("lastCheck", 0L)
        val lastSummary = prev.optString("lastSummary", "")
        val ago = if (lastCheck == 0L) "never (first sweep)"
        else "${(System.currentTimeMillis() - lastCheck) / 60000} min ago"
        // Personality: who the owner is (baseline) + what worked before.
        val profileBlock = try { com.frit.app.core.UserProfile.toPromptBlock() } catch (_: Exception) { "" }
        val feedback = try {
            com.frit.app.features.memory.MemoryEngine.relevant("feedback $label").take(3).joinToString(" | ").take(400)
        } catch (_: Exception) { "" }
        val risk = try { com.frit.app.core.UserProfile.load().tradingRisk } catch (_: Exception) { "" }
        val moneyRule = when {
            risk.equals("aggressive", ignoreCase = true) -> "Money rule: act within standing rules; report after."
            risk.equals("moderate", ignoreCase = true) -> "Money rule: draft anything involving money for the owner; act on everything else."
            else -> "Money rule: NEVER move money — draft for the owner and wait."
        }
        val brief = "[AutoMode agency sweep for $label ($pkg). Last check $ago; then: ${lastSummary.ifBlank { "n/a" }}. " +
            "You are acting as the phone's owner inside this app. Open it, review what's new/unread since last check, and DECIDE like the owner: " +
            "(1) reply/act now if something clearly needs it, (2) stay silent if nothing does, (3) message the owner in chat if you need a decision. " +
            "${appToneHint(pkg, label)} " +
            (if (profileBlock.isNotBlank()) "Owner baseline: $profileBlock. " else "") +
            (if (feedback.isNotBlank()) "Past feedback (BINDING — never repeat a negatively-rated approach): $feedback. " else "") +
            "$moneyRule " +
            "Standing rules: never send money, never delete, max 3 outbound messages per sweep, keep replies short and human, don't spam. " +
            "End your reply with one line: SWEEP_SUMMARY: <one sentence of what you saw/did> (or SWEEP_SUMMARY: nothing new).]"
        val result = withTimeoutOrNull(10 * 60 * 1000L) {
            com.frit.app.core.IntegrationCoordinator.process(brief, emptyList(), allowReschedule = false)
        } ?: "Sweep timed out after 10 minutes."
        val summary = Regex("SWEEP_SUMMARY:\\s*(.+)").find(result)?.groupValues?.getOrNull(1)?.trim()?.take(300) ?: ""
        prefs.edit().putString(stateKey, JSONObject().put("lastCheck", System.currentTimeMillis()).put("lastSummary", summary).toString()).apply()
        val quiet = result.length < 200 && Regex("nothing new|ignored|no new|all (clear|quiet)|nothing (to|needs?)", RegexOption.IGNORE_CASE).containsMatchIn(result)
        if (!quiet) {
            withContext(Dispatchers.Main) {
                try {
                    ChatSession.init(this@AutoModeService)
                    ChatSession.addUser("🤖 [AutoMode · $label] agency sweep")
                    ChatSession.addAssistant(result)
                } catch (e: Exception) { Diag.warn("AutoMode", "sweep_chat", e) }
            }
        } else {
            Log.i(TAG, "sweep $label: quiet")
        }
    }

    private fun announce(text: String) {
        scope.launch {
            withContext(Dispatchers.Main) {
                try {
                    ChatSession.init(this@AutoModeService)
                    ChatSession.addAssistant(text)
                } catch (e: Exception) {
                    Log.w(TAG, "chat announce failed", e)
                }
            }
        }
    }

    private fun promoteToForeground(text: String) {
        createChannel()
        try {
            val stopIntent = PendingIntent.getService(
                this, 0,
                Intent(this, AutoModeService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE
            )
            val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("FRIT AutoMode")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_frit_notification)
                .setOngoing(true).setSilent(true)
                .addAction(0, "Stop", stopIntent)
                .build()
                .sanitizeExtras()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                }
                try { startForeground(NOTIF_ID, notif, fgsType) }
                catch (_: Exception) { startForeground(NOTIF_ID, notif) }
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "FRIT AutoMode", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }
}
