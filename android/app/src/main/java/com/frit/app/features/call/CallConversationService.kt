package com.frit.app.features.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.frit.app.R
import com.frit.app.core.sanitizeExtras

/**
 * Hosts CallConversationManager inside a foreground service (microphone
 * type) so the mic stays live for the whole call on Android 9+ background
 * capture rules. Shows "FRIT is on a call" with an End button.
 */
class CallConversationService : Service() {

    companion object {
        const val CHANNEL_ID = "FritCallChannel"
        const val NOTIF_ID = 7
        const val ACTION_END = "com.frit.app.CALL_END"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_OUTGOING = "outgoing"

        fun start(context: Context, number: String, outgoing: Boolean = false) {
            val i = Intent(context, CallConversationService::class.java).apply {
                putExtra(EXTRA_NUMBER, number)
                putExtra(EXTRA_OUTGOING, outgoing)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e.javaClass.simpleName == "ForegroundServiceStartNotAllowedException"
                ) {
                    android.util.Log.w("CallService", "FGS start not allowed; call service left stopped", e)
                } else {
                    android.util.Log.w("CallService", "Could not start call service", e)
                }
            }
        }

        fun end(context: Context) {
            val i = Intent(context, CallConversationService::class.java).apply {
                action = ACTION_END
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e.javaClass.simpleName == "ForegroundServiceStartNotAllowedException"
                ) {
                    android.util.Log.w("CallService", "FGS end not allowed; ignoring", e)
                } else {
                    android.util.Log.w("CallService", "Could not send end to call service", e)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        promote()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        if (intent?.action == ACTION_END) {
            CallConversationManager.stop()
            CallConversationManager.hangup(this)
            stopSelf()
            return START_NOT_STICKY
        }
        val number = intent?.getStringExtra(EXTRA_NUMBER).orEmpty()
        val outgoing = intent?.getBooleanExtra(EXTRA_OUTGOING, false) ?: false
        CallConversationManager.onDone = {
            try { stopSelf() } catch (_: Exception) {}
        }
        CallConversationManager.start(this, number, outgoing)
        return START_STICKY
    }

    override fun onDestroy() {
        CallConversationManager.stop()
        CallConversationManager.onDone = null
        super.onDestroy()
    }

    private fun promote() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "FRIT Call Handling", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val endIntent = PendingIntent.getService(
            this, 0,
            Intent(this, CallConversationService::class.java).apply { action = ACTION_END },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FRIT is on a call")
            .setContentText("AI voice conversation active — tap End to hang up")
            .setSmallIcon(R.drawable.ic_frit_notification)
            .setOngoing(true).setSilent(true)
            .addAction(0, "End call", endIntent)
            .build()
            .sanitizeExtras()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(NOTIF_ID, notif,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } catch (_: Exception) {
                    startForeground(NOTIF_ID, notif)
                }
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            android.util.Log.e("CallService", "startForeground failed", e)
        }
    }
}
