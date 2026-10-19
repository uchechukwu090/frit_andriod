package com.frit.app.features.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.frit.app.R
import com.frit.app.core.ChatSession
import com.frit.app.core.Diag
import com.frit.app.core.SubAgentOrchestrator
import com.frit.app.core.sanitizeExtras
import com.frit.app.features.market.MT5Agent
import com.frit.app.features.market.MarketAnalysisEngine
import com.frit.app.features.memory.MemoryEngine
import com.frit.app.features.vision.VisionEngine
import com.frit.app.features.voice.VoiceEngine
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * FritBackgroundService — persistent task queue with SQLite-backed storage.
 * Tasks survive service kills and reboots. Each task has an ID so it can be cancelled.
 * Uses START_STICKY so Android restarts it if killed.
 */
class FritBackgroundService : Service() {
    private val CHANNEL_ID = "FritBackgroundChannel"
    private val NOTIF_ID = 3
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = mutableMapOf<String, Job>()
    private var veilScreenReceiver: android.content.BroadcastReceiver? = null

    companion object {
        const val EXTRA_TASK = "task"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_DELAY = "delay"
        const val EXTRA_REPEAT_SEC = "repeat_sec"
        const val EXTRA_PKG = "pkg"
        const val ACTION_CANCEL = "com.frit.app.CANCEL_TASK"
        private const val PREFS_TASKS = "frit_task_queue"

        fun enqueue(context: Context, taskId: String, task: String, delaySeconds: Long = 0, repeatSeconds: Long = 0, pkg: String? = null) {
            val intent = Intent(context, FritBackgroundService::class.java).apply {
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_TASK, task)
                putExtra(EXTRA_DELAY, delaySeconds)
                putExtra(EXTRA_REPEAT_SEC, repeatSeconds)
                if (!pkg.isNullOrBlank()) putExtra(EXTRA_PKG, pkg)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context, taskId: String) {
            val intent = Intent(context, FritBackgroundService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_TASK_ID, taskId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()

        // Veil screen-state events (SCREEN_ON/OFF/USER_PRESENT are
        // context-only broadcasts — a manifest receiver would never get
        // them, so the persistent service registers dynamically).
        try {
            com.frit.app.features.veil.VeilManager.init(this)
            veilScreenReceiver = com.frit.app.features.veil.VeilStateReceiver()
            registerReceiver(veilScreenReceiver, com.frit.app.features.veil.VeilStateReceiver.dynamicFilter())
        } catch (e: Exception) { Diag.warn("FritBackground", "veil_receiver", e) }
        // Stranger-pickup guard: hardware motion trigger, ~zero battery.
        try {
            com.frit.app.features.veil.PickupMonitor.register(this)
        } catch (e: Exception) { Diag.warn("FritBackground", "pickup_monitor", e) }

        // --- Core Initialization for Background Independence ---
        VoiceEngine.init(this)
        VisionEngine.init(this)
        AutomationEngine.init(this)
        MemoryEngine.init(this)
        SubAgentOrchestrator.init(this)
        MT5Agent.init(this)
        com.frit.app.core.LocalInferenceEngine.init(this)

        // NOTE: the on-device model is NOT pre-loaded here. It is large
        // (0.4–2.5GB) and fetching it on every app start — with no consent, no
        // progress UI and no notification — is what made the download look like
        // a phantom. It is now fetched only when the user taps Download in
        // Settings, via ModelDownloadService. Chat routing loads it cached-only.

        val prefs = getSharedPreferences("frit_prefs", MODE_PRIVATE)
        val baseUrl = prefs.getString("base_url", com.frit.app.core.FritConfig.BASE_URL) ?: com.frit.app.core.FritConfig.BASE_URL
        com.frit.app.data.NetworkClient.setBaseUrl(baseUrl)
        SubAgentOrchestrator.setBaseUrl(baseUrl)
        VoiceEngine.setBaseUrl(baseUrl)
        MarketAnalysisEngine.setBaseUrl(baseUrl)

        restorePersistedTasks()

        // Accessibility keeper: re-nag (max 1/6h) if the system switched the
        // service off. Starts here so boot + restarts are covered too.
        com.frit.app.core.A11yKeeper.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()

        if (intent?.action == ACTION_CANCEL) {
            val id = intent.getStringExtra(EXTRA_TASK_ID)
            if (id != null) {
                activeJobs[id]?.cancel()
                activeJobs.remove(id)
                removePersistedTask(id)
                Log.d("FritBackground", "Cancelled task: $id")
            }
            if (activeJobs.isEmpty()) {
                updateNotification("FRIT Background Engine idle")
            }
            return START_STICKY
        }

        val task = intent?.getStringExtra(EXTRA_TASK) ?: return START_STICKY
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: "task_${System.currentTimeMillis()}"
        val delaySeconds = intent.getLongExtra(EXTRA_DELAY, 0L)
        val repeatSeconds = intent.getLongExtra(EXTRA_REPEAT_SEC, 0L)
        val pkg = intent.getStringExtra(EXTRA_PKG)

        persistTask(taskId, task, delaySeconds, repeatSeconds, pkg)
        launchTask(taskId, task, delaySeconds, repeatSeconds, pkg)
        return START_STICKY
    }

    private fun promoteToForeground() {
        createNotificationChannel()
        try {
            val notif = buildNotification("FRIT Background Engine active")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                }
                try {
                    startForeground(NOTIF_ID, notif, fgsType)
                } catch (e: Exception) {
                    Log.w("FritBackground", "startForeground with type failed, falling back", e)
                    startForeground(NOTIF_ID, notif)
                }
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Log.e("FritBackground", "startForeground failed completely", e)
        }
    }

    private val CHANNEL_RESULTS_ID = "FritTaskResultsChannel"

    private fun launchTask(taskId: String, task: String, delaySeconds: Long, repeatSeconds: Long, pkg: String? = null) {
        activeJobs[taskId]?.cancel()
        val job = scope.launch {
            try {
                if (delaySeconds > 0) {
                    delay(delaySeconds * 1000)
                }

                // App-bound watch tasks carry their package: prefix live app
                // context so the agent operates inside/on behalf of that app.
                val effectiveTask = if (!pkg.isNullOrBlank()) {
                    val lbl = try {
                        val ai = packageManager.getApplicationInfo(pkg, 0)
                        packageManager.getApplicationLabel(ai)?.toString()
                    } catch (_: Exception) { null } ?: pkg
                    "[AutoMode app watch: $lbl ($pkg). Perform this recurring task inside/on behalf of that app as the owner would.] $task"
                } else task
                
                while (isActive) {
                    try {
                        Log.i("FritBackground", "Executing task $taskId: $task")
                        updateNotification("Running: $task")
                        
                        // Scheduled-task pre-wake: firing while the screen is
                        // off/sleeping stalls every tap. Wake the screen, hold
                        // a CPU lock for the run, and note a secure lockscreen
                        // (PIN/password windows are FLAG_SECURE — Android hides
                        // them from automation, so full phone operation needs
                        // the user to unlock; the task still attempts).
                        SessionWakeLock.wakeScreen(this@FritBackgroundService)
                        SessionWakeLock.acquireFull(this@FritBackgroundService)
                        // Swipe-keyguard dismissal (NON-SECURE locks only — a
                        // PIN/secure lock cannot be automated, see lockNote).
                        if (SessionWakeLock.lockState(this@FritBackgroundService) == "locked_swipe") {
                            try { com.frit.app.features.market.FritAccessibilityService.executeStep("dismiss_keyguard") } catch (_: Exception) {}
                        }
                        val lockNote = when (SessionWakeLock.lockState(this@FritBackgroundService)) {
                            "locked_secure" -> " [Phone is PIN/password-locked: taps on secure windows will fail — unlock the phone for full operation.]"
                            "locked_swipe" -> " [Lockscreen showing: a swipe-up may be needed before taps land.]"
                            else -> ""
                        }
                        val t0 = System.currentTimeMillis()
                        val rawResult = withTimeoutOrNull(600000) {
                            com.frit.app.core.IntegrationCoordinator.process(effectiveTask, emptyList(), allowReschedule = false)
                        } ?: "Task timed out after 10 minutes."
                        // Overrun accounting: repeats are SEQUENTIAL — the next
                        // run starts only after this one finishes plus the
                        // interval delay — capped at 10 min each. An overrun
                        // shifts cadence but never overlaps itself.
                        val mins = (System.currentTimeMillis() - t0) / 60000
                        val result = "$rawResult$lockNote [run took ${mins}min]"
                        
                        Log.i("FritBackground", "Task $taskId result: $result")

                        // 1. Alert user via System Notification with result/error
                        postResultNotification(taskId, task, result)

                        // 2. Add to Chat Session so user sees history in the app UI
                        withContext(Dispatchers.Main) {
                            try {
                                ChatSession.init(this@FritBackgroundService)
                                ChatSession.addUser("🤖 [Automation Task] $task")
                                ChatSession.addAssistant(result)
                            } catch (e: Exception) {
                                Log.e("FritBackground", "Failed recording task output to ChatSession", e)
                            }
                        }

                        // 3. Update ongoing notification status summary
                        val shortSummary = if (result.length > 50) result.take(47) + "..." else result
                        updateNotification("Last run ($task): $shortSummary")

                    } catch (e: Exception) {
                        Log.e("FritBackground", "Execution error in task $taskId", e)
                        postResultNotification(taskId, task, "Execution error: ${e.message}")
                    }
                    
                    if (repeatSeconds > 0) {
                        delay(repeatSeconds * 1000)
                    } else {
                        break
                    }
                }
            } catch (e: CancellationException) {
                Log.d("FritBackground", "Task $taskId cancelled")
            } catch (e: Exception) {
                Log.e("FritBackground", "Task $taskId error: ${e.message}")
            } finally {
                SessionWakeLock.release()
                if (repeatSeconds == 0L) {
                    removePersistedTask(taskId)
                    activeJobs.remove(taskId)
                }
                if (activeJobs.isEmpty()) updateNotification("FRIT Background Engine idle")
            }
        }
        activeJobs[taskId] = job
    }

    private fun postResultNotification(taskId: String, taskDescription: String, resultText: String) {
        try {
            val isError = resultText.contains("error", ignoreCase = true) ||
                          resultText.contains("failed", ignoreCase = true) ||
                          resultText.contains("timed out", ignoreCase = true) ||
                          resultText.contains("401", ignoreCase = true) ||
                          resultText.contains("403", ignoreCase = true)

            val title = if (isError) "⚠️ Automation Error: $taskDescription" else "🤖 Automation Result: $taskDescription"

            val notif = NotificationCompat.Builder(this, CHANNEL_RESULTS_ID)
                .setContentTitle(title)
                .setContentText(resultText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(resultText))
                .setSmallIcon(R.drawable.ic_frit_notification)
                .setAutoCancel(true)
                .setPriority(if (isError) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .build()
                .sanitizeExtras()

            val notifId = (taskId + System.currentTimeMillis()).hashCode()
            getSystemService(NotificationManager::class.java).notify(notifId, notif)
        } catch (e: Exception) {
            Log.e("FritBackground", "Failed to post result notification", e)
        }
    }

    private fun persistTask(id: String, task: String, delaySeconds: Long, repeatSeconds: Long, pkg: String? = null) {
        val prefs = getSharedPreferences(PREFS_TASKS, Context.MODE_PRIVATE)
        val queue = try { JSONArray(prefs.getString("queue", "[]")) } catch (e: Exception) { JSONArray() }
        
        // Remove if exists
        val updated = JSONArray()
        for (i in 0 until queue.length()) {
            if (queue.getJSONObject(i).optString("id") != id) updated.put(queue.getJSONObject(i))
        }
        
        updated.put(JSONObject().apply { 
            put("id", id)
            put("task", task)
            put("delay", delaySeconds)
            put("repeat", repeatSeconds)
            if (!pkg.isNullOrBlank()) put("pkg", pkg)
        })
        prefs.edit().putString("queue", updated.toString()).apply()
    }

    private fun removePersistedTask(id: String) {
        val prefs = getSharedPreferences(PREFS_TASKS, Context.MODE_PRIVATE)
        val queue = try { JSONArray(prefs.getString("queue", "[]")) } catch (e: Exception) { JSONArray() }
        val updated = JSONArray()
        for (i in 0 until queue.length()) {
            val obj = queue.getJSONObject(i)
            if (obj.optString("id") != id) updated.put(obj)
        }
        prefs.edit().putString("queue", updated.toString()).apply()
    }

    private fun restorePersistedTasks() {
        val prefs = getSharedPreferences(PREFS_TASKS, Context.MODE_PRIVATE)
        val queue = try { JSONArray(prefs.getString("queue", "[]")) } catch (e: Exception) { JSONArray() }
        for (i in 0 until queue.length()) {
            val obj = queue.getJSONObject(i)
            launchTask(obj.optString("id"), obj.optString("task"), obj.optLong("delay", 0L), obj.optLong("repeat", 0L), obj.optString("pkg", null))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            veilScreenReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) { Diag.warn("FritBackground", "unregister_veil", e) }
        veilScreenReceiver = null
        try {
            com.frit.app.features.veil.PickupMonitor.unregister()
        } catch (e: Exception) { Diag.warn("FritBackground", "unregister_pickup", e) }
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FRIT")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_frit_notification)
            .setOngoing(true).setSilent(true)
            .build()
            .sanitizeExtras()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "FRIT Background", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULTS_ID, "FRIT Task Results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Notifications for completed FRIT automated tasks and trading alerts"
                }
            )
        }
    }
}
