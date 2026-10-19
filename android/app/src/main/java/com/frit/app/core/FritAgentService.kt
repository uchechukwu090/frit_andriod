package com.frit.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.frit.app.R
import com.frit.app.features.market.FritAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import java.lang.ref.WeakReference

/**
 * FritAgentService 2.0 — The Spatial AI Controller.
 */
class FritAgentService : Service() {
    private val CHANNEL_ID = "FritAgentChannel"
    private val NOTIF_ID = 4
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var windowManager: WindowManager? = null

    private var spatialOverlay: View? = null
    private var hudOverlay: View? = null

    private val highlightTarget = mutableStateOf<Rect?>(null)
    private val agentStatus = mutableStateOf("Agent Sleeping")
    private val agentThinking = mutableStateOf(false)

    private var lastHash = 0
    private var lastHeartbeatTime = 0L

    companion object {
        private const val TAG = "FritAgentService"
        var isRunning = false
        private var instanceRef: WeakReference<FritAgentService>? = null

        fun updateState(status: String, rect: Rect? = null, thinking: Boolean = false) {
            instanceRef?.get()?.let { service ->
                service.agentStatus.value = status
                service.highlightTarget.value = rect
                service.agentThinking.value = thinking
            }
        }

        fun start(context: Context) {
            if (isRunning) return
            try {
                val intent = Intent(context, FritAgentService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start FritAgentService", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FritAgentService::class.java))
        }
    }

    private val screenEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val screenChangeListener = object : FritAccessibilityService.OnScreenChangeListener {
        override fun onScreenChanged() {
            screenEvents.tryEmit(Unit)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instanceRef = WeakReference(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        promoteToForeground()

        if (canDrawOverlays()) {
            showSpatialOverlay()
            showHud()
        }

        FritAccessibilityService.addOnScreenChangeListener(screenChangeListener)
        
        scope.launch(Dispatchers.IO) {
            screenEvents
                .debounce(1500) // Slightly increased for stability
                .collectLatest {
                    try {
                        performObservation()
                    } catch (e: Exception) {
                        Log.e(TAG, "Observation loop error", e)
                    }
                }
        }
        
        // Initial observation
        screenEvents.tryEmit(Unit)
    }

    private suspend fun performObservation() {
        val now = System.currentTimeMillis()
        if (now - lastHeartbeatTime < 2000) return 

        try {
            val screenText = FritAccessibilityService.getScreenText()
            val currentHash = screenText.hashCode()

            if (currentHash != lastHash && screenText.isNotBlank()) {
                lastHash = currentHash
                lastHeartbeatTime = now
                Log.d(TAG, "Observation registered (content changed)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Observation error", e)
        }
    }

    fun executeLocalMacro(macroName: String) {
        scope.launch {
            val success = LocalMacroEngine.execute(macroName)
            if (success) {
                Log.d(TAG, "Local macro '$macroName' executed successfully")
            } else {
                Log.e(TAG, "Local macro '$macroName' failed")
            }
        }
    }

    private fun showSpatialOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )

        val composeView = ComposeView(this).apply {
            setContent {
                SpatialCanvas(highlightTarget.value)
            }
        }

        attachComposeToWindow(composeView)
        try {
            windowManager?.addView(composeView, params)
            spatialOverlay = composeView
        } catch (e: Exception) { Log.e(TAG, "Overlay fail", e) }
    }

    private fun showHud() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 200
        }

        val composeView = ComposeView(this).apply {
            setContent {
                AgentHud(agentStatus.value, agentThinking.value)
            }
        }

        attachComposeToWindow(composeView)
        try {
            windowManager?.addView(composeView, params)
            hudOverlay = composeView
        } catch (e: Exception) { Log.e(TAG, "HUD fail", e) }
    }

    @Composable
    fun SpatialCanvas(target: Rect?) {
        if (target == null) return

        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.2f, targetValue = 0.7f,
            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "alpha"
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                color = Color(0xFF00FFCC).copy(alpha = alpha),
                topLeft = Offset(target.left.toFloat(), target.top.toFloat()),
                size = Size(target.width().toFloat(), target.height().toFloat()),
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }

    @Composable
    fun AgentHud(status: String, thinking: Boolean) {
        Row(
            modifier = Modifier
                .background(Color(0xFF0D0D0D).copy(alpha = 0.92f), RoundedCornerShape(24.dp))
                .border(1.dp, Color(0xFF00FFCC).copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "think")
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.8f, targetValue = 1.2f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "scale"
            )

            Box(
                modifier = Modifier
                    .size(10.dp)
                    .graphicsLayer {
                        if (thinking) {
                            scaleX = scale
                            scaleY = scale
                        }
                    }
                    .background(if (thinking) Color(0xFF00FFCC) else Color(0xFF555555), RoundedCornerShape(5.dp))
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = status.uppercase(),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
        }
    }

    private fun attachComposeToWindow(view: View) {
        val lifecycleOwner = object : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
            private val lifecycleRegistry = LifecycleRegistry(this).apply {
                currentState = Lifecycle.State.RESUMED
            }
            private val store = ViewModelStore()
            private val controller = SavedStateRegistryController.create(this).apply {
                performRestore(null)
            }

            override val lifecycle: Lifecycle = lifecycleRegistry
            override val viewModelStore: ViewModelStore = store
            override val savedStateRegistry: SavedStateRegistry = controller.savedStateRegistry
        }
        
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeViewModelStoreOwner(lifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(this)
        } else true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        return START_STICKY
    }

    private fun promoteToForeground() {
        createNotificationChannel()
        try {
            val notif = buildNotification("FRIT Spatial Engine Active")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                }
                try {
                    startForeground(NOTIF_ID, notif, fgsType)
                } catch (_: Exception) {
                    startForeground(NOTIF_ID, notif)
                }
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        FritAccessibilityService.removeOnScreenChangeListener(screenChangeListener)
        isRunning = false
        instanceRef = null
        scope.cancel()
        spatialOverlay?.let { try { windowManager?.removeView(it) } catch (e: Exception) {} }
        hudOverlay?.let { try { windowManager?.removeView(it) } catch (e: Exception) {} }
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FRIT SPATIAL ENGINE")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_frit_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
            .sanitizeExtras()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "FRIT Engine", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}