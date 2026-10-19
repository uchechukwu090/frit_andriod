package com.frit.app.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.frit.app.MainActivity
import com.frit.app.R
import com.frit.app.data.NetworkClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * CreatorWatcher — polls /admin/inbox while creator watch is on and posts a
 * system notification for every NEW pending payment / withdrawal, with an
 * approve prompt. Replies (approve/decline) go out with the ADMIN_TOKEN via
 * NetworkClient.admin* — the app/user token can never approve anything.
 *
 * Runs only on the creator's own phone, only when creator mode is enabled in
 * Creator console. Poll every 60s; seen IDs persist in frit_prefs.
 */
object CreatorWatcher {
    private const val TAG = "CreatorWatcher"
    private const val CHANNEL = "frit_creator"
    private const val SEEN_KEY = "creator_seen_ids"

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun sync(context: Context) {
        val want = try { AdminStore.creatorMode() } catch (_: Exception) { false }
        if (want && job?.isActive != true) start(context.applicationContext)
        if (!want) stop()
    }

    fun start(context: Context) {
        if (job?.isActive == true) return
        ensureChannel(context)
        job = scope.launch {
            while (isActive) {
                try { pollOnce(context) } catch (e: Exception) {
                    Log.w(TAG, "poll failed", e)
                }
                delay(60_000)
            }
        }
        Log.i(TAG, "creator watch started")
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun seenSet(context: Context): MutableSet<String> =
        context.getSharedPreferences("frit_prefs", Context.MODE_PRIVATE)
            .getStringSet(SEEN_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun markSeen(context: Context, ids: Set<String>) {
        val cur = seenSet(context)
        cur.addAll(ids)
        while (cur.size > 300) cur.remove(cur.first())
        context.getSharedPreferences("frit_prefs", Context.MODE_PRIVATE)
            .edit().putStringSet(SEEN_KEY, cur).apply()
    }

    private suspend fun pollOnce(context: Context) {
        val raw = NetworkClient.adminInbox()
        val o = try { JSONObject(raw) } catch (_: Exception) { return }
        if (o.optString("status") == "error") return
        val seen = seenSet(context)
        val fresh = mutableListOf<Pair<String, String>>() // id -> title
        val payments = o.optJSONArray("payments")
        for (i in 0 until (payments?.length() ?: 0)) {
            val p = payments.getJSONObject(i)
            val id = p.optString("id")
            if (id.isNotBlank() && !seen.contains("p:$id")) {
                val amt = "₦${"%,.0f".format(p.optLong("amount_kobo", 0) / 100.0)}"
                fresh.add("p:$id" to "Payment $amt · ${p.optString("tier", p.optString("purpose", ""))} · ${p.optString("user_id", "?")}")
            }
        }
        val withdrawals = o.optJSONArray("withdrawals")
        for (i in 0 until (withdrawals?.length() ?: 0)) {
            val w = withdrawals.getJSONObject(i)
            val id = w.optString("id")
            if (id.isNotBlank() && !seen.contains("w:$id")) {
                val amt = "₦${"%,.0f".format(w.optLong("amount_kobo", 0) / 100.0)}"
                fresh.add("w:$id" to "Withdrawal $amt → ${w.optString("bank_name", "?")} ${w.optString("account_number", "?")}")
            }
        }
        if (fresh.isEmpty()) return
        markSeen(context, fresh.map { it.first }.toSet())
        fresh.forEachIndexed { idx, (_, title) ->
            notify(
                context, 9000 + idx, "FRIT payment needs you",
                "$title. Open Creator console to approve."
            )
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Creator payments", NotificationManager.IMPORTANCE_HIGH)
            )
        } catch (e: Exception) { Diag.warn("Creator", "channel", e) }
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        try {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_creator", true)
            val pi = PendingIntent.getActivity(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val icon = R.drawable.ic_frit_notification
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: Exception) {
            Log.w(TAG, "notify failed", e)
        }
    }
}
