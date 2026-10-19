package com.frit.app.features.veil

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.frit.app.core.Diag
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * VeilManager — FRIT's own lock screen ("the veil") for task time.
 *
 * WHY: Android never lets a third-party app dismiss a secure system lock
 * (PIN/pattern/password/biometric). So automation can only run behind the
 * lock if the user relaxes the system lock AND FRIT guards the phone itself.
 * The veil is a fullscreen lock activity (PIN) that:
 *  - arms when the Screen-veil toggle is ON and a task is running, scheduled,
 *    or auto-mode is on; otherwise it stands down and the ORIGINAL Android
 *    lock (whatever the user kept) shows through untouched;
 *  - wakes the screen to show itself when a task starts while locked;
 *  - can be dismissed instantly by the AI/user path (PIN in Phase 1,
 *    PIN-or-face in Phase 2).
 *
 * SECURITY HONESTY: the veil is an app lock, not a device credential. It does
 * not encrypt anything and cannot match a hardware-backed system lock. It
 * exists so scheduled Hands-Free tasks survive the lock screen — keep the
 * phone around you while tasks run (the veil says so on-screen too).
 */
object VeilManager {
    const val ACTION_DISMISS_VEIL = "com.frit.app.VEIL_DISMISS"

    private const val TAG = "VeilManager"
    private const val PREFS = "frit_veil"
    private const val KEY_ENABLED = "veil_enabled"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_FAILS = "pin_fails"
    private const val KEY_LOCKOUT_UNTIL = "pin_lockout_until"
    private const val KEY_BOOT_GRACE_UNTIL = "boot_grace_until"
    private const val MAX_FAILS = 5
    private const val LOCKOUT_MS = 30_000L
    const val BOOT_GRACE_MS = 120_000L

    // Visible = the veil activity is on screen. Written by the manager and
    // the activity's own lifecycle (show/dismiss/unlock paths).
    @Volatile var veilVisible = false

    private val activeTasks = AtomicInteger(0)
    private var appCtx: Context? = null

    fun init(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
    }

    private fun prefs(ctx: Context) =
        (appCtx ?: ctx.applicationContext).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- toggle ----------------
    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        if (!on) dismissVeil()
    }

    // ---------------- task activity ----------------
    fun isTaskActive(): Boolean = activeTasks.get() > 0

    fun taskStarted() {
        activeTasks.incrementAndGet()
        val ctx = appCtx ?: return
        try {
            if (!isScreenOn(ctx) && shouldArm(ctx)) showVeil(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "taskStarted veil", e)
        }
    }

    fun taskFinished() {
        if (activeTasks.decrementAndGet() <= 0) {
            activeTasks.set(0)
            val ctx = appCtx ?: return
            try {
                if (!shouldArm(ctx)) dismissVeil(ctx)
            } catch (e: Exception) {
                Log.w(TAG, "taskFinished veil", e)
            }
        }
    }

    // ---------------- arming ----------------
    /** True while the veil should guard the phone: toggle on + work pending. */
    fun shouldArm(ctx: Context): Boolean {
        if (!isEnabled(ctx)) return false
        if (inBootGrace(ctx)) return false
        if (isTaskActive()) return true
        return hasScheduledWork(ctx)
    }

    private fun hasScheduledWork(ctx: Context): Boolean {
        try {
            if (com.frit.app.features.automation.AutomationScheduler.listActiveTasks().isNotEmpty()) return true
        } catch (e: Exception) { Diag.warn("VeilManager", "hasScheduledWork", e) }
        return try {
            (appCtx ?: ctx.applicationContext)
                .getSharedPreferences("frit_prefs", Context.MODE_PRIVATE)
                .getBoolean("automode_enabled", false)
        } catch (e: Exception) { Diag.warn("VeilManager", "automode_pref", e); false }
    }

    // ---------------- screen events ----------------
    fun onScreenOn(ctx: Context) {
        init(ctx)
        try {
            if (shouldArm(ctx)) showVeil(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "onScreenOn", e)
        }
    }

    fun onUserPresent(ctx: Context) {
        init(ctx)
        try {
            if (!shouldArm(ctx)) dismissVeil(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "onUserPresent", e)
        }
    }

    fun onBoot(ctx: Context) {
        init(ctx)
        try {
            // Fail-safe against PIN lockout: for 2 minutes after every boot
            // the veil stays down so the owner can always reach Settings and
            // reset the veil PIN. Nothing is armed from a fresh boot anyway
            // (taskActive is in-memory and resets).
            prefs(ctx).edit().putLong(KEY_BOOT_GRACE_UNTIL, System.currentTimeMillis() + BOOT_GRACE_MS).apply()
        } catch (e: Exception) {
            Log.w(TAG, "onBoot", e)
        }
    }

    fun inBootGrace(ctx: Context): Boolean =
        try {
            System.currentTimeMillis() < prefs(ctx).getLong(KEY_BOOT_GRACE_UNTIL, 0)
        } catch (_: Exception) { false }

    // ---------------- show / dismiss ----------------
    fun isScreenOn(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isInteractive
    } catch (_: Exception) { true }

    /** Needs "display over other apps" (background-start exemption). False = not shown. */
    fun canLaunchVeil(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (_: Exception) { false }

    fun showVeil(ctx: Context): Boolean = showVeil(ctx, VeilActivity.MODE_LOCK)

    fun showVeil(ctx: Context, mode: String): Boolean {
        if (veilVisible) {
            // Already up — if a verify was requested, nudge it there.
            if (mode == VeilActivity.MODE_VERIFY) sendBroadcastToVeil(ctx, VeilActivity.ACTION_ENTER_VERIFY)
            return true
        }
        // Wake the display first: timeout-bound pulse, needs no permission
        // (scheduled runs already pulse via SessionWakeLock; repeating here
        // is harmless and covers OEMs where turnScreenOn is flaky).
        try {
            com.frit.app.features.automation.SessionWakeLock.wakeScreen(ctx, 60_000L)
        } catch (e: Exception) { Diag.warn("VeilManager", "wakeScreen", e) }
        if (!canLaunchVeil(ctx)) {
            Log.w(TAG, "showVeil blocked: overlay permission missing")
            return false
        }
        return try {
            val safe = (appCtx ?: ctx.applicationContext)
            val i = Intent(safe, VeilActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(VeilActivity.EXTRA_MODE, mode)
            safe.startActivity(i)
            veilVisible = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "showVeil failed", e)
            veilVisible = false
            false
        }
    }

    private fun sendBroadcastToVeil(ctx: Context, action: String) {
        try {
            (appCtx ?: ctx.applicationContext).sendBroadcast(Intent(action))
        } catch (e: Exception) {
            Log.w(TAG, "sendBroadcastToVeil", e)
        }
    }

    fun dismissVeil(ctx: Context? = null) {
        veilVisible = false
        try {
            val safe = ctx?.applicationContext ?: appCtx ?: return
            safe.sendBroadcast(Intent(ACTION_DISMISS_VEIL))
        } catch (e: Exception) {
            Log.w(TAG, "dismissVeil", e)
        }
    }

    // ---------------- device state ----------------
    fun isSystemLockSecure(ctx: Context): Boolean = try {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        km.isDeviceSecure
    } catch (_: Exception) { false }

    // ---------------- veil PIN (SHA-256 + salt, app-private) ----------------
    fun isPinSet(ctx: Context): Boolean = prefs(ctx).getString(KEY_PIN_HASH, null) != null

    /**
     * Set/replace the veil PIN. Returns null on success, human-readable error
     * otherwise. The current PIN is required unless none exists yet or the
     * phone is inside the post-boot grace window (lockout recovery).
     */
    fun setPin(ctx: Context, newPin: String, oldPin: String? = null): String? {
        if (newPin.length < 4 || newPin.length > 8 || !newPin.all { it.isDigit() })
            return "PIN must be 4–8 digits"
        if (isPinSet(ctx) && !inBootGrace(ctx)) {
            if (oldPin == null || !verifyPin(ctx, oldPin)) return "Current PIN incorrect"
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(ctx).edit()
            .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_PIN_HASH, hashPin(salt, newPin))
            .remove(KEY_FAILS).remove(KEY_LOCKOUT_UNTIL)
            .apply()
        return null
    }

    fun verifyPin(ctx: Context, pin: String): Boolean {
        val p = prefs(ctx)
        if (System.currentTimeMillis() < p.getLong(KEY_LOCKOUT_UNTIL, 0)) return false
        val saltB64 = p.getString(KEY_PIN_SALT, null) ?: return false
        val salt = try {
            Base64.decode(saltB64, Base64.DEFAULT)
        } catch (_: Exception) { return false }
        val ok = hashPin(salt, pin) == p.getString(KEY_PIN_HASH, null)
        if (ok) {
            p.edit().remove(KEY_FAILS).remove(KEY_LOCKOUT_UNTIL).apply()
        } else {
            val fails = p.getInt(KEY_FAILS, 0) + 1
            val ed = p.edit().putInt(KEY_FAILS, fails)
            if (fails >= MAX_FAILS) ed.putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + LOCKOUT_MS)
            ed.apply()
        }
        return ok
    }

    fun lockoutRemainingMs(ctx: Context): Long = try {
        (prefs(ctx).getLong(KEY_LOCKOUT_UNTIL, 0) - System.currentTimeMillis()).coerceAtLeast(0)
    } catch (_: Exception) { 0 }

    private fun hashPin(salt: ByteArray, pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        val out = md.digest(pin.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    // ---------------- task pause (stranger pickup, Phase 2) ----------------
    @Volatile private var tasksPaused = false
    private val pauseCallbacks = mutableListOf<() -> Unit>()

    fun areTasksPaused(): Boolean = tasksPaused

    /** Screens/services register in-flight job kill-switches (chatJob, voice jobs). */
    @Synchronized
    fun onTasksPaused(cb: () -> Unit) {
        pauseCallbacks += cb
    }

    @Synchronized
    private fun runPauseCallbacks() {
        pauseCallbacks.forEach {
            try {
                it()
            } catch (_: Exception) { }
        }
    }

    fun setTasksPaused(paused: Boolean) {
        tasksPaused = paused
        if (paused) runPauseCallbacks()
    }

    /** Owner is back (PIN or face): resume tasks, silence any alert. */
    fun ownerVerified(ctx: Context? = null) {
        tasksPaused = false
        try {
            stopStrangerAlert(ctx)
        } catch (_: Exception) { }
    }

    // ---------------- stranger alert: loud ringtone + vibration ----------------
    private var alertRingtone: android.media.Ringtone? = null
    private var prevAlarmVolume = -1

    fun startStrangerAlert(ctx: Context) {
        try {
            stopStrangerAlert(ctx)
        } catch (_: Exception) { }
        try {
            val safe = (appCtx ?: ctx.applicationContext)
            val am = safe.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            prevAlarmVolume = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM)
            am.setStreamVolume(
                android.media.AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM), 0
            )
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                ?: return
            alertRingtone = android.media.RingtoneManager.getRingtone(safe, uri)?.also { r ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    r.audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM).build()
                }
                r.play()
            }
            (safe.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(
                android.os.VibrationEffect.createWaveform(longArrayOf(0, 600, 300, 600), 0)
            )
            // Safety: never ring longer than 60s even if stop is missed.
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    { try { stopStrangerAlert(null) } catch (_: Exception) { } }, 60_000L
                )
            } catch (_: Exception) { }
        } catch (e: Exception) {
            Log.w(TAG, "startStrangerAlert", e)
        }
    }

    fun stopStrangerAlert(ctx: Context? = null) {
        try {
            alertRingtone?.stop()
        } catch (_: Exception) { }
        alertRingtone = null
        try {
            val safe = ctx?.applicationContext ?: appCtx ?: return
            (safe.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)?.cancel()
            if (prevAlarmVolume >= 0) {
                val am = safe.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, prevAlarmVolume, 0)
                prevAlarmVolume = -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopStrangerAlert", e)
        }
    }

    // ---------------- pickup (Phase 2) ----------------
    /** Only mid-task pickups interrogate — idle motion is ignored. */
    fun shouldVerifyOnPickup(): Boolean = try {
        isTaskActive()
    } catch (_: Exception) { false }

    /**
     * Stranger confirmed (face check failed/timed out): ring the alarm AND
     * engage the real device lock underneath the veil when possible, so a
     * thief faces two locks. Only called on confirmation — never on a mere
     * pickup, so the owner never eats a double unlock by mistake.
     */
    fun onStrangerConfirmed(ctx: Context) {
        try {
            startStrangerAlert(ctx)
        } catch (_: Exception) { }
        try {
            lockSystemIfPossible()
        } catch (_: Exception) { }
    }

    /** Best-effort real device lock via the accessibility service (API 28+). */
    fun lockSystemIfPossible(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return false
            val svc = com.frit.app.features.market.FritAccessibilityService.instance ?: return false
            svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        } catch (_: Exception) { false }
    }

    fun onPickup(ctx: Context) {
        init(ctx)
        try {
            if (!isEnabled(ctx) || !isTaskActive()) return
            Log.i(TAG, "pickup during task — pausing + verifying holder")
            setTasksPaused(true)
            if (!showVeil(ctx, VeilActivity.MODE_VERIFY)) postVerifyNotification(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "onPickup", e)
        }
    }

    /** Fallback when the veil can't launch (no overlay permission): tap to open it. */
    private fun postVerifyNotification(ctx: Context) {
        try {
            val safe = (appCtx ?: ctx.applicationContext)
            val mgr = safe.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                mgr.createNotificationChannel(
                    android.app.NotificationChannel(
                        "frit_veil", "FRIT Task Guard", android.app.NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val open = android.app.PendingIntent.getActivity(
                safe, 9101,
                Intent(safe, VeilActivity::class.java)
                    .putExtra(VeilActivity.EXTRA_MODE, VeilActivity.MODE_VERIFY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val n = androidx.core.app.NotificationCompat.Builder(safe, "frit_veil")
                .setContentTitle("Verify it's you")
                .setContentText("Phone was picked up during a task — tap to verify")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            mgr.notify(9101, n)
        } catch (e: Exception) {
            Log.w(TAG, "postVerifyNotification", e)
        }
    }
}
