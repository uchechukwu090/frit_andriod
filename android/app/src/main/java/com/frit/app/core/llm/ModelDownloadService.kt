package com.frit.app.core.llm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.frit.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * ModelDownloadService — owns the on-device model download as a foreground
 * service.
 *
 * Why a service and not a coroutine in the Settings composable:
 *   - A `rememberCoroutineScope` is cancelled the moment the user leaves the
 *     screen, which killed the download mid-file and left a truncated GGUF
 *     that then failed its checksum.
 *   - A foreground notification is the only kind that reliably survives the
 *     app being backgrounded, and it is where the user expects to see real
 *     bytes / total / speed / destination path.
 *
 * The service does NOT decide whether to download — Settings does, after the
 * mobile-data consent dialog. It only performs the download the user asked for
 * and reports what actually happened.
 *
 * `specialUse` matches the other long-running services in this app and, unlike
 * `dataSync`, carries no 6-hour cap that could kill a large download on a slow
 * connection.
 */
class ModelDownloadService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var dlWakeLock: PowerManager.WakeLock? = null
    private var dlWifiLock: WifiManager.WifiLock? = null

    companion object {
        private const val TAG = "ModelDownload"
        const val ACTION_START = "com.frit.app.MODEL_DOWNLOAD_START"
        const val ACTION_STOP = "com.frit.app.MODEL_DOWNLOAD_STOP"
        const val EXTRA_URL = "model_url"
        private const val CHANNEL_ID = "frit_model"
        private const val NOTIF_ID = 77

        /** True while a user-requested download is in flight. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** @param url null = the built-in RAM-tier model; otherwise any https link to a .gguf file. */
        fun start(context: Context, url: String? = null) {
            val intent = Intent(context, ModelDownloadService::class.java).setAction(ACTION_START)
            if (!url.isNullOrBlank()) intent.putExtra(EXTRA_URL, url.trim())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java).setAction(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop requested")
            stopSelf()
            return START_NOT_STICKY
        }
        startDownload(intent?.getStringExtra(EXTRA_URL))
        // If the system kills us mid-download, come back with the same intent so
        // the (resumable) transfer picks up where it stopped instead of vanishing.
        return START_REDELIVER_INTENT
    }

    private fun startDownload(customUrl: String? = null) {
        if (isRunning) {
            updateNotification("Already downloading the on-device model…")
            return
        }
        isRunning = true
        promoteToForeground()

        scope.launch {
            val startedAt = System.currentTimeMillis()
            // Screen-off is what stalls minimised downloads: the CPU sleeps
            // and the radio powers down, so the socket dies. Hold both for the
            // run (released in finally; process death releases them anyway).
            acquireDownloadLocks()
            try {
                val result = if (customUrl.isNullOrBlank()) {
                    LocalLLMEngine.loadModel(
                        this@ModelDownloadService,
                        onDownloadProgress = { p -> updateNotification(progressLine(p)) },
                        // Consent was already obtained in Settings before start().
                        confirmMetered = { true },
                        allowDownload = true,
                    )
                } else {
                    // Any GGUF the user pointed us at: download, make it active, load it.
                    ModelManager.downloadCustomGguf(this@ModelDownloadService, customUrl) { p ->
                        updateNotification(progressLine(p))
                    }
                    LocalLLMEngine.unloadModel()
                    LocalLLMEngine.loadModel(this@ModelDownloadService, allowDownload = false)
                }
                val secs = (System.currentTimeMillis() - startedAt) / 1000
                if (result.success) {
                    val where = result.path?.takeIf { it.isNotBlank() }?.let {
                        ModelManager.displayPath(this@ModelDownloadService, java.io.File(it))
                    } ?: "app storage"
                    updateNotification(
                        "On-device model ready — ${result.sizeMB}MB in ${secs}s\n$where",
                        done = true,
                    )
                } else {
                    updateNotification("Model download failed: ${result.error}", done = true, failed = true)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // User tapped Stop (or the service was asked to stop): the
                // .part file stays, so this is pausable, not fatal.
                updateNotification("Download stopped — resume any time from Settings.", done = true)
            } catch (e: Exception) {
                Log.e(TAG, "Download crashed", e)
                updateNotification("Model download failed: ${e.message}", done = true, failed = true)
            } finally {
                releaseDownloadLocks()
                isRunning = false
                // Let the terminal notification linger, then release the FGS slot.
                stopSelf()
            }
        }
    }

    /**
     * Keep CPU + Wi-Fi radio alive while bytes are moving. Without these, a
     * minimised app on battery saver stalls at tens of MB and the retry loop
     * alone can't save it.
     */
    private fun acquireDownloadLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            dlWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FRIT:ModelDownload").apply {
                acquire(3 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "wakelock failed", e)
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_HIGH_PERF
            else WifiManager.WIFI_MODE_FULL
            dlWifiLock = wm.createWifiLock(mode, "FRIT:ModelDownloadWifi").apply { acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "wifilock failed", e)
        }
    }

    private fun releaseDownloadLocks() {
        try {
            dlWakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) { }
        dlWakeLock = null
        try {
            dlWifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) { }
        dlWifiLock = null
    }

    private fun progressLine(p: ModelManager.DownloadProgress): String {
        val pct = if (p.totalBytes > 0) "${p.percent}%" else "…"
        val speed = if (p.speedKbps > 0) " · ${p.speedKbps / 1024} MB/s" else ""
        val size = if (p.totalBytes > 0) {
            "${p.bytesDownloaded / 1_000_000} MB / ${p.totalBytes / 1_000_000} MB"
        } else {
            "${p.bytesDownloaded / 1_000_000} MB"
        }
        return "Downloading on-device model — $size ($pct)$speed"
    }

    private fun promoteToForeground() {
        try {
            updateNotification("Starting model download…")
            val notif = buildNotification("Preparing download…")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                }
                try {
                    startForeground(NOTIF_ID, notif, type)
                } catch (e: Exception) {
                    Log.w(TAG, "startForeground with type failed, falling back", e)
                    startForeground(NOTIF_ID, notif)
                }
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed completely", e)
        }
    }

    private fun updateNotification(text: String, done: Boolean = false, failed: Boolean = false) {
        try {
            val b = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_frit_notification)
                .setContentTitle(
                    when {
                        failed -> "On-device model download failed"
                        done -> "On-device model ready"
                        else -> "Downloading on-device model"
                    }
                )
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(!done)
                .setAutoCancel(done)
                .setOnlyAlertOnce(true)
                .setPriority(if (failed) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            if (!done && !failed) b.setProgress(100, 0, true)
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, b.build())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post download notification", e)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FRIT")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_frit_notification)
            .setOngoing(true)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress and result of on-device model downloads"
                }
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
