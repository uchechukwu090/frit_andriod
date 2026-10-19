package com.frit.app.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import com.frit.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A11yKeeper — keeps the accessibility switch alive as far as Android allows.
 *
 * Hard truth: no app can force its own accessibility service on
 * (WRITE_SECURE_SETTINGS is signature-only), and the system turns it off on
 * app update/reinstall, some OS upgrades, and aggressive OEM task killers.
 * So instead of a toggle that can't exist, this keeper:
 *  - remembers once the user EVER enabled it (a11y_ever_enabled),
 *  - checks every 60s from the background service,
 *  - posts a high-priority "Tap to re-enable" notification (deep-links to
 *    system Accessibility settings) the moment it goes missing,
 *  - offers a one-tap battery-optimisation exemption (the #1 OEM killer).
 */
object A11yKeeper {
    private const val TAG = "A11yKeeper"
    private const val CHANNEL = "frit_a11y"
    private const val NOTIF_ID = 41
    private const val EVER_KEY = "a11y_ever_enabled"
    private const val NAG_AT_KEY = "a11y_last_nag"

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun isEnabled(context: Context): Boolean {
        return try {
            val expected = "${context.packageName}/.features.market.FritAccessibilityService"
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.split(":").any { it.equals(expected, ignoreCase = true) || it.endsWith("FritAccessibilityService") }
        } catch (_: Exception) { false }
    }

    private fun everEnabled(context: Context): Boolean =
        try {
            context.getSharedPreferences("frit_prefs", Context.MODE_PRIVATE).getBoolean(EVER_KEY, false)
        } catch (_: Exception) { false }

    fun start(context: Context) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                try { checkOnce(context.applicationContext) } catch (e: Exception) {
                    Log.w(TAG, "check failed", e)
                }
                delay(60_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun checkOnce(context: Context) {
        val prefs = context.getSharedPreferences("frit_prefs", Context.MODE_PRIVATE)
        if (isEnabled(context)) {
            if (!prefs.getBoolean(EVER_KEY, false)) prefs.edit().putBoolean(EVER_KEY, true).apply()
            return
        }
        // Only nag users who had it on (or run AutoMode) — never nag fresh installs.
        val autoMode = try { prefs.getBoolean("automode_enabled", false) } catch (_: Exception) { false }
        if (!everEnabled(context) && !autoMode) return
        val last = try { prefs.getLong(NAG_AT_KEY, 0) } catch (_: Exception) { 0L }
        if (System.currentTimeMillis() - last < 6 * 60 * 60 * 1000L) return // max one nag per 6h
        prefs.edit().putLong(NAG_AT_KEY, System.currentTimeMillis()).apply()
        postReenable(context)
    }

    private fun postReenable(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Frit access", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                context, NOTIF_ID, settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_frit_notification)
                .setContentTitle("Frit lost screen access")
                .setContentText("Accessibility got switched off (update/reboot/battery saver). Tap to turn Frit Assistant back on.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "Frit's hands were switched off by the system. Tap here, find Frit Assistant, and turn it back on. " +
                        "Tip: also exempt Frit from battery optimisation so it stays on."
                ))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, n)
        } catch (e: Exception) {
            Log.w(TAG, "nag failed", e)
        }
    }

    /** One-tap battery-optimisation exemption — the top OEM killer of services. */
    fun requestBatteryExemption(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) return true
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) { false }
    }
}
