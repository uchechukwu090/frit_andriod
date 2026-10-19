package com.frit.app.features.automation

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * SessionWakeLock — keeps scheduled/autonomous tasks alive when the phone
 * would otherwise sleep them.
 *
 * - acquire(): PARTIAL_WAKE_LOCK for the run (CPU stays on; 10-min timeout
 *   so a stuck task can never drain the battery forever).
 * - wakeScreen(): transient SCREEN_BRIGHT + ACQUIRE_CAUSES_WAKEUP pulse that
 *   turns the screen on when a scheduled task fires while the phone sleeps.
 *   Auto-releases via timeout; wrapped in try/catch because some OEMs block it.
 * - lockState(): reports lockscreen state so results can note when a secure
 *   (PIN/password) lock will block taps — Android hides credential windows
 *   (FLAG_SECURE) from accessibility, so no automation can type a lockscreen
 *   password; that part is a platform wall, not a bug.
 */
object SessionWakeLock {
    private const val TAG = "SessionWakeLock"
    @Volatile private var sessionLock: PowerManager.WakeLock? = null
    @Volatile private var screenLock: PowerManager.WakeLock? = null

    fun acquire(ctx: Context, timeoutMs: Long = 10 * 60 * 1000L) {
        release()
        try {
            val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            sessionLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FRIT:AgentSession").apply {
                acquire(timeoutMs)
            }
            Log.i(TAG, "session wake lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "acquire failed", e)
        }
    }

    /**
     * Full stay-awake for a run: PARTIAL (CPU) + SCREEN_BRIGHT (display stays
     * on till tasks are done). Screen-off mid-task kills taps, so autonomous
     * runs hold both; released in the run's finally block. The bright level
     * is deprecated on newer APIs — wrapped so a refusal falls back to
     * PARTIAL + the wakeScreen pulse, never a crash.
     */
    @Suppress("DEPRECATION")
    fun acquireFull(ctx: Context, timeoutMs: Long = 10 * 60 * 1000L) {
        acquire(ctx, timeoutMs)
        try {
            releaseScreen()
            val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            screenLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "FRIT:AgentScreen").apply {
                acquire(timeoutMs)
            }
            Log.i(TAG, "screen wake lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "acquireFull bright failed (fallback: partial)", e)
        }
    }

    fun releaseScreen() {
        try { screenLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        screenLock = null
    }

    @Suppress("DEPRECATION")
    fun wakeScreen(ctx: Context, holdMs: Long = 60_000L) {
        try {
            val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "FRIT:WakeScreen"
                ).apply { acquire(holdMs) }
                Log.i(TAG, "screen wake requested")
            }
        } catch (e: Exception) {
            Log.w(TAG, "wakeScreen failed (OEM may block it)", e)
        }
    }

    fun lockState(ctx: Context): String {
        return try {
            val km = ctx.applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            when {
                !km.isKeyguardLocked -> "unlocked"
                km.isKeyguardSecure -> "locked_secure"
                else -> "locked_swipe"
            }
        } catch (_: Exception) { "unknown" }
    }

    fun release() {
        try { sessionLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        sessionLock = null
        releaseScreen()
    }
}
