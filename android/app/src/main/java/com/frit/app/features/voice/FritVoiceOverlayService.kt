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
import com.frit.app.core.Diag
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
import com.frit.app.ui.normalizeAssistantReplyForUi
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
                cancelChatTasks()
                runCatching {
                    replyAnimating.value = false
                    fritMode.value = FritMode.IDLE
                }
            }
        } catch (e: Exception) {
            // F-19 (MEDIUM): this catch used to be empty, so a failure to
            // install the hook meant the "stranger picks up the phone -> pause
            // everything" safety net simply did not exist — with nothing on
            // screen or in the log to say so. Log it loudly.
            Log.e(TAG, "failed to install the Veil pause hook", e)
            Diag.warn(TAG, "veil_pause_hook", e)
        }

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
                Log.e(TAG, "overlay-permission settings intent failed", e)
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (e2: Exception) {
                    // F-19 (LOW): a total failure to open any settings page was
                    // invisible. Log it — the user is otherwise told to grant
                    // a permission with no way to get there.
                    Log.e(TAG, "could not open overlay-permission settings", e2)
                    Diag.warn(TAG, "overlay_permission_settings", e2)
                }
            }
            stopSelf()
        }

        // Initialize dependencies that were in MainActivity
        // F-19 (MEDIUM): these used to be ONE try block, so a single failure
        // (say VisionEngine.init throwing) silently skipped every later init —
        // including the base-URL wiring below, which left the overlay talking
        // to the DEFAULT backend instead of the user's configured one. Each
        // subsystem is now initialised independently so one failure cannot
        // take the rest down with it.
        try { com.frit.app.core.CrashGuard.install(this) } catch (e: Exception) { Diag.warn(TAG, "init_crashguard", e) }
        try { com.frit.app.core.TokenStore.init(this) } catch (e: Exception) { Diag.warn(TAG, "init_tokenstore", e) }
        try { com.frit.app.core.PrivacyGuard.init(this) } catch (e: Exception) { Diag.warn(TAG, "init_privacyguard", e) }
        try { com.frit.app.core.ChatHistory.init(this) } catch (e: Exception) { Diag.warn(TAG, "init_chathistory", e) }
        try { ChatSession.init(this) } catch (e: Exception) { Diag.warn(TAG, "init_chatsession", e) }
        try { IntegrationCoordinator.init(this) } catch (e: Exception) { Diag.warn(TAG, "init_coordinator", e) }
        try { VoiceEngine.init(this) } catch (e: Exception) { Diag.warn(TAG, "init_voiceengine", e) }
        try { VisionEngine.init(this) } catch (e: Exception) { Diag.warn(TAG, "init_visionengine", e) }
        try { AutomationEngine.init(this) } catch (e: Exception) { Diag.warn(TAG, "init_automationengine", e) }
        try { com.frit.app.features.automation.AutomationScheduler.init(this) } catch (e: Exception) { Diag.warn(TAG, "init_scheduler", e) }
        try { MemoryEngine.init(this) } catch (e: Exception) { Diag.warn(TAG, "init_memory", e) }
        try { SubAgentOrchestrator.init(this) } catch (e: Exception) { Diag.warn(TAG, "init_subagent", e) }
        try { MT5Agent.init(this) } catch (e: Exception) { Diag.warn(TAG, "init_mt5agent", e) }
        try { com.frit.app.core.LocalInferenceEngine.init(this) } catch (e: Exception) { Diag.warn(TAG, "init_localengine", e) }

        // The wake-word service holds the microphone. While the overlay is
        // up, pause it so THIS service can record (otherwise the overlay
        // AudioRecord reads silence and "the mic doesn't function").
        try { FritWakeWordService.pauseIfRunning() } catch (e: Exception) { Diag.warn(TAG, "pause_wakeword", e) }

        // Pre-load local model — cached file only. A voice-command session must
        // never trigger a 0.4–2.5GB download; the model is fetched only from
        // Settings via ModelDownloadService.
        scope.launch(Dispatchers.IO) {
            // F-19 (MEDIUM): this launch had NO try/catch, so a failure in
            // init/loadModel propagated to the default uncaught-exception
            // handler and CRASHED the app — while every other init here was
            // individually guarded.
            try {
                com.frit.app.core.LocalInferenceEngine.init(this@FritVoiceOverlayService)
                com.frit.app.core.llm.LocalLLMEngine.loadModel(
                    this@FritVoiceOverlayService,
                    allowDownload = false,
                )
            } catch (e: Exception) { Diag.warn(TAG, "local_model_preload", e) }
        }

        // Set base URL for network clients — guarded individually so one
        // failure cannot leave the overlay on the wrong backend.
        try { NetworkClient.setBaseUrl(baseUrl.value) } catch (e: Exception) { Diag.warn(TAG, "set_baseurl_network", e) }
        try { SubAgentOrchestrator.setBaseUrl(baseUrl.value) } catch (e: Exception) { Diag.warn(TAG, "set_baseurl_subagent", e) }
        try { VoiceEngine.setBaseUrl(baseUrl.value) } catch (e: Exception) { Diag.warn(TAG, "set_baseurl_voice", e) }
        try { MarketAnalysisEngine.setBaseUrl(baseUrl.value) } catch (e: Exception) { Diag.warn(TAG, "set_baseurl_market", e) }

        // REMOVED: Auto-start Siri mode on service creation
        // We only want to start listening if explicitly requested or via handleNewInput
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        val initialText = intent?.getStringExtra("initial_text")
        val autoListen = intent?.getBooleanExtra("auto_listen", false) ?: false
        // F-19 (MEDIUM): a START_STICKY restart delivers a NULL intent. The
        // old code then auto-started the microphone (initialText == null was
        // read as "no text to send, so listen"), so after a system kill the
        // overlay reappeared over whatever app the user was in and opened
        // the mic with no tap, no wake word and no visible user intent. A
        // null intent means "the system brought me back" — not "the user
        // asked to listen" — so stay idle and only restore the foreground
        // state.
        if (intent == null) {
            Log.i(TAG, "service restarted by the system with no intent — staying idle, not auto-listening")
            return START_STICKY
        }
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
            // F-19 (MEDIUM): a failed startForeground used to be logged and
            // ignored, which left the service running as a plain background
            // service with the microphone open and no notification — an
            // invisible recorder. stopSelf() so it cannot outlive the
            // failure.
            Log.e(TAG, "Failed to start foreground service", e)
            Diag.warn(TAG, "promote_to_foreground", e)
            try { stopSelf() } catch (_: Exception) { }
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
        cancelChatTasks()
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
        // F-19: register in the SET of live jobs (not a single slot) so a
        // second in-flight send cannot make the first one uncancellable.
        val myJob = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
        if (myJob != null) chatTaskJobs.add(myJob)

        val imageToSend = pendingImageContent.value
        // F-19 (MEDIUM): the pending image was cleared BEFORE the request ran,
        // so a failed turn silently lost it — the strip disappeared and the
        // user could not re-send or edit it. Clear it only on success; on
        // failure it stays in the composer.
        var sentOk = false

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
            }.also { sentOk = true }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Overlay closed mid-task — stop silently, keep the sent message.
            replyAnimating.value = false
            thinkJob.cancel()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Chat error", e)
            Diag.warn(TAG, "chat_error", e)
            "Error: ${e.message}"
        } finally {
            // Remove ONLY this task. Clearing the whole set here is what made
            // a concurrent send uncancellable (F-19).
            if (myJob != null) chatTaskJobs.remove(myJob)
            // F-19 (MEDIUM): clear the pending image only once the turn
            // actually succeeded. On failure it stays in the composer so the
            // user can retry instead of silently losing it.
            if (sentOk && imageToSend != null) pendingImageContent.value = null
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
    // Active cloud tasks for this overlay session — cancelled on close so no
    // orphan agent keeps driving the phone after the UI is gone.
    //
    // F-19 (HIGH): this used to be a SINGLE `chatTaskJob` slot. A second
    // in-flight send overwrote the first job, and the `finally` block cleared
    // the slot UNCONDITIONALLY — so the still-running job became untracked.
    // "Stop", "close overlay" and the stranger-pickup pause all cancelled
    // nothing, and an agent that can drive the phone and place trades kept
    // running after the user cancelled it. A SET of live jobs means every
    // in-flight task is cancellable, and a finished task only removes itself.
    private val chatTaskJobs = java.util.Collections.synchronizedSet(mutableSetOf<kotlinx.coroutines.Job>())

    /** Cancel every in-flight cloud task. Safe to call from any thread. */
    private fun cancelChatTasks() {
        val jobs = synchronized(chatTaskJobs) { chatTaskJobs.toList() }
        jobs.forEach { try { it.cancel() } catch (_: Exception) { } }
        synchronized(chatTaskJobs) { chatTaskJobs.clear() }
    }

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
                        // F-19 (MEDIUM): `scope.launch` on an already-cancelled
                        // scope does NOT throw — the body simply never runs,
                        // so a transcript that arrived while the service was
                        // being torn down was silently dropped. Log it, and
                        // keep the text in the chat so it is not lost.
                        if (!scope.isActive) {
                            Log.w(TAG, "transcript arrived after the service started dying — keeping it in the chat")
                            Diag.warn(TAG, "transcript_after_teardown", null)
                            runCatching { ChatSession.addUser(text, null, emptyList()) }
                            return
                        }
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
        // F-19 (MEDIUM): the Siri path wrapped startRecording in try/catch but
        // these two did not, so a mic-unavailable / AudioRecord failure
        // propagated into the Compose click handler and CRASHED the app.
        try {
        VoiceEngine.startRecording(object : VoiceEngine.VoiceListener {
            override fun onReady() {}
            override fun onResult(text: String) {
                isQuickRecording.value = false
                if (VoiceEngine.isMeaningfulTranscript(text)) { scope.launch { sendMessage(text) } }
            }
            override fun onError(e: String) { isQuickRecording.value = false }
        })
        } catch (e: Exception) {
            isQuickRecording.value = false
            Log.e(TAG, "quick recording failed to start", e)
            Diag.warn(TAG, "quick_recording_start", e)
            android.widget.Toast.makeText(this@FritVoiceOverlayService, "Could not start recording: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun startDictation() {
        isQuickRecording.value = true
        dictateToInput.value = true
        // No haptic feedback in service directly, needs to be from UI if desired
        try {
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
        } catch (e: Exception) {
            isQuickRecording.value = false
            dictateToInput.value = false
            Log.e(TAG, "dictation failed to start", e)
            Diag.warn(TAG, "dictation_start", e)
            android.widget.Toast.makeText(this@FritVoiceOverlayService, "Could not start dictation: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun cancelListening() {
        fritMode.value = FritMode.IDLE
        // Closing/cancelling the overlay must kill its cloud work too —"
        // otherwise the agent keeps driving the phone after the UI is gone.
        cancelChatTasks()
        VoiceEngine.stopRecording()
        VoiceEngine.stopSpeaking()
        // Optionally stop the service if Siri mode is explicitly cancelled
        stopSelf()
    }

    /** Stop-button (send morphs to stop while generating — same UX as main chat). */
    fun stopOverlayChat() {
        cancelChatTasks()
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
            com.frit.app.ui.TopoBackground(isDark = isDark, modifier = Modifier.matchParentSize())
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
        // F-19 (HIGH): "End all" used to call AutomationScheduler.cancelAll(),
        // which cancels the running jobs AND permanently deletes every saved
        // schedule (including recurring jobs set for tomorrow). One tap in a
        // floating overlay destroyed the user's whole automation config, with
        // no warning and no undo. The action is now explicit and confirmed.
        var confirmEndAll by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            while (true) {
                try {
                    bgTasks.clear()
                    bgTasks.addAll(com.frit.app.features.automation.AutomationScheduler.listActiveTasks())
                } catch (e: Exception) { Diag.warn("VoiceOverlay", "bg_task_poll", e) }
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
                TextButton(onClick = { confirmEndAll = true }) { Text("End all", color = Color(0xFFFF8A80)) }
            }
        }

        if (confirmEndAll) {
            AlertDialog(
                onDismissRequest = { confirmEndAll = false },
                title = { Text("Stop and delete all tasks?", color = fg) },
                text = {
                    Text(
                        "This stops the ${bgTasks.size} background action(s) running right now AND " +
                            "permanently deletes your saved schedule — including recurring jobs set for later. " +
                            "It cannot be undone.",
                        color = fg
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        com.frit.app.features.automation.AutomationScheduler.cancelAll()
                        bgTasks.clear()
                        confirmEndAll = false
                    }) { Text("Stop and delete", color = Color(0xFFFF8A80)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmEndAll = false }) { Text("Keep them", color = fg) }
                },
                containerColor = bg,
            )
        }

        pendingImage?.let { b64 ->
            val bitmap = remember(b64) {
                try {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    // F-19 (MEDIUM): this decoded the FULL-RESOLUTION photo
                    // (~48 MB of ARGB for a 12 MP image) just to draw a 36 dp
                    // thumbnail, and kept it alive for the life of the
                    // composition — a reliable OOM on a mid-range device.
                    // Decode downsampled to the thumbnail size instead.
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    val targetPx = 128
                    while (bounds.outWidth / sample > targetPx || bounds.outHeight / sample > targetPx) {
                        sample *= 2
                    }
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                } catch (e: Exception) {
                    Diag.warn(TAG, "pending_image_decode", e)
                    null
                }
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

// F-14: the overlay used to keep its OWN private copy of this function, so
// the overlay could parse a different envelope shape than the chat UI. It now
// uses the one shared implementation.
}


