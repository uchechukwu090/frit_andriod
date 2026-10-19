package com.frit.app.features.veil

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Screen/boot events for the veil.
 *
 * Manifest entry handles BOOT_COMPLETED only. SCREEN_ON / SCREEN_OFF /
 * USER_PRESENT are context-only broadcasts (the OS never delivers them to
 * manifest receivers), so hosts with a living context — FritBackgroundService
 * (persistent from boot) — register this same receiver dynamically via
 * dynamicFilter(). Either path funnels into VeilManager.
 */
class VeilStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        VeilManager.init(context)
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" ->
                VeilManager.onBoot(context)
            Intent.ACTION_SCREEN_ON ->
                VeilManager.onScreenOn(context)
            Intent.ACTION_USER_PRESENT ->
                VeilManager.onUserPresent(context)
            Intent.ACTION_SCREEN_OFF -> {
                // Screen off needs no action: a starting task wakes the phone
                // itself via VeilManager.taskStarted() -> showVeil (turnScreenOn).
            }
        }
    }

    companion object {
        fun dynamicFilter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
    }
}
