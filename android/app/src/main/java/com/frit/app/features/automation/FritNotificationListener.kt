package com.frit.app.features.automation

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.frit.app.core.ChatSession
import com.frit.app.core.Diag
import com.frit.app.core.IntegrationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AutoMode ears: forwards notifications from USER-SELECTED apps to the agent.
 *
 * Loop guards (a reply that posts a notification must never re-trigger):
 * - own package never triggers
 * - only packages checked in AutoMode app selection
 * - per-package 5-minute cooldown
 * - blank title+text and pure progress noise ignored
 * - only fires while the AutoMode master toggle is ON
 *
 * Enablement: user grants "Notification access" in system settings
 * (Settings → AutoMode section button deep-links there).
 */
class FritNotificationListener : NotificationListenerService() {
    companion object {
        private const val TAG = "FritNotifListen"
        private const val COOLDOWN_MS = 5 * 60 * 1000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val lastFire = mutableMapOf<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val pkg = n.packageName ?: return
        try {
            if (pkg == packageName) return
            if (!AutoModeService.isEnabled(this)) return
            if (!AutoModeApps.isSelected(this, pkg)) return
            val now = System.currentTimeMillis()
            if (now - (lastFire[pkg] ?: 0L) < COOLDOWN_MS) return
            val extras = n.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
            if (title.isBlank() && text.isBlank()) return
            if (n.isOngoing && text.isBlank()) return
            lastFire[pkg] = now

            val appLabel = try {
                val ai = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(ai)?.toString() ?: pkg
            } catch (_: Exception) { pkg }

            scope.launch {
                try {
                    val prompt = "AutoMode watch: new notification from $appLabel ($pkg): title='$title' text='$text'. " +
                        "Standing policy: if this needs the owner's attention or a reply, handle it as the owner would " +
                        "(reply via the app's tools, keep it short, confirm identity-sensitive actions first; never send " +
                        "money, never delete anything, never open links blindly). If it is noise, promos, or OS chatter, " +
                        "do nothing and reply with exactly: ignored."
                    val result = IntegrationCoordinator.process(prompt, emptyList(), allowReschedule = false)
                    withContext(Dispatchers.Main) {
                        try {
                            ChatSession.init(this@FritNotificationListener)
                            ChatSession.addUser("🔔 [AutoMode] $appLabel: ${title.ifBlank { text.take(80) }}")
                            ChatSession.addAssistant(result)
                        } catch (e: Exception) { Diag.warn("AutoMode", "chat_record", e) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "automode turn failed for $pkg", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "onNotificationPosted guard failed", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
