package com.frit.app.features.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.frit.app.features.automation.FritBackgroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = context.getSharedPreferences("frit_prefs", Context.MODE_PRIVATE)
            val baseUrl = prefs.getString("base_url", com.frit.app.core.FritConfig.BASE_URL) ?: ""
            val wakeEnabled = prefs.getBoolean("wake_enabled", false)

            // 1. Resume persistent background Wake Word Service if enabled or trained.
            // NOTE: do NOT consult in-memory LocalWakeWordEngine.isTrained() here —
            // in a fresh boot process it is always false until init()/loadTemplate()
            // runs. Check the persisted pref + template file instead.
            val templateExists = try {
                java.io.File(context.filesDir, "wake_template.json").exists()
            } catch (_: Exception) { false }
            if (wakeEnabled || templateExists) {
                val siWake = Intent(context, FritWakeWordService::class.java).apply {
                    putExtra("base_url", baseUrl)
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(siWake)
                    } else {
                        context.startService(siWake)
                    }
                } catch (e: Exception) {
                    // Android 12+: FGS start from background is restricted.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        e.javaClass.simpleName == "ForegroundServiceStartNotAllowedException"
                    ) {
                        Log.w("BootReceiver", "FGS start not allowed for wake service; left stopped until foreground", e)
                    } else {
                        Log.w("BootReceiver", "Could not start wake service on boot", e)
                    }
                }
            }

            // 2. Resume persistent background Automation Engine (cron / recurring tasks)
            val siBg = Intent(context, FritBackgroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(siBg)
                } else {
                    context.startService(siBg)
                }
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e.javaClass.simpleName == "ForegroundServiceStartNotAllowedException"
                ) {
                    Log.w("BootReceiver", "FGS start not allowed for bg service; left stopped until foreground", e)
                } else {
                    Log.w("BootReceiver", "Could not start bg service on boot", e)
                }
            }
        }
    }
}
