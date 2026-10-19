package com.frit.app.features.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log

/**
 * CallAnswerReceiver — auto-answers incoming calls when the user enabled it
 * ("answer my calls" automation). Uses TelecomManager.acceptRingingCall()
 * (API 26+, ANSWER_PHONE_CALLS permission) — no system privileges needed
 * for ANSWERING.
 *
 * HARD PLATFORM LIMIT (be honest about this): a two-way voice conversation —
 * FRIT speaking INTO the call uplink and transcribing the caller from the
 * downlink — is BLOCKED on stock Android. In-call audio injection requires a
 * system/privileged dialer app or root. So this receiver answers + announces
 * via speaker TTS as a best effort, and logs the event; full duplex
 * call-conversation is parked until a privileged build exists.
 */
class CallAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return

        val prefs = context.getSharedPreferences("frit_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_answer_calls", false)) return

        val incoming = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
                tm.acceptRingingCall()
                Log.i("CallAnswer", "Auto-answered incoming call from $incoming")
                // Hand the live conversation to the foreground call service:
                // speakerphone bridge, listen -> think -> speak loop, auto hangup.
                com.frit.app.features.call.CallConversationService.start(context, incoming)
            }
        } catch (e: SecurityException) {
            Log.e("CallAnswer", "Missing ANSWER_PHONE_CALLS permission", e)
        } catch (e: Exception) {
            Log.e("CallAnswer", "Auto-answer failed", e)
        }
    }
}
