package com.frit.app.features.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.asImageBitmap
import android.util.Base64
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import com.frit.app.core.sanitizeExtras
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
import com.frit.app.core.ChatSession
import com.frit.app.core.FritMode
import com.frit.app.core.IntegrationCoordinator
import com.frit.app.core.SubAgentOrchestrator
import com.frit.app.data.ChatMessage
import com.frit.app.data.ReplyQuote
import com.frit.app.data.NetworkClient
import com.frit.app.features.wake.FritWakeWordService
import com.frit.app.features.automation.AutomationEngine
import com.frit.app.features.market.MT5Agent
import com.frit.app.features.market.MarketAnalysisEngine
import com.frit.app.features.memory.MemoryEngine
import com.frit.app.features.vision.VisionEngine
import com.frit.app.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.ref.WeakReference

class FritVoiceOverlayService : Service() {

    private val CHANNEL_ID = "FritVoiceOverlayChannel"
    private val NOTIF_ID = 5
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var dimOverlay: View? = null
    private var serviceLifecycleOwner: ServiceLifecycleOwner? = null

    class ServiceLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = lifecycleRegistry

        private val store = ViewModelStore()
        override val viewModelStore: ViewModelStore = store

        private val controller = SavedStateRegistryController.create(this)
        override val savedStateRegistry: SavedStateRegistry = controller.savedStateRegistry

        fun onCreate() {
            controller.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun onDestroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            store.clear()
        }
    }

    // State for the chat UI, moved from MainActivity
    private val fritMode = mutableStateOf(FritMode.IDLE)
    // Pause->send revert signal: true while a reply is still typing, cleared when
    // the assistant bubble finishes its typewriter animation.
    private val replyAnimating = mutableStateOf(false)
    private val input = mutableStateOf("")
    // Multi-quote reply drafts (Messenger style, max 3), shared with the
    // input strip below and folded into the next send for the brain.
    private val replyQuotes = mutableStateListOf<ReplyQuote>()
    private val thinkSteps = mutableStateListOf<String>()
    private val isQuickRecording = mutableStateOf(false)
    // Dictation mode: mic tap records once and drops the transcript into the
    // text field (appended) instead of sending it as a message.
    private val dictateToInput = mutableStateOf(false)
    private val isListeningPaused = mutableStateOf(false)
    private val pendingImageContent = mutableStateOf<String?>(null)
    private lateinit var prefs: android.content.SharedPreferences
    private val baseUrl = mutableStateOf(com.frit.app.core.FritConfig.BASE_URL)
    private val voiceEnabled = mutableStateOf(true)
    private val automationEnabled = mutableStateOf(true)
    private val isDark = mutableStateOf(false)

    companion object {
        private const val TAG = "FritVoiceOverlayService"
        var isRunning = false
        private var instanceRef: WeakReference<FritVoiceOverlayService>? = null

        fun start(context: Context, initialText: String? = null) {
            val current = instanceRef?.get()
            if (current != null) {
                current.handleNewInput(initialText)
                return
            }
            val intent = Intent(context, FritVoiceOverlayService::class.java).apply {
                initialText?.let { putExtra("initial_text", it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FritVoiceOverlayService::class.java))
        }

        fun updateOverlayState(mode: FritMode? = null, newMessages: List<ChatMessage>? = null, newThinkSteps: List<String>? = null, newIsQuickRecording: Boolean? = null) {
            instanceRef?.get()?.let { service ->
                mode?.let { service.fritMode.value = it }
                newMessages?.let {
                    ChatSession.messages.clear()
                    ChatSession.messages.addAll(it)
                }
                newThinkSteps?.let {
                    service.thinkSteps.clear()
                    service.thinkSteps.addAll(it)
                }
                newIsQuickRecording?.let { service.isQuickRecording.value = it }
            }
        }

        /** Called by OverlayImagePickerActivity after a photo is selected. */
        fun notifyImagePicked(base64: String) {
            instanceRef?.get()?.onImagePicked(base64)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instanceRef = WeakReference(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("frit_prefs", MODE_PRIVATE)

        // Veil stranger guard: a pickup pauses tasks by killing live voice turns.
        try {
            com.frit.app.features.veil.VeilManager.onTasksPaused {
                try {
                    chatTaskJob?.cancel()
                } catch (_: Exception) { }
                chatTaskJob = null
                try {
                    replyAnimating.value = false
                    fritMode.value = FritMode.IDLE
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        // Load preferences safely after context is attached
        baseUrl.value = prefs.getString("base_url", com.frit.app.core.FritConfig.BASE_URL) ?: com.frit.app.core.FritConfig.BASE_URL
        voiceEnabled.value = prefs.getBoolean("voice_enabled", true)
        automationEnabled.value = prefs.getBoolean("automation_enabled", true)
        isDark.value = prefs.getBoolean("dark_theme", false)

        promoteToForeground()

        if (canDrawOverlays()) {
            showOverlay()
        } else {
            Log.e(TAG, "Cannot draw overlays. Requesting permission.")
            try {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(this, "Please grant 'Display over other apps' permission for FRIT.", android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (_: Exception) {}
            }
            stopSelf()
        }

        // Initialize dependencies that were in MainActivity
        try {
            com.frit.app.core.CrashGuard.install(this)
            com.frit.app.core.TokenStore.init(this)
            com.frit.app.core.PrivacyGuard.init(this)
            com.frit.app.core.ChatHistory.init(this)
            ChatSession.init(this)
            IntegrationCoordinator.init(this)
            VoiceEngine.init(this)
            VisionEngine.init(this)
            AutomationEngine.init(this)
            com.frit.app.features.automation.AutomationScheduler.init(this)
            MemoryEngine.init(this)
            SubAgentOrchestrator.init(this)
            MT5Agent.init(this)
            com.frit.app.core.LocalInferenceEngine.init(this)

            // The wake-word service holds the microphone. While the overlay is
            // up, pause it so THIS service can record (otherwise the overlay
            // AudioRecord reads silence and "the mic doesn't function").
            FritWakeWordService.pauseIfRunning()

            // Pre-load local model — cached file only. A voice-command session must
            // never trigger a 0.4–2.5GB download; the model is fetched only from
            // Settings via ModelDownloadService.
            scope.launch(Dispatchers.IO) {
                com.frit.app.core.LocalInferenceEngine.init(this@FritVoiceOverlayService)
                com.frit.app.core.llm.LocalLLMEngine.loadModel(
                    this@FritVoiceOverlayService,
                    allowDownload = false,
                )
            }

            // Set base URL for network clients
            NetworkClient.setBaseUrl(baseUrl.value)
            SubAgentOrchestrator.setBaseUrl(baseUrl.value)
            VoiceEngine.setBaseUrl(baseUrl.value)
            MarketAnalysisEngine.setBaseUrl(baseUrl.value)
        } catch (e: Exception) {
            Log.e(TAG, "Initialization error", e)
        }

        // REMOVED: Auto-start Siri mode on service creation
        // We only want to start listening if explicitly requested or via handleNewInput
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        val initialText = intent?.getStringExtra("initial_text")
        val autoListen = intent?.getBooleanExtra("auto_listen", false) ?: false
        handleNewInput(initialText, autoListen)
        return START_STICKY
    }

    private fun promoteToForeground() {
        createNotificationChannel()
        try {
            val notif = buildNotification("FRIT Voice Assistant Active")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
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

    private fun handleNewInput(initialText: String?, autoListen: Boolean = false) {
        initialText?.let {
            if (it.isNotBlank()) {
                scope.launch { sendMessage(it) }
                return
            }
        }
        // Auto-start listening (Gemini-style) when triggered via wake word
        if (autoListen || (initialText == null && (fritMode.value == FritMode.IDLE || fritMode.value == FritMode.SPEAKING))) {
            fritMode.value = FritMode.LISTENING
            startSiriMode()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        instanceRef = null
        chatTaskJob?.cancel()
        chatTaskJob = null
        scope.cancel()
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { Log.e(TAG, "Error removing overlay view", e) }
        }
        dimOverlay?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { Log.e(TAG, "Error removing dim overlay", e) }
        }
        VoiceEngine.stopRecording()
        VoiceEngine.stopSpeaking()
        FritWakeWordService.resumeIfRunning()
        serviceLifecycleOwner?.onDestroy()
        serviceLifecycleOwner = null
        super.onDestroy()
    }

    private fun showOverlay() {
        // Create a semi-transparent dim background behind the overlay panel
        val dimParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.FILL
            dimAmount = 0.4f
        }

        val dimView = View(this).apply {
            setBackgroundColor(android.graphics.Color.argb(100, 0, 0, 0))
            setOnClickListener { cleanupOverlay() }
        }
        dimOverlay = dimView
        try { windowManager?.addView(dimView, dimParams) } catch (e: Exception) {}

        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            windowAnimations = android.R.style.Animation_InputMethod
        }

        val composeView = ComposeView(this).apply {
            setContent {
                var isFocused by remember { mutableStateOf(false) }
                
                LaunchedEffect(isFocused) {
                    if (isFocused) {
                        panelParams.flags = panelParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                    } else {
                        panelParams.flags = panelParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    }
                    try { windowManager?.updateViewLayout(this@apply, panelParams) } catch (e: Exception) {}
                }

                val offset by animateFloatAsState(
                    targetValue = 0f,
                    animationSpec = tween(500, easing = FastOutSlowInEasing),
                    label = "slideUp"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { translationY = offset }
                ) {
                    OverlayChatScreen(
                        currentFritMode = fritMode.value,
                        messages = ChatSession.messages,
                        currentInput = input.value,
                        onInputChange = { input.value = it },
                        onSend = { text -> scope.launch { sendMessage(text) } },
                        onVoiceTap = {
                            // Tap while recording = stop & end (both Siri and quick).
                            if (fritMode.value == FritMode.LISTENING || isQuickRecording.value || VoiceEngine.isRecordingActive()) {
                                isStartingRecording = false
                                isQuickRecording.value = false
                                dictateToInput.value = false
                                isListeningPaused.value = false
                                fritMode.value = FritMode.IDLE
                                VoiceEngine.stopRecording()
                            } else {
                                startDictation()
                            }
                        },
                        onVoiceLongPress = { startSiriMode() },
                        onCancel = { cancelListening() },
                        onCloseOverlay = { cleanupOverlay() },
                        isDark = isDark.value,
                        thinkSteps = thinkSteps,
                        onFocusChange = { isFocused = it },
                        pendingImage = pendingImageContent.value,
                        onAttachImage = { openImagePicker() }
                    )
                }
            }
        }

        attachComposeToWindow(composeView)
        try {
            windowManager?.addView(composeView, panelParams)
            overlayView = composeView
        } catch (e: Exception) { Log.e(TAG, "Overlay fail", e) }
    }

    private fun cleanupOverlay() {
        try { dimOverlay?.let { windowManager?.removeView(it) } } catch (e: Exception) {}
        stopSelf()
    }

    private fun attachComposeToWindow(view: View) {
        val owner = ServiceLifecycleOwner().also {
            it.onCreate()
        }
        serviceLifecycleOwner = owner
        
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(this)
        } else true
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FRIT Voice Assistant")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_frit_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
            .sanitizeExtras()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) { Log.e(TAG, "updateNotification error", e) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "FRIT Voice Overlay", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private suspend fun sendMessage(text: String) {
        if (text.isBlank() && pendingImageContent.value == null) return
        // Track the cloud task so overlay close/cancel aborts it mid-flight.
        chatTaskJob = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]

        val imageToSend = pendingImageContent.value
        pendingImageContent.value = null

        // Fold quoted context into what the brain receives; the stored message
        // keeps clean text + structured quotes for rendering/reload.
        val quotePrefix = if (replyQuotes.isEmpty()) "" else replyQuotes.joinToString("\n") {
            "> @${if (it.role == "user") "you" else "FRIT"}: ${it.text}"
        } + "\n\n"
        ChatSession.addUser(text, imageToSend, replyQuotes.toList())
        replyQuotes.clear()
        input.value = ""
        // Stop recording if currently in listening mode, as we are transitioning to thinking/speaking
        if (fritMode.value == FritMode.LISTENING || fritMode.value == FritMode.SPEAKING) {
            try { VoiceEngine.stopRecording() } catch (e: Exception) { Log.e(TAG, "stopRecording error", e) }
        }
        fritMode.value = FritMode.THINKING
        thinkSteps.clear()

        val thinkJob = scope.launch {
            val isLocal = com.frit.app.core.llm.LocalLLMEngine.isModelLoaded()
            val steps = if (isLocal) {
                listOf("On-device reasoning...", "Parsing screen intent...", "Local agent active...")
            } else {
                listOf("Cloud brain thinking...", "Coordinating multi-agents...", "Finalizing AI response...")
            }
            steps.forEach { if (isActive && isRunning) { thinkSteps.add(it); delay(1000) } }
        }

        val reply = try {
            withContext(Dispatchers.IO) {
                IntegrationCoordinator.process(quotePrefix + text, ChatSession.compactHistory(), imageToSend)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Overlay closed mid-task — stop silently, keep the sent message.
            replyAnimating.value = false
            thinkJob.cancel()
            throw e
        } catch (e: Exception) { 
            Log.e(TAG, "Chat error", e)
            "Error: ${e.message}" 
        } finally {
            chatTaskJob = null
        }

        thinkJob.cancel()
        thinkSteps.clear()

        if (!isRunning) return

        val cleanReply = try { normalizeAssistantReplyForUi(reply) } catch (e: Exception) { reply }
        val artifacts = IntegrationCoordinator.lastArtifacts.value
        IntegrationCoordinator.clearArtifacts()
        // Revert stop->send once the reply bubble finishes typing.
        replyAnimating.value = true
        ChatSession.addAssistant(cleanReply, artifacts) {
            replyAnimating.value = false
        }
        fritMode.value = FritMode.SPEAKING
        
        // Delay slightly before speaking/restarting to avoid race conditions
        delay(300)
        if (!isRunning) return
        
        if (voiceEnabled.value) {
            try {
                VoiceEngine.speak(cleanReply) {
                    if (isRunning) {
                        // Continuous multi-turn dialogue loop: automatically resume listening for the next question
                        fritMode.value = FritMode.LISTENING
                        startSiriMode()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Speak error", e)
                fritMode.value = FritMode.IDLE
            }
        } else {
            delay(3000) // Let the user read the text
            if (isRunning) {
                fritMode.value = FritMode.LISTENING
                startSiriMode()
            }
        }
    }

    /** Pause Siri listening without closing the overlay (resume via mic tap). */
    private fun pauseSiri() {
        if (fritMode.value != FritMode.LISTENING) return
        isListeningPaused.value = true
        VoiceEngine.stopRecording()
    }

    private fun resumeSiri() {
        isListeningPaused.value = false
        startSiriMode()
    }

    fun onImagePicked(base64: String) {
        if (!isRunning) return
        scope.launch {
            pendingImageContent.value = base64
            updateNotification("Image attached — send or type a follow-up.")
        }
    }

    private fun openImagePicker() {
        try {
            val intent = Intent(this, OverlayImagePickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open image picker", e)
        }
    }

    private var isStartingRecording = false
    // Active cloud task for this overlay session — cancelled on close so no
    // orphan agent keeps driving the phone after the UI is gone.
    private var chatTaskJob: kotlinx.coroutines.Job? = null
    private var consecutiveVoiceErrors = 0
    private val MAX_VOICE_ERRORS = 3

    fun startSiriMode() {
        if (!isRunning) return
        // Prevent double-starting recording
        if (isStartingRecording) {
            Log.d(TAG, "Siri Mode: Already starting recording, skipping")
            return
        }
        if (fritMode.value == FritMode.THINKING) {
            Log.d(TAG, "Siri Mode: Currently thinking, skipping")
            return
        }
        if (consecutiveVoiceErrors >= MAX_VOICE_ERRORS) {
            Log.w(TAG, "Siri Mode: Too many consecutive voice errors ($consecutiveVoiceErrors). Stopping auto-retry.")
            fritMode.value = FritMode.IDLE
            ChatSession.addAssistant("Voice listening stopped after repeated errors. Tap the microphone to try again.")
            consecutiveVoiceErrors = 0
            return
        }

        isStartingRecording = true
        fritMode.value = FritMode.LISTENING
        try {
            VoiceEngine.startRecording(object : VoiceEngine.VoiceListener {
                override fun onReady() {
                    isStartingRecording = false
                    Log.d(TAG, "Siri Mode: Ready to listen")
                }
                override fun onResult(text: String) {
                    isStartingRecording = false
                    consecutiveVoiceErrors = 0
                    if (!isRunning) return
                    if (VoiceEngine.isMeaningfulTranscript(text)) {
                        fritMode.value = FritMode.THINKING
                        scope.launch { sendMessage(text) }
                    } else {
                        if (fritMode.value == FritMode.LISTENING && !isListeningPaused.value) {
                            Log.d(TAG, "Siri Mode: Empty result, restarting...")
                            scope.launch {
                                delay(500)
                                startSiriMode()
                            }
                        }
                    }
                }
                override fun onError(e: String) {
                    isStartingRecording = false
                    consecutiveVoiceErrors++
                    Log.e(TAG, "Voice recording error ($consecutiveVoiceErrors/$MAX_VOICE_ERRORS): $e")
                    if (isRunning && (fritMode.value == FritMode.LISTENING || fritMode.value == FritMode.SPEAKING) && !isListeningPaused.value) {
                        scope.launch {
                            delay(2000) // Backoff delay on error
                            startSiriMode()
                        }
                    }
                }
            })
        } catch (e: Exception) {
            isStartingRecording = false
            consecutiveVoiceErrors++
            Log.e(TAG, "startRecording exception: ${e.message}")
            if (isRunning) {
                scope.launch {
                    delay(2000)
                    startSiriMode()
                }
            }
        }
    }

    fun startQuickRecording() {
        isQuickRecording.value = true
        // No haptic feedback in service directly, needs to be from UI if desired
        VoiceEngine.startRecording(object : VoiceEngine.VoiceListener {
            override fun onReady() {}
            override fun onResult(text: String) {
                isQuickRecording.value = false
                if (VoiceEngine.isMeaningfulTranscript(text)) { scope.launch { sendMessage(text) } }
            }
            override fun onError(e: String) { isQuickRecording.value = false }
        })
    }

    fun startDictation() {
        isQuickRecording.value = true
        dictateToInput.value = true
        // No haptic feedback in service directly, needs to be from UI if desired
        VoiceEngine.startRecording(object : VoiceEngine.VoiceListener {
            override fun onReady() {}
            override fun onResult(text: String) {
                isQuickRecording.value = false
                dictateToInput.value = false
                if (VoiceEngine.isMeaningfulTranscript(text)) {
                    val cur = input.value
                    input.value = if (cur.isBlank()) text else "${cur.trimEnd()} $text"
                } else {
                    android.widget.Toast.makeText(this@FritVoiceOverlayService, "Didn't catch that — try again", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            override fun onError(e: String) {
                isQuickRecording.value = false
                dictateToInput.value = false
                android.widget.Toast.makeText(this@FritVoiceOverlayService, "Voice error: $e", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
    }

    fun cancelListening() {
        fritMode.value = FritMode.IDLE
        // Closing/cancelling the overlay must kill its cloud work too —
        // otherwise the agent keeps driving the phone after the UI is gone.
        chatTaskJob?.cancel()
        chatTaskJob = null
        VoiceEngine.stopRecording()
        VoiceEngine.stopSpeaking()
        // Optionally stop the service if Siri mode is explicitly cancelled
        stopSelf()
    }

    /** Stop-button (send morphs to stop while generating — same UX as main chat). */
    fun stopOverlayChat() {
        chatTaskJob?.cancel()
        chatTaskJob = null
        replyAnimating.value = false
        VoiceEngine.stopRecording()
        VoiceEngine.stopSpeaking()
        fritMode.value = FritMode.IDLE
    }

    //region Composable UI for Overlay
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun OverlayChatScreen(
    currentFritMode: FritMode,
    messages: List<ChatMessage>,
    currentInput: String,
    onInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onVoiceTap: () -> Unit,
    onVoiceLongPress: () -> Unit,
    onCancel: () -> Unit,
    onCloseOverlay: () -> Unit,
    isDark: Boolean,
    thinkSteps: List<String>,
    onFocusChange: (Boolean) -> Unit = {},
    pendingImage: String? = null,
    onAttachImage: () -> Unit = {}
) {
    val bg     = if (isDark) Color(0xFF111110) else FritCream
    val fg     = if (isDark) FritCream        else FritEspresso
    val surf   = if (isDark) Color(0xFF1C1A18) else FritSurface
    val stroke = if (isDark) FritCream.copy(alpha = 0.22f) else FritStroke
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, thinkSteps.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .imePadding() // Automatically adjusts bounds when typing keyboard rises so text is never hidden
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(bg)
                .border(0.5.dp, stroke.copy(alpha = 0.6f), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
        ) {
            // Theme contour-line art behind the panel — same asset as full chat.
            com.frit.app.TopoBackground(isDark = isDark, modifier = Modifier.matchParentSize())
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg.copy(alpha = 0.85f))
                    .clickable { onFocusChange(false) } // Click background to unfocus
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Low-profile top bar that enters full app on tap
                Row(
                    modifier = Modifier.clickable {
                        val intent = Intent(this@FritVoiceOverlayService, com.frit.app.MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        cleanupOverlay()
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(24.dp, 4.dp).background(fg.copy(alpha = 0.2f), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    // Theme-aware logo (white variant in dark mode, same proportions).
                    com.frit.app.ui.FritWordmark(color = fg, isDark = isDark)
                }

                IconButton(onClick = onCloseOverlay, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, "Close", tint = fg.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                }
            }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp, min = 80.dp),
            state = listState,
            contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp)
        ) {
            itemsIndexed(messages) { idx, msg ->
                ChatBubble(msg, idx, fg, bg, surf, stroke, isDark, onReply = { m ->
                    val t = m.content.take(200)
                    if (replyQuotes.size < 3 && replyQuotes.none { it.text == t }) {
                        replyQuotes.add(ReplyQuote(m.role, t))
                    }
                })
            }
            if (currentFritMode == FritMode.THINKING) {
                item { com.frit.app.ui.FritThinkingBar(thinkSteps, fg) }
            }
        }

        // Background-task banner (overlay parity with main chat): the reply
        // animation is done but automation may still run underneath.
        val bgTasks = remember { mutableStateListOf<String>() }
        LaunchedEffect(Unit) {
            while (true) {
                try {
                    bgTasks.clear()
                    bgTasks.addAll(com.frit.app.features.automation.AutomationScheduler.listActiveTasks())
                } catch (_: Exception) {}
                delay(5000)
            }
        }
        if (bgTasks.isNotEmpty() && currentFritMode == FritMode.IDLE) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(fg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "⚙️ ${bgTasks.size} background action(s) still running",
                    color = bg, fontSize = 13.sp, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    com.frit.app.features.automation.AutomationScheduler.cancelAll()
                    bgTasks.clear()
                }) { Text("End all", color = Color(0xFFFF8A80)) }
            }
        }

        pendingImage?.let { b64 ->
            val bitmap = remember(b64) {
                try {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) { null }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Pending image",
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp))
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Image attached — type to add context or send",
                    color = fg.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    fontFamily = Montserrat
                )
            }
        }

        com.frit.app.ui.ReplyQuoteStrip(
            quotes = replyQuotes, fg = fg, surf = surf, stroke = stroke,
            onRemove = { replyQuotes.removeAt(it) }
        )
        OverlayInputBar(
            input = currentInput,
            onInputChange = onInputChange,
            onSend = {
                onSend(it)
                onFocusChange(false)
            },
            onVoiceTap = onVoiceTap,
            onVoiceLongPress = onVoiceLongPress,
            fg = fg, bg = bg, surf = surf, stroke = stroke,
            isListening = currentFritMode == FritMode.LISTENING || isQuickRecording.value,
            isGenerating = replyAnimating.value,
            onStop = { stopOverlayChat() },
            onFocusChange = onFocusChange,
            onAttachImage = onAttachImage
        )

        AnimatedVisibility(
            visible = currentFritMode == FritMode.LISTENING || isQuickRecording.value,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).align(Alignment.CenterHorizontally)
        ) {
            RecordingOverlay(
                fg = fg,
                bg = bg,
                isSiriMode = currentFritMode == FritMode.LISTENING,
                isPaused = isListeningPaused.value
            )
        }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OverlayInputBar(
    input: String, onInputChange: (String) -> Unit, onSend: (String) -> Unit,
    onVoiceTap: () -> Unit, onVoiceLongPress: () -> Unit,
    fg: Color, bg: Color, surf: Color, stroke: Color, isListening: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onAttachImage: () -> Unit = {},
    onAttachLongPress: () -> Unit = {},
    isGenerating: Boolean = false,
    onStop: () -> Unit = {}
) {
    // Delegate to the shared designer so overlay and full app stay in lockstep.
    com.frit.app.ui.FritInputBar(
        input = input,
        onInputChange = onInputChange,
        onSend = { if (input.isNotBlank()) onSend(input) },
        onVoiceTap = onVoiceTap,
        onVoiceLongPress = onVoiceLongPress,
        onAttachImage = onAttachImage,
        onAttachLongPress = onAttachLongPress,
        fg = fg,
        bg = bg,
        surf = surf,
        stroke = stroke,
        isListening = isListening,
        isGenerating = isGenerating,
        onStop = onStop,
        compact = true,
        onFocusChange = onFocusChange
    )
}

// Re-use shared UI: bubbles, input bar, thinking bar, watermark, and wordmark
// all live in com.frit.app.ui. We only keep overlay-specific helpers here.

@Composable
fun RecordingOverlay(fg: Color, bg: Color, isSiriMode: Boolean, isPaused: Boolean = false) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(0.5.dp, fg.copy(alpha = 0.25f), RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0.9f, targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha"
        )
        Box(Modifier.size(7.dp).background(fg.copy(alpha = alpha), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            text = when {
                isPaused -> "Paused — tap mic to resume"
                isSiriMode -> "Listening"
                else -> "Recording"
            },
            color = fg,
            fontFamily = Montserrat,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun ChatBubble(msg: ChatMessage, idx: Int, fg: Color, bg: Color, surf: Color, stroke: Color, isDark: Boolean = false, onReply: ((ChatMessage) -> Unit)? = null) {
    // Drop the duplicate rendering — reuse the same shared bubble the full app uses.
    @Suppress("UNUSED_PARAMETER") val ignoredIdx = idx
    @Suppress("UNUSED_PARAMETER") val ignoredSurf = surf
    @Suppress("UNUSED_PARAMETER") val ignoredStroke = stroke
    val context = LocalContext.current
    fun lastUserTask(): String =
        ChatSession.messages.lastOrNull { it.role == "user" }?.content.orEmpty()
    fun toast(t: String) =
        android.widget.Toast.makeText(context, t, android.widget.Toast.LENGTH_SHORT).show()
    com.frit.app.ui.FritChatBubble(
        msg = msg,
        isDark = isDark,
        fg = fg, bg = bg,
        surf = if (isDark) Color(0xFF1C1A18) else com.frit.app.ui.theme.FritSurface,
        stroke = if (isDark) FritCream.copy(alpha = 0.22f) else com.frit.app.ui.theme.FritStroke,
        onThumbsUp = { reply ->
            MemoryEngine.addFeedback(true, lastUserTask(), reply)
            toast("Noted — I'll do it this way again")
        },
        onThumbsDown = { reply ->
            MemoryEngine.addFeedback(false, lastUserTask(), reply)
            toast("Saved — I'll avoid this approach next time")
        },
        onReply = onReply,
        compact = false
    )
}

fun isActionIntent(text: String): Boolean {
    val t = text.lowercase()
    return t.contains("open") || t.contains("go to") || t.contains("click") || 
           t.contains("type") || t.contains("tap") || t.contains("scroll") || 
           t.contains("send") || t.contains("call") || t.contains("trade") ||
           t.contains("buy") || t.contains("sell") || t.contains("navigate")
}

// Only handle SIMPLE local actions through AutomationEngine, not complex ones
// that trigger the agentic loop (which starts FritAgentService and can crash)
private fun isSimpleAutomationIntent(text: String): Boolean {
    val t = text.lowercase().trim()
    return t.startsWith("open ") || t.startsWith("launch ") ||
           t.startsWith("call ") || t.startsWith("dial ") ||
           t.startsWith("navigate to ") || t.startsWith("go to ") ||
           t.startsWith("set alarm") || t.startsWith("set timer") ||
           t.startsWith("text ") || t.startsWith("sms ") ||
           t.startsWith("play ") || t.startsWith("listen to ")
}

fun normalizeAssistantReplyForUi(reply: String): String {
    if (reply.contains("assistant_text")) {
        try {
            val json = JSONObject(reply)
            return json.optString("assistant_text", reply)
        } catch (e: Exception) {}
    }
    return reply
}

}


