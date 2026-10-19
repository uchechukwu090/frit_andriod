
package com.frit.app.features.vision

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.frit.app.core.Diag
import androidx.core.app.NotificationCompat
import com.frit.app.R
import com.frit.app.core.sanitizeExtras

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    var virtualDisplay: VirtualDisplay? = null
        private set
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    companion object {
        private const val CHANNEL_ID = "ScreenCaptureChannel"
        private const val NOTIFICATION_ID = 2
        const val ACTION_STOP = "com.frit.app.features.vision.STOP_CAPTURE"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var lastScreenshotBase64: String? = null

        fun hasScreenshot(): Boolean = !lastScreenshotBase64.isNullOrBlank()

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
            lastScreenshotBase64 = null
            ContinuousVisionPipeline.clear()
        }

        fun captureNow() {
            // The ContinuousVisionPipeline already holds the latest changed frame
            // as base64 — surface it so callers (e.g. analyze_screenshot) get the
            // freshest captured frame without waiting for a new change.
            lastScreenshotBase64 = ContinuousVisionPipeline.getLatestBase64()
        }

        // ---- Agent-driven vision: cached MediaProjection consent ----
        // Android requires a user consent intent per boot to start capture.
        // We cache it once granted so the agent can auto-enable screen vision
        // when a task leaves the FRIT app (open_app/taps) and auto-disable on
        // return_to_frit — without re-prompting every task.
        @Volatile private var cachedResultCode: Int = Activity.RESULT_CANCELED
        @Volatile private var cachedData: Intent? = null
        @Volatile var visionAutoActive: Boolean = false
            private set

        fun cacheConsent(resultCode: Int, data: Intent?) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                cachedResultCode = resultCode
                cachedData = Intent(data)
            }
        }

        fun hasConsent(): Boolean =
            cachedResultCode == Activity.RESULT_OK && cachedData != null

        /** Start capture WITHOUT prompting if consent was cached this boot. */
        fun autoStartIfPermitted(context: Context): Boolean {
            if (isRunning || !hasConsent()) return isRunning
            return try {
                val si = Intent(context, ScreenCaptureService::class.java).apply {
                    putExtra("resultCode", cachedResultCode)
                    putExtra("data", cachedData)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(si)
                } else {
                    context.startService(si)
                }
                visionAutoActive = true
                true
            } catch (e: Exception) { Diag.warn("ScreenCapture", "autoStart", e); false }
        }

        /** Stop capture only if the AGENT auto-started it (never kill manual vision). */
        fun autoStopIfAuto(context: Context) {
            if (!visionAutoActive) return
            visionAutoActive = false
            stop(context)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        promoteToForeground()

        handlerThread = HandlerThread("ScreenCaptureThread").apply { start() }
        backgroundHandler = Handler(handlerThread!!.looper)

        // Keep lastScreenshotBase64 fresh so analyze_screenshot/take_screenshot
        // always see the latest continuously-captured frame.
        ContinuousVisionPipeline.onNewFrame = { b64 -> lastScreenshotBase64 = b64 }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            setupVirtualDisplay()
        } else {
            Log.e("ScreenCapture", "Failed to start: resultCode=$resultCode, data=${data != null}")
            stopSelf()
        }
        return START_STICKY
    }

    private fun promoteToForeground() {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Analysis Active")
            .setContentText("FRIT is analyzing your screen for context...")
            .setSmallIcon(R.drawable.frit_logo)
            .build()
            .sanitizeExtras()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } catch (_: Exception) {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Failed to promote to foreground", e)
        }
    }

    private fun setupVirtualDisplay() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, backgroundHandler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride

                    // RGBA_8888 -> pixelStride is 4 bytes. Row stride can exceed
                    // width*pixelStride (padding). Allocate a padded-width bitmap
                    // to copy the raw buffer, then crop to the true `width`.
                    val paddedWidth = rowStride / pixelStride
                    val padded = Bitmap.createBitmap(
                        paddedWidth, height, Bitmap.Config.ARGB_8888
                    )
                    padded.copyPixelsFromBuffer(buffer)

                    val bitmap = if (paddedWidth == width) {
                        padded
                    } else {
                        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                        padded.recycle()
                        cropped
                    }

                    // Continuous: pipe every frame through the OpenCV pipeline,
                    // which downscales, detects change, and keeps the latest.
                    ContinuousVisionPipeline.submit(bitmap, "screen")
                } catch (e: Exception) {
                    Log.e("ScreenCapture", "Error processing screen", e)
                } finally {
                    image.close()
                }
            }
        }, backgroundHandler)
    }

    override fun onDestroy() {
        isRunning = false
        lastScreenshotBase64 = null
        if (ContinuousVisionPipeline.onNewFrame != null) {
            ContinuousVisionPipeline.onNewFrame = null
        }
        ContinuousVisionPipeline.clear()
        virtualDisplay?.release()
        mediaProjection?.stop()
        handlerThread?.quitSafely()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Screen Capture", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
