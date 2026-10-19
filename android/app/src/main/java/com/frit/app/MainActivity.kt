package com.frit.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.frit.app.core.*
import com.frit.app.data.ChatMessage
import com.frit.app.data.NetworkClient
import com.frit.app.features.automation.AutomationEngine
import com.frit.app.features.automation.AutomationScheduler
import com.frit.app.features.market.MT5Agent
import com.frit.app.features.memory.MemoryEngine
import com.frit.app.features.vision.ScreenCaptureService
import com.frit.app.features.vision.VisionEngine
import com.frit.app.features.voice.VoiceEngine
import com.frit.app.ui.*
import com.frit.app.ui.theme.*
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import com.frit.app.features.market.MarketAnalysisEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var onWakeDetected: ((String) -> Unit)? = null
    var openCreatorOnStart: Boolean = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_creator", false)) openCreatorOnStart = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestFritPermissions()

        val prefs = getSharedPreferences("frit_prefs", Context.MODE_PRIVATE)
        com.frit.app.core.TokenStore.init(this)
        com.frit.app.core.AdminStore.init(this)
        com.frit.app.core.UserProfile.init(this)
        com.frit.app.core.PrivacyGuard.init(this)
        com.frit.app.core.ChatSession.init(this)
        com.frit.app.core.ChatHistory.init(this)
        IntegrationCoordinator.init(this)
        VoiceEngine.init(this)
        VisionEngine.init(this)
        AutomationEngine.init(this)
        com.frit.app.features.automation.AutomationScheduler.init(this)
        MemoryEngine.init(this)
        SubAgentOrchestrator.init(this)
        MT5Agent.init(this)
        LocalInferenceEngine.init(this)
        com.frit.app.features.creative.CreativeSuiteEngine.init(this)

        // Ensure background automation (cron/recurring tasks) engine is alive
        val siBg = Intent(this, com.frit.app.features.automation.FritBackgroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(siBg)
        else startService(siBg)

        // Auto-start persistent background wake word service if enabled or trained
        val wakeEnabled = prefs.getBoolean("wake_enabled", false)
        val isTrained = com.frit.app.features.wake.LocalWakeWordEngine.isTrained()
        if (wakeEnabled || isTrained) {
            com.frit.app.features.wake.FritWakeWordService.start(this)
        }
        
        // Pre-load the local model for offline use — CACHED FILE ONLY.
        // allowDownload = false: starting the app must never fetch 0.4–2.5GB.
        // The model is downloaded only when the user taps Download in Settings
        // (ModelDownloadService), which shows real progress in a notification.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.frit.app.core.LocalInferenceEngine.init(this@MainActivity)
            com.frit.app.core.llm.LocalLLMEngine.loadModel(
                this@MainActivity,
                allowDownload = false,
            )
        }
        
        setContent {
            var isDark by remember { mutableStateOf(prefs.getBoolean("dark_theme", false)) }
            FritTheme(darkTheme = isDark) {
                MainScreen(
                    prefs = prefs,
                    onWakeSetup = { onWakeDetected = it },
                    isDark = isDark,
                    onThemeToggle = { 
                        isDark = it
                        prefs.edit().putBoolean("dark_theme", it).apply()
                    }
                )
            }
        }
    }

    private fun requestFritPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CAMERA,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            // Call answering: Telecom acceptRingingCall needs ANSWER_PHONE_CALLS,
            // the ringing-state receiver needs READ_PHONE_STATE. SEND_SMS was
            // declared in the manifest but never requested — without this the
            // auto-answer receiver dies with SecurityException on first call.
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
            // NOTE: SEND_SMS is neither declared nor requested — SMS goes via
            // ACTION_SENDTO compose only (user taps send in their SMS app).
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(perms.toTypedArray(), 101)
    }

    private fun onWakeSetup(callback: (String) -> Unit) {
        onWakeDetected = callback
    }
}

@Composable
fun MainScreen(
    prefs: android.content.SharedPreferences,
    onWakeSetup: ((String) -> Unit) -> Unit,
    isDark: Boolean,
    onThemeToggle: (Boolean) -> Unit
) {
    var fritMode    by remember { mutableStateOf(FritMode.IDLE) }
    var input       by remember { mutableStateOf("") }
    val messages    = ChatSession.messages
    var showSettings by remember { mutableStateOf(false) }
    var showWallet by remember { mutableStateOf(false) }
    var showCreator by remember { mutableStateOf(false) }
    var showOwner by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(!com.frit.app.core.UserProfile.isOnboarded()) }
    var showAutomation by remember { mutableStateOf(false) }
    var showChats by remember { mutableStateOf(false) }
    var showCreativeSuite by remember { mutableStateOf(false) }
    var showTrading by remember { mutableStateOf(false) }
    var tradingTab by remember { mutableStateOf<String?>(null) }
    // Notification deep-link: tapping a trading alert opens the suite tab.
    // (Effect lives after the nav helpers below — local funs must be
    // declared before use.)
    val tradingDeepLinkActivity = LocalContext.current as? android.app.Activity
    var showImageEditor by remember { mutableStateOf(false) }
    // ImageEditor input: menu opens it empty; project picks load the
    // project's first image asset into it (F3).
    var editorImageUrl by remember { mutableStateOf("") }
    var editorAssetId by remember { mutableStateOf("") }
    var showVideoEditor by remember { mutableStateOf(false) }
    var showStoryboard by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    var showBatchEdit by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showProjectList by remember { mutableStateOf(false) }
    var showSidebar by remember { mutableStateOf(false) }
    var showGenerate by remember { mutableStateOf(false) }
    var showCinema by remember { mutableStateOf(false) }
    var showKatana by remember { mutableStateOf(false) }
    var showGenjutsu by remember { mutableStateOf(false) }
    var showAds by remember { mutableStateOf(false) }
    var showInfluencer by remember { mutableStateOf(false) }
    var showCharacters by remember { mutableStateOf(false) }
    var showLipSync by remember { mutableStateOf(false) }
    var showBrandKit by remember { mutableStateOf(false) }
    var showDesign by remember { mutableStateOf(false) }
    var showDirector by remember { mutableStateOf(false) }
    var editTarget  by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val thinkSteps  = remember { mutableStateListOf<String>() }

    // Hierarchical back navigation: every full-screen destination pushes its
    // key when opened; back arrows + the system back button pop to the screen
    // that opened it (Wallet -> Settings -> chat) instead of always chat.
    // Empty stack = chat interface.
    val navHistory = remember { mutableStateListOf<String>() }
    fun pushNav(key: String) { if (navHistory.lastOrNull() != key) navHistory.add(key) }
    fun showOnly(key: String?) {
        showSidebar = false; showChats = false
        showWallet = false; showCreator = false; showOwner = false
        showSettings = false; showAutomation = false
        showTrading = false; showCreativeSuite = false
        showGenerate = false; showCinema = false; showKatana = false
        showGenjutsu = false; showAds = false; showInfluencer = false
        showCharacters = false; showLipSync = false; showBrandKit = false
        showDesign = false; showImageEditor = false; showVideoEditor = false
        showStoryboard = false; showTemplates = false; showBatchEdit = false
        showExport = false; showProjectList = false; showDirector = false
        when (key) {
            "sidebar" -> showSidebar = true
            "chats" -> showChats = true
            "wallet" -> showWallet = true
            "creator" -> showCreator = true
            "owner" -> showOwner = true
            "settings" -> showSettings = true
            "automation" -> showAutomation = true
            "trading" -> showTrading = true
            "suite" -> showCreativeSuite = true
            "generate" -> showGenerate = true
            "cinema" -> showCinema = true
            "katana" -> showKatana = true
            "genjutsu" -> showGenjutsu = true
            "ads" -> showAds = true
            "influencer" -> showInfluencer = true
            "characters" -> showCharacters = true
            "lipsync" -> showLipSync = true
            "brandkit" -> showBrandKit = true
            "design" -> showDesign = true
            "director" -> showDirector = true
            "imageEditor" -> showImageEditor = true
            "videoEditor" -> showVideoEditor = true
            "storyboard" -> showStoryboard = true
            "templates" -> showTemplates = true
            "batchEdit" -> showBatchEdit = true
            "export" -> showExport = true
            "projects" -> showProjectList = true
        }
    }
    fun goBack() {
        if (navHistory.isNotEmpty()) navHistory.removeAt(navHistory.lastIndex)
        val prev = navHistory.lastOrNull()
        if (prev != "trading") tradingTab = null
        showOnly(prev)
    }
    // Cross-studio handoff: the media is already staged on
    // CreativeSuiteEngine's bus by HandoffRow — just navigate.
    // Targets: image | video | lipsync | cinema | katana | genjutsu |
    // design | characters | export.
    fun goHandoff(target: String) {
        if (target == "image") { editorImageUrl = ""; editorAssetId = "" }
        val key = when (target) {
            "image" -> "imageEditor"
            "video" -> "videoEditor"
            "lipsync" -> "lipsync"
            "cinema" -> "cinema"
            "katana" -> "katana"
            "genjutsu" -> "genjutsu"
            "design" -> "design"
            "characters" -> "characters"
            "export" -> "export"
            else -> return
        }
        pushNav(key)
        showOnly(key)
    }
    androidx.activity.compose.BackHandler(enabled = navHistory.isNotEmpty()) { goBack() }

    // Notification deep-link effect (placed after the nav helpers so
    // pushNav is in scope): a trading alert opens the suite tab.
    LaunchedEffect(Unit) {
        val tab = tradingDeepLinkActivity?.intent?.getStringExtra("trading_tab")
        if (!tab.isNullOrBlank()) { tradingTab = tab; showTrading = true; pushNav("trading") }
    }

    var isVoiceModeActive by remember { mutableStateOf(false) }

    // Each launch starts fresh: archive any previous conversation so it's
    // available from the history list, then clear the live session.
    LaunchedEffect(Unit) {
        if (ChatSession.messages.isNotEmpty()) {
            com.frit.app.core.ChatHistory.archiveCurrent(ChatSession.messages.toList())
            ChatSession.clear()
        }
    }

    fun startNewChat() {
        if (ChatSession.messages.isNotEmpty()) {
            com.frit.app.core.ChatHistory.archiveCurrent(ChatSession.messages.toList())
            ChatSession.clear()
        }
        com.frit.app.core.ChatHistory.detached()
        editTarget = null
        input = ""
    }

    fun openChat(id: String) {
        if (ChatSession.messages.isNotEmpty()) {
            com.frit.app.core.ChatHistory.archiveCurrent(ChatSession.messages.toList())
        }
        com.frit.app.core.ChatHistory.open(id)
        goBack()
    }
    
    var isQuickRecording by remember { mutableStateOf(false) }
    var showWakeTraining by remember { mutableStateOf(false) }
    // Multi-quote reply drafts (Messenger style, max 3) — folded into the
    // next send as "> @role: quote" context lines for the brain.
    val replyQuotes = remember { androidx.compose.runtime.mutableStateListOf<com.frit.app.data.ReplyQuote>() }
    var tradeToConfirm   by remember { mutableStateOf<JSONObject?>(null) }

    var baseUrl by remember { 
        mutableStateOf(prefs.getString("base_url", com.frit.app.core.FritConfig.BASE_URL) ?: com.frit.app.core.FritConfig.BASE_URL) 
    }
    // API token: baked BuildConfig default (from server/.env at build time),
    // overridable here at runtime. Never hardcoded in source (Play-safe).
    var apiToken by remember { mutableStateOf(com.frit.app.core.TokenStore.get()) }
    
    var voiceEnabled       by remember { mutableStateOf(prefs.getBoolean("voice_enabled", true)) }
    var wakeEnabled        by remember { mutableStateOf(prefs.getBoolean("wake_enabled", false)) }
    var screenCapEnabled   by remember { mutableStateOf(prefs.getBoolean("vision_enabled", false)) }
    var marketEnabled      by remember { mutableStateOf(prefs.getBoolean("market_enabled", true)) }
    var automationEnabled  by remember { mutableStateOf(prefs.getBoolean("automation_enabled", true)) }
    var automodeEnabled    by remember { mutableStateOf(prefs.getBoolean("automode_enabled", false)) }
    // App-PIN vault (transaction PINs the agent may type into third-party
    // apps on demand). Kept in app-private prefs; never leaves the phone
    // except inside a get_app_pin tool result for the immediate typing step.
    val appPinCtx = LocalContext.current
    var appPin            by remember { mutableStateOf(com.frit.app.core.SecureStore.getString(appPinCtx, "app_pin")) }
    var brainMode          by remember { mutableStateOf(RouteController.currentPreference()) }
    var privacyFilter      by remember { mutableStateOf(PrivacyGuard.isEnabled()) }

    val isAccessibilityEnabled = remember { mutableStateOf(false) }
    // One-shot nag: once the user taps the banner (opens system settings),
    // it never shows again — even if they leave the service off.
    var a11yNagDismissed by remember { mutableStateOf(prefs.getBoolean("a11y_nag_dismissed", false)) }
    LaunchedEffect(Unit) {
        while(true) {
            isAccessibilityEnabled.value = com.frit.app.features.market.FritAccessibilityService.instance != null
            delay(2000)
        }
    }

    val listState = rememberLazyListState()
    val scope     = rememberCoroutineScope()
    val context   = LocalContext.current
    val haptic    = LocalHapticFeedback.current
    // Veil lock-screen wiring (Phase 1): single source of truth lives in
    // VeilManager ("frit_veil" prefs); this mirrors it for recomposition.
    var veilEnabled by remember { mutableStateOf(com.frit.app.features.veil.VeilManager.isEnabled(context)) }

    // Creator payment watch: starts polling only on the owner's phone when
    // creator mode is on; opens Creator console from payment notifications.
    LaunchedEffect(Unit) {
        com.frit.app.core.CreatorWatcher.sync(context)
        com.frit.app.features.veil.VeilManager.init(context)
        if ((context as? MainActivity)?.openCreatorOnStart == true) {
            (context as? MainActivity)?.openCreatorOnStart = false
            showCreator = true
            pushNav("creator")
        }
    }

    val externalThoughts = IntegrationCoordinator.thoughtSteps.collectAsState()

    // Live streamed reply text (audit: streaming AI chat). The coordinator
    // appends each delta as the model produces it.
    val streamText = IntegrationCoordinator.streamText.collectAsState()

    LaunchedEffect(externalThoughts.value) {
        thinkSteps.clear()
        thinkSteps.addAll(externalThoughts.value)
    }

    var pendingFileContent by remember { mutableStateOf<String?>(null) }
    var pendingImageContent by remember { mutableStateOf<String?>(null) }
    var pendingFileName by remember { mutableStateOf<String?>(null) }
    
    // Minimal fix for circular dependency between sendMessage and voice mode functions
    var startContinuousVoiceModeRef: (() -> Unit)? = null

    // Stop-reply: the active chat coroutine. Send button becomes Stop while set.
    var chatJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // True from message send until the assistant reply bubble FINISHES typing —
    // the pause button reverts to send exactly when the typing animation stops.
    var replyAnimating by remember { mutableStateOf(false) }
    // Live text of a streaming reply (audit: streaming AI chat). Empty when
    // nothing is streaming; a provisional bubble renders it while it grows.
    var streamingText by remember { mutableStateOf("") }

    // Mirror the coordinator's stream buffer into local state so Compose
    // recomposes the live bubble on every delta.
    LaunchedEffect(streamText.value) {
        streamingText = streamText.value
    }
    fun stopChat() {
        chatJob?.cancel()
        chatJob = null
        replyAnimating = false
        VoiceEngine.stopRecording()
        VoiceEngine.stopSpeaking()
        fritMode = FritMode.IDLE
    }
    // Veil stranger guard: a pickup pauses tasks by killing the live turn.
    // (Registered once; the stored lambda reads chatJob live when invoked.)
    LaunchedEffect(Unit) {
        com.frit.app.features.veil.VeilManager.onTasksPaused {
            try {
                chatJob?.cancel()
            } catch (_: Exception) { }
            chatJob = null
        }
    }

    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val cr = context.contentResolver
                    var fileName = "Unknown file"
                    cr.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: "Unknown file"
                        }
                    }
                    val mime = cr.getType(uri) ?: ""
                    
                    if (mime.startsWith("image/")) {
                        val bitmap = cr.openInputStream(uri)?.use { stream ->
                            android.graphics.BitmapFactory.decodeStream(stream)
                        }
                        if (bitmap != null) {
                            pendingImageContent = Base64.encodeToString(downscaleForUpload(bitmap), Base64.NO_WRAP)
                            Toast.makeText(context, "Image Attached: $fileName", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Could not read image", Toast.LENGTH_SHORT).show()
                        }
                    } else if (mime.startsWith("text/") || mime == "application/json") {
                        val content = cr.openInputStream(uri)?.use { stream ->
                            stream.bufferedReader().use { reader -> reader.readText().take(5000) }
                        }
                        pendingFileContent = content
                        pendingFileName = fileName
                        Toast.makeText(context, "Attached: $fileName", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Unsupported file type", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error reading file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val si = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            // Cache consent so the agent can auto-enable vision per task.
            ScreenCaptureService.cacheConsent(result.resultCode, result.data)
            context.startForegroundService(si)
        } else {
            screenCapEnabled = false
            VisionEngine.shutdown()
            ScreenCaptureService.stop(context)
            Toast.makeText(context, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            prefs.edit().putBoolean("vision_enabled", false).apply()
        }
    }

    val bg     = if (isDark) Color(0xFF111110) else FritCream
    val fg     = if (isDark) FritCream        else FritEspresso
    val surf   = if (isDark) Color(0xFF1C1A18) else FritSurface
    val stroke = if (isDark) FritCream.copy(alpha = 0.22f) else FritStroke

    LaunchedEffect(baseUrl) {
        NetworkClient.setBaseUrl(baseUrl)
        SubAgentOrchestrator.setBaseUrl(baseUrl)
        VoiceEngine.setBaseUrl(baseUrl)
        MarketAnalysisEngine.setBaseUrl(baseUrl)
        prefs.edit().putString("base_url", baseUrl).apply()
    }

    suspend fun sendMessage(text: String) {
        if (text.isBlank() && pendingFileContent == null && pendingImageContent == null) return
        
        val fullText = if (pendingFileContent != null) {
            val t = "$text\n\n[FILE ATTACHMENT]:\n$pendingFileContent"
            pendingFileContent = null
            pendingFileName = null
            t
        } else text

        val imageToSend = pendingImageContent
        pendingImageContent = null

        // Fold quoted context into what the brain receives; the stored message
        // keeps clean text + structured quotes for rendering/reload.
        val quotePrefix = if (replyQuotes.isEmpty()) "" else replyQuotes.joinToString("\n") {
            "> @${if (it.role == "user") "you" else "FRIT"}: ${it.text}"
        } + "\n\n"
        if (editTarget != null) {
            val idx = editTarget!!.first
            ChatSession.replaceUser(idx, text, imageToSend)
            editTarget = null
        } else {
            ChatSession.addUser(text, imageToSend, replyQuotes.toList())
        }
        replyQuotes.clear()
        
        input = ""
        if (fritMode == FritMode.LISTENING || fritMode == FritMode.SPEAKING) {
            VoiceEngine.stopRecording()
        }
        fritMode = FritMode.THINKING
        replyAnimating = true
        scope.launch { delay(60); listState.animateScrollToItem(messages.size - 1) }

        val reply = try {
            // Image-only sends (camera/screenshot with no typed text) must
            // still carry a goal — the server rejects empty goals with 400
            // "Goal required", which used to surface as a brain error.
            val goal = (quotePrefix + fullText).ifBlank {
                "Describe what you see in the attached image in detail and offer relevant help."
            }
            // Streaming (audit): the reply arrives token by token and is shown
            // in a live bubble while it lands. A task falls through to the
            // agent loop, which the server signals back to the client.
            IntegrationCoordinator.process(goal, ChatSession.compactHistory(), imageToSend)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // User pressed Stop — exit silently, keep the sent message, no error bubble.
            replyAnimating = false
            fritMode = FritMode.IDLE
            return
        } catch (e: Exception) { "Error: ${e.message}" }
        val cleanReply = normalizeAssistantReplyForUi(reply)
        val artifacts = IntegrationCoordinator.lastArtifacts.value
        IntegrationCoordinator.clearArtifacts()
        // A streamed reply was ALREADY shown live as it arrived, so it is
        // added without the typing animation (replaying it would show the
        // same text twice).
        val wasStreamed = IntegrationCoordinator.lastReplyWasStreamed
        IntegrationCoordinator.lastReplyWasStreamed = false
        // Revert stop->send the moment the reply bubble finishes typing.
        ChatSession.addAssistant(cleanReply, artifacts, animated = !wasStreamed) {
            replyAnimating = false
        }
        fritMode = FritMode.SPEAKING
        
        if (voiceEnabled || isVoiceModeActive) {
            VoiceEngine.speak(cleanReply) { 
                if (isVoiceModeActive) {
                    startContinuousVoiceModeRef?.invoke()
                } else {
                    fritMode = FritMode.IDLE
                }
            }
        } else {
            delay(400)
            fritMode = FritMode.IDLE
        }
        scope.launch { delay(60); listState.animateScrollToItem(messages.size - 1) }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = cameraImageUri
        cameraImageUri = null
        if (success && uri != null) {
            scope.launch {
                try {
                    val cr = context.contentResolver
                    val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val payload = if (bitmap != null) downscaleForUpload(bitmap, 78) else bytes
                    val b64 = Base64.encodeToString(payload, Base64.NO_WRAP)
                    pendingImageContent = b64
                    Toast.makeText(context, "Camera capture attached", Toast.LENGTH_SHORT).show()
                    sendMessage("")
                } catch (e: Exception) {
                    Toast.makeText(context, "Camera capture failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun startSiriMode() {
        fritMode = FritMode.LISTENING
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        VoiceEngine.startRecording(object : VoiceEngine.VoiceListener {
            override fun onReady() {}
            override fun onResult(text: String) {
                if (VoiceEngine.isMeaningfulTranscript(text)) {
                    fritMode = FritMode.THINKING
                    scope.launch { sendMessage(text) }
                } else {
                    fritMode = FritMode.IDLE
                }
            }
            override fun onError(e: String) {
                fritMode = FritMode.IDLE
                Toast.makeText(context, "Voice error: $e", Toast.LENGTH_SHORT).show()
            }
        })
    }

    fun startContinuousVoiceMode() {
        isVoiceModeActive = true
        fritMode = FritMode.LISTENING
        VoiceEngine.startRecording(object : VoiceEngine.VoiceListener {
            override fun onReady() {}
            override fun onResult(text: String) {
                if (VoiceEngine.isMeaningfulTranscript(text)) {
                    fritMode = FritMode.THINKING
                    scope.launch { sendMessage(text) }
                } else if (isVoiceModeActive) {
                    startContinuousVoiceMode()
                }
            }
            override fun onError(e: String) {
                if (isVoiceModeActive) {
                    scope.launch {
                        delay(1000)
                        if (isVoiceModeActive) startContinuousVoiceMode()
                    }
                } else {
                    fritMode = FritMode.IDLE
                }
            }
        })
    }

    // Dictation mode: mic tap records once and drops the transcript into the
    // text field (appended) instead of sending it as a message.
    var dictateToInput by remember { mutableStateOf(false) }

    fun startQuickRecording() {
        isQuickRecording = true
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        VoiceEngine.startRecording(object : VoiceEngine.VoiceListener {
            override fun onReady() {}
            override fun onResult(text: String) {
                isQuickRecording = false
                if (VoiceEngine.isMeaningfulTranscript(text)) { scope.launch { sendMessage(text) } }
            }
            override fun onError(e: String) { isQuickRecording = false }
        })
    }

    fun startDictation() {
        isQuickRecording = true
        dictateToInput = true
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        VoiceEngine.startRecording(object : VoiceEngine.VoiceListener {
            override fun onReady() {}
            override fun onResult(text: String) {
                isQuickRecording = false
                dictateToInput = false
                if (VoiceEngine.isMeaningfulTranscript(text)) {
                    input = if (input.isBlank()) text else "${input.trimEnd()} $text"
                } else {
                    Toast.makeText(context, "Didn't catch that — try again", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onError(e: String) {
                isQuickRecording = false
                dictateToInput = false
                Toast.makeText(context, "Voice error: $e", Toast.LENGTH_SHORT).show()
            }
        })
    }

    fun openCamera() {
        val ctx = context
        val file = java.io.File(ctx.cacheDir, "camera_capture_${System.currentTimeMillis()}.jpg")
        val uri = try {
            androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", file
            )
        } catch (e: Exception) {
            Toast.makeText(ctx, "Camera init failed", Toast.LENGTH_SHORT).show()
            return
        }
        cameraImageUri = uri
        try {
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(ctx, "No camera app available", Toast.LENGTH_SHORT).show()
            cameraImageUri = null
        }
    }

    fun startVision() {
        screenCapEnabled = true
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
        prefs.edit().putBoolean("vision_enabled", true).apply()
    }

    fun stopVision() {
        screenCapEnabled = false
        ScreenCaptureService.stop(context)
        VisionEngine.shutdown()
        ScreenCaptureService.lastScreenshotBase64 = null
        prefs.edit().putBoolean("vision_enabled", false).apply()
    }

    fun stopVoiceMode() {
        isVoiceModeActive = false
        // Closing voice mode must kill its cloud work too: the agent loop is
        // client-driven, so cancelling the chat job stops all future phone
        // actions from that voice session immediately.
        stopChat()
        fritMode = FritMode.IDLE
        VoiceEngine.stopRecording()
        VoiceEngine.stopSpeaking()
    }
    
    startContinuousVoiceModeRef = ::startContinuousVoiceMode

    fun toggleVision(enable: Boolean) {
        screenCapEnabled = enable
        if (enable) {
            startVision()
        } else {
            stopVision()
        }
    }
    fun cancelListening() { fritMode = FritMode.IDLE; VoiceEngine.stopRecording(); VoiceEngine.stopSpeaking() }

    onWakeSetup { t ->
        if (t.isNotBlank()) {
            scope.launch { sendMessage(t) }
        } else {
            isVoiceModeActive = true
            startContinuousVoiceMode()
        }
    }

    tradeToConfirm?.let { trade ->
        Dialog(onDismissRequest = { tradeToConfirm = null }) {
            Box(Modifier.fillMaxWidth().background(bg).border(2.dp, fg, RoundedCornerShape(16.dp)).padding(24.dp)) {
                Column {
                    Text("Safety Shield: Trade Confirmation", fontWeight = FontWeight.Bold, color = fg, fontSize = 16.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("The AI agent wants to execute a trade:", color = fg, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Symbol: ${trade.optString("symbol")}", fontWeight = FontWeight.Bold, color = fg)
                    Text("Action: ${trade.optString("action")}", color = if(trade.optString("action").lowercase()=="buy") FritPositive else FritNegative)
                    Text("Lot Size: ${trade.optDouble("volume")}", color = fg)
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { tradeToConfirm = null }, Modifier.weight(1f)) { Text("Cancel") }
                        Button(onClick = {
                            scope.launch { NetworkClient.placeTrade(trade) }
                            tradeToConfirm = null
                        }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = fg)) {
                            Text("Execute", color = bg)
                        }
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(bg)) {
        TopoBackground(isDark)

        var privacyConsent by remember { mutableStateOf(prefs.getBoolean("privacy_consent_given", false)) }

        // First-launch personal-details onboarding: prompts once after install
        // (after the privacy consent), editable any time in Settings.
        if (privacyConsent && showOnboarding) {
            OnboardingDialog(fg = fg, surf = surf, stroke = stroke, bg = bg) {
                showOnboarding = false
            }
        }

        if (!privacyConsent) {
            Dialog(onDismissRequest = {}) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .border(1.dp, stroke, RoundedCornerShape(16.dp))
                        .padding(24.dp)
                ) {
                    Column {
                        Text(
                            "Your privacy",
                            color = fg, fontFamily = Montserrat, fontSize = 18.sp, fontWeight = FontWeight.Black
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "FRIT sends your screen text, current app, and relevant memory to its cloud reasoning brain to carry out tasks.",
                            color = fg.copy(alpha = 0.8f), fontSize = 13.sp, fontFamily = Montserrat, lineHeight = 19.sp
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = {
                                privacyConsent = true
                                prefs.edit().putBoolean("privacy_consent_given", true).apply()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = fg),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("I understand", color = bg, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        when {
            showSidebar -> {
                com.frit.app.ui.FritSidebar(
                    onChatsTap = { pushNav("chats"); showChats = true; showSidebar = false },
                    onNewChatTap = {
                        startNewChat()
                        goBack()
                        scope.launch { listState.scrollToItem(0) }
                    },
                    onCreativeSuiteTap = { pushNav("suite"); showCreativeSuite = true; showSidebar = false },
                    onTradingTap = { pushNav("trading"); showTrading = true; showSidebar = false },
                    onClose = { goBack() },
                    fg = fg, bg = bg
                )
            }
            showChats -> {
                ChatsScreen(
                    fg = fg, surf = surf, stroke = stroke, bg = bg,
                    onClose = { goBack() },
                    onOpen = ::openChat,
                    onNew = {
                        startNewChat()
                        goBack()
                    }
                )
            }
            showWallet -> {
                WalletScreen(
                    fg = fg, surf = surf, stroke = stroke, bg = bg,
                    onClose = { goBack() },
                    onCreatorTap = { pushNav("creator"); showCreator = true; showWallet = false }
                )
            }
            showCreator -> {
                CreatorScreen(
                    fg = fg, surf = surf, stroke = stroke, bg = bg,
                    onClose = { goBack() },
                    onOwnerTap = { pushNav("owner"); showOwner = true; showCreator = false }
                )
            }
            showOwner -> {
                OwnerConsole(
                    fg = fg, surf = surf, stroke = stroke, bg = bg,
                    onClose = { goBack() }
                )
            }
            showSettings -> {
                SettingsScreen(
                    fg = fg, surf = surf, stroke = stroke, bg = bg,
                    onWalletTap = { pushNav("wallet"); showWallet = true; showSettings = false },
                    veilEnabled = veilEnabled,
                    onVeilEnabled = {
                        veilEnabled = it
                        com.frit.app.features.veil.VeilManager.setEnabled(context, it)
                        if (it) Toast.makeText(context, "Screen veil on — set a veil PIN below", Toast.LENGTH_LONG).show()
                    },
                    voiceEnabled = voiceEnabled, onVoiceEnabled = { voiceEnabled = it },
                    wakeEnabled = wakeEnabled, onWakeEnabled = {
                        wakeEnabled = it
                        prefs.edit().putBoolean("wake_enabled", it).apply()
                        if (it) com.frit.app.features.wake.FritWakeWordService.start(context)
                        else com.frit.app.features.wake.FritWakeWordService.stop(context)
                    },
                    screenCapEnabled = screenCapEnabled, onScreenCapEnabled = { toggleVision(it) },
                    marketEnabled = marketEnabled, onMarketEnabled = { marketEnabled = it },
                    automationEnabled = automationEnabled, onAutomationEnabled = { automationEnabled = it },
                    isDark = isDark, onThemeToggle = onThemeToggle,
                    brainMode = brainMode, onBrainModeChange = {
                        brainMode = it
                        RouteController.setPreference(it)
                    },
                    privacyFilter = privacyFilter, onPrivacyFilterChange = {
                        privacyFilter = it
                        PrivacyGuard.setEnabled(it)
                    },
                    isWakeTrained = com.frit.app.features.wake.LocalWakeWordEngine.isTrained(),
                    onTrainWakeWord = { showWakeTraining = true },
                    appPin = appPin, onAppPinChange = { appPin = it },
                    onSaveAppPin = {
                        com.frit.app.core.SecureStore.putString(context, "app_pin", appPin)
                        Toast.makeText(context, "App PIN saved on this phone", Toast.LENGTH_SHORT).show()
                    },
                    onClearAppPin = {
                        appPin = ""
                        com.frit.app.core.SecureStore.remove(context, "app_pin")
                        Toast.makeText(context, "App PIN cleared", Toast.LENGTH_SHORT).show()
                    },
                    baseUrl = baseUrl, onBaseUrlChange = { baseUrl = it },
                    apiToken = apiToken, onApiTokenChange = { apiToken = it },
                    onSaveApiToken = {
                        com.frit.app.core.TokenStore.set(apiToken)
                        Toast.makeText(
                            context,
                            if (apiToken.isBlank()) "API token cleared" else "API token saved on this phone",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onClose = { goBack() }
                )
                // Training floats OVER settings (Dialog), not as a replacement
                // screen — closing it returns straight to Settings.
                if (showWakeTraining) {
                    WakeWordTrainingDialog(
                        fg = fg, surf = surf, stroke = stroke, bg = bg,
                        onClose = { showWakeTraining = false }
                    )
                }
            }
            showAutomation -> {
                AutomationScreen(
                    fg = fg, surf = surf, stroke = stroke, bg = bg,
                    automodeEnabled = automodeEnabled, onAutomodeEnabled = {
                        automodeEnabled = it
                        prefs.edit().putBoolean("automode_enabled", it).apply()
                        if (it) com.frit.app.features.automation.AutoModeService.start(context)
                        else com.frit.app.features.automation.AutoModeService.stop(context)
                    },
                    watchedAppCount = com.frit.app.features.automation.AutoModeApps.selected(context).size,
                    notifAccessGranted = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
                    onEnableNotifAccess = {
                        try {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (e: Exception) {
                            Toast.makeText(context, "Open Settings → Notifications → Notification access", Toast.LENGTH_LONG).show()
                        }
                    },
                    onClose = { goBack() }
                )
            }
            showTrading -> {
                com.frit.app.ui.trading.TradingSuiteScreen(
                    onClose = { tradingTab = null; goBack() },
                    initialTab = tradingTab
                )
            }
            showCreativeSuite -> {
                com.frit.app.ui.creative.CreativeSuiteScreen(
                    onClose = { goBack() },
                    onGenerate = { pushNav("generate"); showGenerate = true; showCreativeSuite = false },
                    onCinema = { pushNav("cinema"); showCinema = true; showCreativeSuite = false },
                    onKatana = { pushNav("katana"); showKatana = true; showCreativeSuite = false },
                    onGenjutsu = { pushNav("genjutsu"); showGenjutsu = true; showCreativeSuite = false },
                    onAds = { pushNav("ads"); showAds = true; showCreativeSuite = false },
                    onInfluencer = { pushNav("influencer"); showInfluencer = true; showCreativeSuite = false },
                    onCharacters = { pushNav("characters"); showCharacters = true; showCreativeSuite = false },
                    onLipSync = { pushNav("lipsync"); showLipSync = true; showCreativeSuite = false },
                    onBrandKit = { pushNav("brandkit"); showBrandKit = true; showCreativeSuite = false },
                    onDesign = { pushNav("design"); showDesign = true; showCreativeSuite = false },
                    onDirector = { pushNav("director"); showDirector = true; showCreativeSuite = false },
                    onImageEdit = { editorImageUrl = ""; editorAssetId = ""; pushNav("imageEditor"); showImageEditor = true; showCreativeSuite = false },
                    onVideoEdit = { pushNav("videoEditor"); showVideoEditor = true; showCreativeSuite = false },
                    onStoryboard = { pushNav("storyboard"); showStoryboard = true; showCreativeSuite = false },
                    onTemplates = { pushNav("templates"); showTemplates = true; showCreativeSuite = false },
                    onBatchEdit = { pushNav("batchEdit"); showBatchEdit = true; showCreativeSuite = false },
                    onExport = { pushNav("export"); showExport = true; showCreativeSuite = false },
                    onProjects = { pushNav("projects"); showProjectList = true; showCreativeSuite = false }
                )
            }
            showGenerate -> {
                com.frit.app.ui.creative.GenerateScreen(
                    onClose = { goBack() },
                    onSubscribe = { pushNav("wallet"); showGenerate = false; showWallet = true },
                    onHandoff = { goHandoff(it) },
                )
            }
            showCinema -> {
                com.frit.app.ui.creative.CinemaStudioScreen(
                    onClose = { goBack() },
                    onSubscribe = { pushNav("wallet"); showCinema = false; showWallet = true },
                    onHandoff = { goHandoff(it) },
                )
            }
            showKatana -> {
                com.frit.app.ui.creative.KatanaScreen(
                    onClose = { goBack() },
                    onSubscribe = { pushNav("wallet"); showKatana = false; showWallet = true },
                    onHandoff = { goHandoff(it) },
                )
            }
            showGenjutsu -> {
                com.frit.app.ui.creative.GenjutsuScreen(
                    onClose = { goBack() },
                    onSubscribe = { pushNav("wallet"); showGenjutsu = false; showWallet = true },
                    onHandoff = { goHandoff(it) },
                )
            }
            showAds -> {
                com.frit.app.ui.creative.AdsStudioScreen(
                    onClose = { goBack() },
                    onHandoff = { goHandoff(it) },
                )
            }
            showInfluencer -> {
                com.frit.app.ui.creative.InfluencerScreen(
                    onClose = { goBack() },
                    onHandoff = { goHandoff(it) },
                )
            }
            showCharacters -> {
                com.frit.app.ui.creative.CharactersScreen(
                    onClose = { goBack() },
                    onUseElement = { pushNav("cinema"); showCharacters = false; showCinema = true }
                )
            }
            showLipSync -> {
                com.frit.app.ui.creative.LipSyncScreen(
                    onClose = { goBack() },
                    onSubscribe = { pushNav("wallet"); showLipSync = false; showWallet = true },
                    onHandoff = { goHandoff(it) },
                )
            }
            showBrandKit -> {
                com.frit.app.ui.creative.BrandKitScreen(
                    onClose = { goBack() },
                    onApply = { pushNav("design"); showBrandKit = false; showDesign = true }
                )
            }
            showDesign -> {
                com.frit.app.ui.creative.DesignStudioScreen(
                    onClose = { goBack() },
                    onHandoff = { goHandoff(it) },
                )
            }
            showDirector -> {
                com.frit.app.ui.creative.DirectorScreen(
                    onClose = { goBack() },
                    onHandoff = { goHandoff(it) },
                )
            }
            showImageEditor -> {
                com.frit.app.ui.creative.ImageEditorScreen(
                    assetId = editorAssetId,
                    imageUrl = editorImageUrl,
                    onClose = { goBack() },
                    onSave = { url ->
                        scope.launch {
                            val loc = saveEditedImageToDownloads(context, url)
                            Toast.makeText(
                                context,
                                if (loc.isBlank()) "Save failed" else "Saved edited image → $loc",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        goBack()
                    },
                    onHandoff = { goHandoff(it) },
                )
            }
            showVideoEditor -> {
                com.frit.app.ui.creative.VideoEditorScreen(
                    onClose = { goBack() },
                    onSave = { goBack() },
                    onHandoff = { goHandoff(it) },
                )
            }
            showStoryboard -> {
                com.frit.app.ui.creative.StoryboardScreen(
                    onClose = { goBack() },
                    onExport = { pushNav("export"); showStoryboard = false; showExport = true },
                    onHandoff = { goHandoff(it) },
                )
            }
            showTemplates -> {
                com.frit.app.ui.creative.TemplateGalleryScreen(
                    onClose = { goBack() }
                )
            }
            showBatchEdit -> {
                com.frit.app.ui.creative.BatchEditScreen(
                    onClose = { goBack() },
                    onHandoff = { goHandoff(it) },
                )
            }
            showExport -> {
                com.frit.app.ui.creative.ExportScreen(
                    onClose = { goBack() },
                    onExported = { url -> shareExportAsset(context, url) }
                )
            }
            showProjectList -> {
                com.frit.app.ui.creative.ProjectListScreen(
                    onClose = { goBack() },
                    onProjectSelected = { id ->
                        // Load the project and open its first image asset in
                        // the editor instead of dropping the selection (F3).
                        editorImageUrl = ""
                        editorAssetId = ""
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val url = runCatching {
                                val p = com.frit.app.features.creative.CreativeSuiteEngine.loadProject(id).getOrNull()
                                p?.assets?.firstOrNull { it.kind == "image" && it.currentUrl.isNotBlank() }?.currentUrl
                                    ?: p?.assets?.firstOrNull { it.currentUrl.isNotBlank() }?.currentUrl
                                    ?: p?.thumbnailUrl
                            }.getOrNull().orEmpty()
                            if (url.isNotBlank()) editorImageUrl = url
                        }
                        pushNav("imageEditor"); showProjectList = false; showImageEditor = true
                    }
                )
            }
            else -> {
                Column(Modifier.fillMaxSize()) {
                    if (!isAccessibilityEnabled.value && !a11yNagDismissed) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFFF5555))
                                .clickable {
                                    context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    prefs.edit().putBoolean("a11y_nag_dismissed", true).apply()
                                    a11yNagDismissed = true
                                }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Accessibility Service Disabled! Tap to fix.", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    
                    ChatContent(
                        bg = bg, fg = fg, surf = surf, stroke = stroke, isDark = isDark, fritMode = fritMode,
                        messages = messages, input = input, onInputChange = { input = it },
                        replyQuotes = replyQuotes,
                        onSend = { scope.launch { sendMessage(it) }.also { chatJob = it; it.invokeOnCompletion { if (chatJob === it) chatJob = null } } },
                        onStop = { stopChat() },
                        isGenerating = replyAnimating,
                        onThumbsUp = { reply ->
                            val task = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
                            com.frit.app.features.memory.MemoryEngine.addFeedback(true, task, reply)
                            android.widget.Toast.makeText(context, "Noted — I'll do it this way again", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onThumbsDown = { reply ->
                            val task = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
                            com.frit.app.features.memory.MemoryEngine.addFeedback(false, task, reply)
                            android.widget.Toast.makeText(context, "Saved — I'll avoid this approach next time", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onChatsTap = { pushNav("sidebar"); showSidebar = true },
                        onNewChatTap = { startNewChat() },
                        onVoiceTap = {
                            if (fritMode == FritMode.LISTENING || isQuickRecording || VoiceEngine.isRecordingActive()) {
                                VoiceEngine.stopRecording()
                                isQuickRecording = false
                                dictateToInput = false
                                fritMode = FritMode.IDLE
                            } else {
                                startDictation()
                            }
                        },
                        onVoiceLongPress = { startContinuousVoiceMode() },
                        isVisionActive = screenCapEnabled,
                        onVisionTap = { toggleVision(!screenCapEnabled) },
                        onVisionLongPress = { openCamera() },
                        onAttachmentTap = { filePicker.launch("*/*") },
                        onAttachmentLongPress = { openCamera() },
                        onSettingsTap = { pushNav("settings"); showSettings = true },
                        onAutomationTap = { pushNav("automation"); showAutomation = true },
                        onEdit = { idx, txt -> input = txt; editTarget = idx to txt },
                        onDelete = { ChatSession.removeAt(it) },
                        listState = listState, thinkSteps = thinkSteps,
                        isQuickRecording = isQuickRecording,
                        isFileAttached = pendingFileContent != null || pendingImageContent != null,
                        pendingImageB64 = pendingImageContent,
                        pendingFileName = pendingFileName,
                        streamingText = streamingText,
                        onRemoveImage = { pendingImageContent = null },
                        onRemoveFile = { pendingFileContent = null; pendingFileName = null },
                        onOpenCreativeSuite = { pushNav("suite"); showCreativeSuite = true },
                        brainMode = brainMode,
                        onBrainModeChange = {
                            brainMode = it
                            RouteController.setPreference(it)
                        }
                    )
                }
            }
        }

        if (isVoiceModeActive) {
            FritVoiceUI(
                fritMode = fritMode,
                onCancel = { stopVoiceMode() },
                fg = fg,
                bg = bg,
                isDark = isDark,
                onMicTap = {
                    if (fritMode == FritMode.SPEAKING) {
                        VoiceEngine.stopSpeaking()
                    }
                    if (fritMode == FritMode.LISTENING) {
                        VoiceEngine.stopRecording()
                        fritMode = FritMode.IDLE
                    } else {
                        startContinuousVoiceMode()
                    }
                },
                isVisionActive = screenCapEnabled,
                onVisionTap = { toggleVision(!screenCapEnabled) },
                onVisionLongPress = { openCamera() },
                onCameraTap = { openCamera() },
                onFilePickerTap = { filePicker.launch("*/*") }
            )
        }
    }
}

@Composable
fun WaveformBars(level: Float, fg: Color, stroke: Color) {
    val history = remember { mutableStateListOf<Float>() }
    LaunchedEffect(level) {
        history.add(level.coerceIn(0f, 1f))
        if (history.size > 48) history.removeAt(0)
    }
    Canvas(
        Modifier.fillMaxWidth().height(90.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(fg.copy(alpha = 0.06f))
            .border(1.dp, stroke, RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
        if (history.isEmpty()) return@Canvas
        val n = history.size
        val gap = 3.dp.toPx()
        val bw = ((size.width - gap * (n - 1)) / n).coerceAtLeast(2f)
        history.forEachIndexed { i, v ->
            val h = (v * size.height).coerceAtLeast(2f)
            drawRect(
                color = fg,
                topLeft = androidx.compose.ui.geometry.Offset(i * (bw + gap), (size.height - h) / 2),
                size = androidx.compose.ui.geometry.Size(bw, h)
            )
        }
    }
}

@Composable
fun WakeWordTrainingDialog(fg: Color, surf: Color, stroke: Color, bg: Color, onClose: () -> Unit) {
    val context = LocalContext.current
    val engine = com.frit.app.features.wake.LocalWakeWordEngine
    val step by engine.trainingStateFlow.collectAsState()
    val rms by engine.currentRmsFlow.collectAsState()
    val lastScore by engine.lastScoreFlow.collectAsState()

    // Opening the dialog (re)starts training; the engine publishes step
    // updates (1 -> 2 -> 0) and live mic RMS while capturing.
    LaunchedEffect(Unit) {
        com.frit.app.features.wake.FritWakeWordService.train(context) {}
    }

    // Dismiss/Cancel = abort training and restore normal listening.
    fun cancelAndClose() {
        engine.stop()
        com.frit.app.features.wake.FritWakeWordService.start(context)
        onClose()
    }

    Dialog(onDismissRequest = { cancelAndClose() }) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(surf)
                .border(1.dp, stroke, RoundedCornerShape(16.dp)).padding(20.dp)
        ) {
            Text("Train wake word", color = fg, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
            Spacer(Modifier.height(8.dp))
            val done = step?.first == 0
            Text(
                step?.second ?: "Starting microphone…",
                color = fg, fontSize = 14.sp, fontFamily = Montserrat
            )
            Spacer(Modifier.height(16.dp))
            if (!done) {
                WaveformBars(level = rms, fg = fg, stroke = stroke)
                Spacer(Modifier.height(8.dp))
                val live = rms > 0.02f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(if (live) Color(0xFF4A7C59) else fg.copy(alpha = 0.3f), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (live) "MIC LIVE — hearing you" else "quiet… speak clearly",
                        color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat
                    )
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { cancelAndClose() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cancel", color = fg) }
            } else {
                // Test meter: the engine is back in DETECT mode, so speak the
                // phrase and watch your live score vs the trained threshold.
                // CLOSE miss (score slightly above threshold) = retrain clearer
                // / louder / closer to the mic. FAR miss = wrong phrase, room,
                // or distance — fix the condition, not the voice.
                Spacer(Modifier.height(12.dp))
                Text("Test it — say the wake word:", color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
                Spacer(Modifier.height(6.dp))
                val s = lastScore
                Text(
                    if (s == null) "listening for a test phrase…"
                    else "score ${"%.2f".format(s.first)} vs threshold ${"%.2f".format(s.second)} — " +
                        if (s.first < s.second) "MATCH" else "miss",
                    color = fg, fontSize = 13.sp, fontFamily = Montserrat
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = fg)
                ) { Text("Done", color = bg, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
fun RecordingOverlay(fg: Color, bg: Color, isSiriMode: Boolean) {    Row(
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
            text = if (isSiriMode) "Listening" else "Recording",
            color = fg,
            fontFamily = Montserrat,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SettingsScreen(
    fg: Color, surf: Color, stroke: Color, bg: Color,
    voiceEnabled: Boolean, onVoiceEnabled: (Boolean) -> Unit,
    wakeEnabled: Boolean, onWakeEnabled: (Boolean) -> Unit,
    screenCapEnabled: Boolean, onScreenCapEnabled: (Boolean) -> Unit,
    marketEnabled: Boolean, onMarketEnabled: (Boolean) -> Unit,
    automationEnabled: Boolean, onAutomationEnabled: (Boolean) -> Unit,
    isDark: Boolean, onThemeToggle: (Boolean) -> Unit,
    brainMode: com.frit.app.core.RouteController.Brain,
    onBrainModeChange: (com.frit.app.core.RouteController.Brain) -> Unit,
    privacyFilter: Boolean,
    onPrivacyFilterChange: (Boolean) -> Unit,
    isWakeTrained: Boolean,
    onTrainWakeWord: () -> Unit,
    appPin: String,
    onAppPinChange: (String) -> Unit,
    onSaveAppPin: () -> Unit,
    onClearAppPin: () -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    apiToken: String,
    onApiTokenChange: (String) -> Unit,
    onSaveApiToken: () -> Unit,
    onWalletTap: () -> Unit,
    veilEnabled: Boolean,
    onVeilEnabled: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val brainOptions = com.frit.app.core.RouteController.Brain.values().toList()

    Column(Modifier.fillMaxSize().background(bg).padding(24.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", color = fg, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = fg) }
        }
        Spacer(Modifier.height(30.dp))

        Button(
            onClick = onWalletTap,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = fg)
        ) { Text("Wallet & Subscription", color = bg, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))
        Text(
            "Tiers, top-ups, passcodes and redeem codes live here.",
            color = fg, fontSize = 13.sp, fontFamily = Montserrat
        )
        Spacer(Modifier.height(16.dp))

        UserProfileSection(fg = fg, surf = surf, stroke = stroke, bg = bg)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = stroke)
        Spacer(Modifier.height(8.dp))

        SettingToggle("Dark Mode", isDark, onThemeToggle, fg, stroke)

        Spacer(Modifier.height(8.dp))
        ExpandableSection(
            title = "Accessibilities",
            subtitle = "Voice, wake word, vision, engines & PIN vault",
            fg = fg, stroke = stroke
        ) {
            SettingToggle("Voice Output", voiceEnabled, onVoiceEnabled, fg, stroke)
            SettingToggle("Wake Word", wakeEnabled, onWakeEnabled, fg, stroke)
            SettingToggle("Screen Vision", screenCapEnabled, onScreenCapEnabled, fg, stroke)
            SettingToggle("Market Engine", marketEnabled, onMarketEnabled, fg, stroke)
            SettingToggle("Automation", automationEnabled, onAutomationEnabled, fg, stroke)
            Spacer(Modifier.height(12.dp))
            Text(
                if (isWakeTrained) "Wake status: trained — say \"Hey Frit\"" else "Wake status: not trained",
                color = fg, fontSize = 13.sp, fontFamily = Montserrat
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onTrainWakeWord,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = fg)
            ) {
                Text(if (isWakeTrained) "Retrain wake word" else "Train wake word", color = bg, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = stroke)
            Spacer(Modifier.height(12.dp))
            Text("App PIN vault", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
            Text(
                "Stored on this phone only (app-private). The AI receives it ONLY when it calls get_app_pin for an app screen demanding a PIN — e.g. OPay transaction PIN — and types it straight into the field. Phone lockscreen PINs cannot be automated by any third-party app (Android seals secure windows); keep the phone unlocked via Smart Lock / Extend Unlock for scheduled tasks instead.",
                color = fg, fontSize = 12.sp, fontFamily = Montserrat
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = appPin,
                onValueChange = onAppPinChange,
                label = { Text(if (appPin.isBlank()) "No PIN stored" else "PIN stored ••••", color = fg.copy(alpha = 0.6f)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = fg, unfocusedTextColor = fg, focusedBorderColor = fg, unfocusedBorderColor = stroke)
            )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSaveAppPin,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = fg)
                ) { Text("Save PIN", color = bg, fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = onClearAppPin, modifier = Modifier.weight(1f)) {
                    Text("Clear", color = fg)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = stroke)
        Spacer(Modifier.height(8.dp))

        ExpandableSection(
            title = "Screen veil (task lock)",
            subtitle = "FRIT's own lock while tasks run",
            fg = fg, stroke = stroke
        ) {
            val veil = com.frit.app.features.veil.VeilManager
            var showVeilPinDialog by remember { mutableStateOf(false) }
            var veilPin1 by remember { mutableStateOf("") }
            var veilPin2 by remember { mutableStateOf("") }
            var veilPinOld by remember { mutableStateOf("") }
            var veilPinErr by remember { mutableStateOf<String?>(null) }
            val veilPinSet = remember(veilEnabled, showVeilPinDialog) { veil.isPinSet(context) }
            val overlayGranted = remember { android.provider.Settings.canDrawOverlays(context) }
            val sysSecure = remember { veil.isSystemLockSecure(context) }

            SettingToggle("Screen veil", veilEnabled, { on ->
                if (on && !veil.isPinSet(context)) {
                    veilPinErr = "Set a veil PIN first — the veil must never stand guard without one."
                    veilPin1 = ""; veilPin2 = ""; veilPinOld = ""
                    showVeilPinDialog = true
                } else onVeilEnabled(on)
            }, fg, stroke)
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    !veilEnabled -> "Off. Tasks pause on the system lock as before."
                    !veilPinSet -> "On, but no PIN yet — set one below to arm it."
                    sysSecure -> "Ready, with one limit: your system lock is a secure PIN, which Android never lets any app dismiss. For hands-free tasks, relax it to Swipe (button below) — the veil then guards the phone instead."
                    else -> "Armed. The veil guards the phone while a task runs, is scheduled, or auto-mode is on; otherwise your normal lock shows. Face re-entry arrives in Phase 2."
                },
                color = fg, fontSize = 12.sp, fontFamily = Montserrat
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    veilPin1 = ""; veilPin2 = ""; veilPinOld = ""; veilPinErr = null
                    showVeilPinDialog = true
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = fg)
            ) { Text(if (veilPinSet) "Change veil PIN" else "Set veil PIN", color = bg, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(8.dp))
            var faceTick by remember { mutableStateOf(0) }
            val faceEnrolled = remember(veilEnabled, faceTick) {
                com.frit.app.features.veil.FaceVerifier.isEnrolled(context)
            }
            val enrollLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { faceTick++ }
            OutlinedButton(
                onClick = {
                    try {
                        enrollLauncher.launch(android.content.Intent(
                            context,
                            com.frit.app.features.veil.FaceEnrollActivity::class.java
                        ))
                    } catch (e: Exception) { com.frit.app.core.Diag.warn("MainActivity", "launch FaceEnrollActivity", e) }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (faceEnrolled) "Re-enroll face (pickup check)" else "Enroll face (pickup check)", color = fg) }
            Spacer(Modifier.height(4.dp))
            Text(
                if (faceEnrolled) "Face enrolled on this phone only — pickups during tasks verify silently, strangers get the ringtone."
                else "No face enrolled: pickups lock the veil without face check (PIN still works). Enrollment is 3 quick captures, kept on-device.",
                color = fg, fontSize = 12.sp, fontFamily = Montserrat
            )
            Spacer(Modifier.height(8.dp))
            if (!overlayGranted) {
                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ))
                        } catch (e: Exception) { com.frit.app.core.Diag.warn("MainActivity", "overlay permission intent", e) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Allow display over other apps", color = fg) }
                Spacer(Modifier.height(4.dp))
                Text(
                    "What it's for: waking the screen already works without this (tasks pulse it awake). This permission is what lets the guard itself appear over the lock screen — without it, tasks still run with the screen on, but awake and unguarded.",
                    color = fg, fontSize = 12.sp, fontFamily = Montserrat
                )
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
                    } catch (e: Exception) { com.frit.app.core.Diag.warn("MainActivity", "security settings intent", e) }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open system lock settings", color = fg) }
            Spacer(Modifier.height(8.dp))
            Text(
                "Safety: keep your phone around you while tasks run. Forgot the veil PIN? Restart the phone — the veil stays down for 2 minutes after boot so you can reset it here.",
                color = fg, fontSize = 12.sp, fontFamily = Montserrat
            )
            Spacer(Modifier.height(4.dp))

            if (showVeilPinDialog) {
                AlertDialog(
                    onDismissRequest = { showVeilPinDialog = false },
                    title = { Text(if (veilPinSet) "Change veil PIN" else "Set veil PIN") },
                    text = {
                        Column {
                            if (veilPinSet) {
                                OutlinedTextField(
                                    value = veilPinOld,
                                    onValueChange = { veilPinOld = it.filter { c -> c.isDigit() }.take(8) },
                                    label = { Text("Current PIN") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            OutlinedTextField(
                                value = veilPin1,
                                onValueChange = { veilPin1 = it.filter { c -> c.isDigit() }.take(8) },
                                label = { Text("New PIN (4–8 digits)") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = veilPin2,
                                onValueChange = { veilPin2 = it.filter { c -> c.isDigit() }.take(8) },
                                label = { Text("Confirm new PIN") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            veilPinErr?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, color = Color(0xFFC62828), fontSize = 12.sp)
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (veilPin1 != veilPin2) { veilPinErr = "PINs don't match"; return@Button }
                            val err = veil.setPin(context, veilPin1, veilPinOld.ifBlank { null })
                            if (err == null) {
                                showVeilPinDialog = false
                                if (!veilEnabled) onVeilEnabled(true)
                                Toast.makeText(context, "Veil PIN saved", Toast.LENGTH_SHORT).show()
                            } else veilPinErr = err
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showVeilPinDialog = false }) { Text("Cancel") }
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = stroke)
        Spacer(Modifier.height(8.dp))

        // NOTE: server URL + API token fields were removed from Settings — users
        // don't know what a "brain server" is. Values come baked from the build
        // (BuildConfig) with TokenStore override; support can still rotate via
        // TokenStore.set() without an app update.
        OnDeviceModelSection(fg = fg, stroke = stroke, bg = bg)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = stroke)
        Spacer(Modifier.height(8.dp))

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { com.frit.app.core.DataWipe.wipeAll(context) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C3A2F)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Delete my data", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

/** Export a chat as shareable .txt (cache + system share sheet). */
fun exportChat(context: android.content.Context, title: String, messages: List<com.frit.app.data.ChatMessage>) {
    try {
        val safe = title.ifBlank { "Chat" }.replace(Regex("[^A-Za-z0-9-_ ]"), "").trim().take(40).ifBlank { "Chat" }
        val sb = StringBuilder("Frit chat export — $safe\n")
        sb.append("=".repeat(32)).append("\n")
        messages.forEach { m ->
            if (m.content.isBlank()) return@forEach
            sb.append(if (m.role == "user") "\nYou: " else "\nFrit: ").append(m.content.trim()).append("\n")
        }
        val dir = java.io.File(context.cacheDir, "exports").also { it.mkdirs() }
        val file = java.io.File(dir, "frit-chat-${System.currentTimeMillis()}.txt")
        file.writeText(sb.toString())
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(share, "Export chat"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

@Composable
fun ChatsScreen(
    fg: Color, surf: Color, stroke: Color, bg: Color,
    onClose: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit
) {
    val context = LocalContext.current
    val chatsList = remember { mutableStateListOf<com.frit.app.core.ChatHistory.SavedChat>() }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        chatsList.clear()
        chatsList.addAll(com.frit.app.core.ChatHistory.snapshot())
    }
    val visibleChats = if (expanded) chatsList else chatsList.take(5)
    Column(Modifier.fillMaxSize().background(bg).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Chats", color = fg, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = fg) }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = fg)) {
            Text("Start a new chat", color = bg)
        }
        Spacer(Modifier.height(20.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(visibleChats, key = { _, c -> c.id }) { _, c ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(c.id) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(c.title.ifBlank { "Chat" }, color = fg, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        exportChat(context, c.title.ifBlank { "Chat" }, c.messages)
                    }) {
                        Icon(Icons.Default.Share, "Export chat", tint = fg)
                    }
                    IconButton(onClick = {
                        com.frit.app.core.ChatHistory.delete(c.id)
                        chatsList.remove(c)
                    }) {
                        Icon(Icons.Default.Delete, "Delete Chat", tint = Color.Red)
                    }
                }
            }
        }
        if (chatsList.size > 5) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (expanded) "Show less" else "Show all ${chatsList.size} chats",
                    color = fg
                )
            }
        }
    }
}

@Composable
fun AppScheduleDialog(
    appLabel: String, pkg: String,
    fg: Color, surf: Color, stroke: Color, bg: Color,
    onDismiss: () -> Unit,
    onSave: (intervalText: String, taskText: String) -> Unit
) {
    var intervalText by remember { mutableStateOf("") }
    var taskText by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(surf)
                .border(1.dp, stroke, RoundedCornerShape(16.dp)).padding(20.dp)
        ) {
            Text("Watch schedule", color = fg, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
            Spacer(Modifier.height(4.dp))
            Text(appLabel, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
            Text(pkg, color = fg.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = Montserrat)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = intervalText,
                onValueChange = { intervalText = it },
                label = { Text("Every… (e.g. 30m, 2h, 1 day)", color = fg.copy(alpha = 0.6f)) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = fg, unfocusedTextColor = fg, focusedBorderColor = fg, unfocusedBorderColor = stroke)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = taskText,
                onValueChange = { taskText = it },
                label = { Text("Task (e.g. check unread chats)", color = fg.copy(alpha = 0.6f)) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = fg, unfocusedTextColor = fg, focusedBorderColor = fg, unfocusedBorderColor = stroke)
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Cancel", color = fg)
                }
                Button(
                    onClick = { onSave(intervalText, taskText) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = fg)
                ) { Text("Save", color = bg, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// Embedded inside SettingsScreen (which already scrolls), so the list is a
// fixed-height LazyColumn rather than fillMaxSize — a LazyColumn with
// unbounded height inside verticalScroll would crash at measure time.
@Composable
fun AutoModeAppsSection(
    fg: Color, surf: Color, stroke: Color, bg: Color
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) } // dropdown: collapsed by default
    var filter by remember { mutableStateOf("") }
    var scheduleFor by remember { mutableStateOf<com.frit.app.features.automation.AutoModeApps.AppEntry?>(null) }
    val apps = remember { mutableStateListOf<com.frit.app.features.automation.AutoModeApps.AppEntry>() }
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.frit.app.features.automation.AutoModeApps.listInstalled(context)
        }
        val sel = com.frit.app.features.automation.AutoModeApps.selected(context)
        apps.clear()
        apps.addAll(list)
        checked.clear()
        list.forEach { checked[it.packageName] = sel.contains(it.packageName) }
        loading = false
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Apps the AI watches", color = fg, fontSize = 16.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
                Text(
                    if (loading) "Loading apps…"
                    else "${checked.values.count { it }} of ${apps.size} watched — tap to open",
                    color = fg.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = Montserrat
                )
            }
            Text(if (expanded) "▲" else "▼", color = fg, fontSize = 14.sp)
        }
        if (!expanded) return@Column
        Spacer(Modifier.height(8.dp))
        Text(
            "Checked apps are watched: their notifications wake the agent, which replies or acts as you would.",
            color = fg, fontSize = 13.sp, fontFamily = Montserrat
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val all = apps.map { it.packageName }.toSet()
                    com.frit.app.features.automation.AutoModeApps.replaceAll(context, all)
                    checked.clear()
                    apps.forEach { checked[it.packageName] = true }
                    Toast.makeText(context, "Watching all ${apps.size} apps — expect frequent agent turns", Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Check all", color = fg) }
            OutlinedButton(
                onClick = {
                    com.frit.app.features.automation.AutoModeApps.replaceAll(context, emptySet())
                    checked.clear()
                    apps.forEach { checked[it.packageName] = false }
                    Toast.makeText(context, "Unwatched all apps", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Uncheck all", color = fg) }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Search apps", color = fg.copy(alpha = 0.6f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = fg, unfocusedTextColor = fg, focusedBorderColor = fg, unfocusedBorderColor = stroke)
        )
        Spacer(Modifier.height(12.dp))
        scheduleFor?.let { target ->
            AppScheduleDialog(
                appLabel = target.label, pkg = target.packageName,
                fg = fg, surf = surf, stroke = stroke, bg = bg,
                onDismiss = { scheduleFor = null },
                onSave = { intervalText, taskText ->
                    val sched = com.frit.app.features.automation.AutomationScheduler
                    val secs = sched.parseIntervalInput(intervalText, 30L)
                    sched.scheduleAppTask(
                        target.packageName, target.label,
                        taskText.ifBlank { "Check for anything needing attention" }, secs
                    )
                    checked[target.packageName] = true
                    Toast.makeText(context, "Watching ${target.label} every ${sched.formatInterval(secs)}", Toast.LENGTH_LONG).show()
                    scheduleFor = null
                }
            )
        }
        if (loading) {
            Text("Loading apps…", color = fg, fontSize = 14.sp, fontFamily = Montserrat)
        } else {
            val shown = apps.filter {
                filter.isBlank() || it.label.contains(filter, true) || it.packageName.contains(filter, true)
            }
            Column(Modifier.fillMaxWidth().height(380.dp).verticalScroll(rememberScrollState())) {
                shown.forEach { app ->
                    val isChecked = checked[app.packageName] == true
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            val next = !isChecked
                            checked[app.packageName] = next
                            com.frit.app.features.automation.AutoModeApps.setSelected(context, app.packageName, next)
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val hue = ((app.packageName.hashCode() and 0x7fffffff) % 360).toFloat()
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(Color.hsv(hue, 0.5f, 0.62f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                app.label.firstOrNull()?.uppercase() ?: "?",
                                color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(
                                (if (app.isSystem) "system · " else "") + app.packageName,
                                color = fg.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1
                            )
                        }
                        IconButton(onClick = { scheduleFor = app }) {
                            Icon(Icons.Default.Alarm, "Schedule watch", tint = fg)
                        }
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = {
                                checked[app.packageName] = it
                                com.frit.app.features.automation.AutoModeApps.setSelected(context, app.packageName, it)
                            }
                        )
                    }
                    HorizontalDivider(color = stroke.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
fun AutomationScreen(
    fg: Color, surf: Color, stroke: Color, bg: Color,
    automodeEnabled: Boolean,
    onAutomodeEnabled: (Boolean) -> Unit,
    watchedAppCount: Int,
    notifAccessGranted: Boolean,
    onEnableNotifAccess: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val activeTasks = remember { mutableStateListOf<String>() }
    var newTaskText by remember { mutableStateOf("") }
    var newIntervalMins by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        activeTasks.clear()
        activeTasks.addAll(com.frit.app.features.automation.AutomationScheduler.listActiveTasks())
    }
    Column(Modifier.fillMaxSize().background(bg).padding(24.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Automation", color = fg, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = fg) }
        }
        Spacer(Modifier.height(20.dp))

        // ---- Auto Mode (moved from Settings — same functions, new home) ----
        SettingToggle("Auto Mode", automodeEnabled, onAutomodeEnabled, fg, stroke)
        Text(
            "AI harnesses the phone: auto-answers calls, supervises watchers, runs scheduled tasks, live status in notification.",
            color = fg, fontSize = 13.sp, fontFamily = Montserrat
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Watching $watchedAppCount app(s)" + if (notifAccessGranted) " · notification access ON" else " · notification access OFF",
            color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat
        )
        Spacer(Modifier.height(8.dp))
        if (!notifAccessGranted) {
            OutlinedButton(onClick = onEnableNotifAccess, modifier = Modifier.fillMaxWidth()) {
                Text("Enable notification access", color = fg)
            }
            Spacer(Modifier.height(8.dp))
        }
        AutoModeAppsSection(fg = fg, surf = surf, stroke = stroke, bg = bg)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val ok = com.frit.app.core.A11yKeeper.requestBatteryExemption(context)
                Toast.makeText(
                    context,
                    if (ok) "Choose “Allow” so the system never sleeps Frit" else "Already exempt — Frit is protected",
                    Toast.LENGTH_LONG
                ).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Keep Frit alive (battery exemption)", color = fg) }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = stroke)
        Spacer(Modifier.height(16.dp))

        Text("Scheduled tasks", color = fg, fontSize = 17.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = newTaskText,
            onValueChange = { newTaskText = it },
            label = { Text("Task Description / Prompt", color = fg.copy(alpha = 0.6f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = fg, unfocusedTextColor = fg, focusedBorderColor = fg, unfocusedBorderColor = stroke)
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = newIntervalMins,
            onValueChange = { newIntervalMins = it },
            label = { Text("Interval (e.g. 180, 180 mins, 3 hours, 90s)", color = fg.copy(alpha = 0.6f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = fg, unfocusedTextColor = fg, focusedBorderColor = fg, unfocusedBorderColor = stroke)
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val secs = com.frit.app.features.automation.AutomationScheduler.parseIntervalInput(newIntervalMins, 5L)
                if (newTaskText.isNotBlank()) {
                    com.frit.app.features.automation.AutomationScheduler.scheduleTask(newTaskText, newTaskText, secs)
                    activeTasks.clear()
                    activeTasks.addAll(com.frit.app.features.automation.AutomationScheduler.listActiveTasks())
                    newTaskText = ""
                    newIntervalMins = ""
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = fg),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add Task", color = bg)
        }

        Spacer(Modifier.height(20.dp))
        Text("Active Tasks:", color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
        Spacer(Modifier.height(8.dp))

    Column(Modifier.fillMaxWidth()) {
            activeTasks.forEach { taskId ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(taskId, color = fg, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        com.frit.app.features.automation.AutomationScheduler.cancelTask(taskId)
                        activeTasks.remove(taskId)
                    }) { Icon(Icons.Default.Delete, null, tint = Color.Red) }
                }
            }
        }
    }
}

// Wallet & Subscription — brand: FritEspresso boxes on FritCream, FritCream text on FritEspresso.
// Subscription = paying for the app (tier unlock). Wallet = YOUR money the AI
// spends for you (orders, bookings). Two separate pots, two separate flows.
@Composable
private fun SelectBox(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    fg: Color,
    bg: Color,
    stroke: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) fg else bg)
            .border(1.dp, if (selected) fg else stroke, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = if (selected) bg else fg, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat, modifier = Modifier.weight(1f))
                if (selected) Text("●", color = bg, fontSize = 10.sp)
            }
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = if (selected) bg.copy(alpha = 0.75f) else fg.copy(alpha = 0.65f), fontSize = 10.sp, fontFamily = Montserrat)
        }
    }
}

@Composable
private fun WalletField(
    value: String, onChange: (String) -> Unit, label: String,
    fg: Color, bg: Color, stroke: Color,
    numbersOnly: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(if (numbersOnly) it.filter { c -> c.isDigit() } else it) },
        label = { Text(label, color = fg.copy(alpha = 0.55f), fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = fg, unfocusedTextColor = fg,
            focusedBorderColor = fg, unfocusedBorderColor = stroke,
            focusedContainerColor = bg, unfocusedContainerColor = bg
        )
    )
}

@Composable
private fun WalletButton(text: String, enabled: Boolean, fg: Color, bg: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = fg, disabledContainerColor = fg.copy(alpha = 0.4f))
    ) { Text(text, color = bg, fontWeight = FontWeight.Bold, fontFamily = Montserrat) }
}

@Composable
private fun PasscodeCard(code: String, caption: String, fg: Color, bg: Color) {
    if (code.isBlank()) return
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(fg).padding(10.dp)
    ) {
        Column {
            Text(caption, color = bg.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = Montserrat)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(code, color = bg, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat, modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    },
                    border = BorderStroke(1.dp, bg.copy(alpha = 0.5f))
                ) { Text("Copy", color = bg, fontSize = 11.sp) }
            }
        }
    }
}

private fun naira(kobo: Long): String = "₦${"%,.0f".format(kobo / 100.0)}"

@Composable
fun WalletScreen(
    fg: Color, surf: Color, stroke: Color, bg: Color,
    onClose: () -> Unit,
    onCreatorTap: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    var tierSel by remember { mutableStateOf("creator") }
    var methodSel by remember { mutableStateOf("bank") } // bank | usdt | btc
    var txNet by remember { mutableStateOf("usdt") } // usdt | btc
    var topupNaira by remember { mutableStateOf("") }
    var creditTopupNaira by remember { mutableStateOf("") }
    var payId by remember { mutableStateOf("") }
    var orderPass by remember { mutableStateOf("") }
    var orderInfo by remember { mutableStateOf("") }
    var passcode by remember { mutableStateOf("") }
    var sender by remember { mutableStateOf("") }
    var txid by remember { mutableStateOf("") }
    var paidResult by remember { mutableStateOf("") }
    var redeemCode by remember { mutableStateOf("") }

    // Parsed account state (never raw JSON on screen).
    var tier by remember { mutableStateOf("free") }
    var expiry by remember { mutableStateOf("—") }
    var useToday by remember { mutableStateOf("") }
    var useMonth by remember { mutableStateOf("") }
    var balanceKobo by remember { mutableStateOf(0L) }
    var credits by remember { mutableStateOf(0) }
    var creditsMo by remember { mutableStateOf(0) }
    var planFeatures by remember { mutableStateOf(listOf<String>()) }
    var starterPrice by remember { mutableStateOf("") }
    var creatorPrice by remember { mutableStateOf("") }
    var traderPrice by remember { mutableStateOf("") }
    var proPrice by remember { mutableStateOf("") }
    var starterFeat by remember { mutableStateOf("") }
    var creatorFeat by remember { mutableStateOf("") }
    var traderFeat by remember { mutableStateOf("") }
    var proFeat by remember { mutableStateOf("") }
    var bankLine by remember { mutableStateOf("") }
    var bscAddress by remember { mutableStateOf("") }

    var fundOpen by remember { mutableStateOf(false) }
    var wdOpen by remember { mutableStateOf(false) }
    var wdBank by remember { mutableStateOf("") }
    var wdNumber by remember { mutableStateOf("") }
    var wdName by remember { mutableStateOf("") }
    var wdNaira by remember { mutableStateOf("") }
    var withdrawals by remember { mutableStateOf(listOf<Triple<String, String, String>>()) }
    var linked by remember { mutableStateOf(true) }
    var fbMsg by remember { mutableStateOf("") }
    var fbRating by remember { mutableStateOf(5) }

    fun netErr(): String = "Network error — check connection and try again."

    // Each section fetches independently: one failing call must never blank
    // the rest of the screen (that failure mode showed ₦0 prices + 0 credits).
    fun refresh() {
        scope.launch {
            busy = true
            var failures = 0
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.ensureInstallKey() }
            } catch (_: Exception) { failures++ }
            try {
                val s = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.billingStatus() })
                if (s.optString("status") == "error") { failures++ }
                else {
                    tier = s.optString("tier", "free")
                    expiry = s.optString("expires_at", "—").take(10)
                    credits = s.optInt("credits", 0)
                    creditsMo = s.optInt("credits_mo", 0)
                    linked = s.optBoolean("linked", true)
                    planFeatures = s.optJSONArray("features")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList()
                    val t = s.optJSONObject("usage_today")
                    val m = s.optJSONObject("usage_month")
                    useToday = "Turns ${t?.optInt("turns", 0) ?: 0}"
                    useMonth = "Turns ${m?.optInt("turns", 0) ?: 0} · Clips ${m?.optInt("clips", 0) ?: 0} · Images ${m?.optInt("images", 0) ?: 0}"
                }
            } catch (_: Exception) { failures++ }
            try {
                val b = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.walletBalance() })
                if (b.optString("status") == "error") failures++ else balanceKobo = b.optLong("balance_kobo", 0)
            } catch (_: Exception) { failures++ }
            try {
                val p = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.billingPayInfo() })
                if (p.optString("status") == "error") { failures++ }
                else {
                    val pt = p.optJSONObject("tiers")
                    // Server sends kobo (x100); fall back to legacy naira x100 for old servers.
                    fun priceKobo(koboKey: String, nairaKey: String): String {
                        val k = pt?.optLong(koboKey, 0) ?: 0
                        val v = if (k > 0) k else (pt?.optLong(nairaKey, 0) ?: 0) * 100
                        return naira(v) + "/mo"
                    }
                    starterPrice = priceKobo("starter_kobo", "starter_naira")
                    creatorPrice = priceKobo("creator_kobo", "creator_naira")
                    traderPrice = priceKobo("trader_kobo", "trader_naira")
                    proPrice = priceKobo("pro_kobo", "pro_naira")
                    val bk = p.optJSONObject("bank")
                    bankLine = "${bk?.optString("bank_name", "")} · ${bk?.optString("account_number", "")} · ${bk?.optString("account_name", "")}"
                    bscAddress = p.optJSONObject("crypto")?.optString("bsc_address", "") ?: ""
                }
            } catch (_: Exception) { failures++ }
            try {
                val tiersResp = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.billingTiers() })
                if (tiersResp.optString("status") == "error") { failures++ }
                else {
                    val tiers = tiersResp.optJSONObject("tiers")
                    val labelMap = tiersResp.optJSONObject("feature_labels")?.let { lm ->
                        lm.keys().asSequence().associateWith { lm.optString(it, it) }
                    } ?: emptyMap()
                    fun feat(k: String): String {
                        val o = tiers?.optJSONObject(k) ?: return ""
                        val cr = o.optInt("credits_mo", 0)
                        val feats = o.optJSONArray("features")?.let { arr ->
                            (0 until arr.length()).map { labelMap[arr.getString(it)] ?: arr.getString(it) }
                        } ?: emptyList()
                        return "$cr credits/mo · ${feats.joinToString(", ")}"
                    }
                    starterFeat = feat("starter"); creatorFeat = feat("creator"); traderFeat = feat("trader"); proFeat = feat("pro")
                }
            } catch (_: Exception) { failures++ }
            try {
                val wds = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.walletWithdrawals() }).optJSONArray("withdrawals")
                val list = mutableListOf<Triple<String, String, String>>()
                for (i in 0 until (wds?.length() ?: 0)) {
                    val w = wds.getJSONObject(i)
                    list.add(Triple(w.optString("id", ""), naira(w.optLong("amount_kobo", 0)), w.optString("status", "")))
                }
                withdrawals = list
            } catch (_: Exception) { failures++ }
            notice = when {
                failures >= 4 -> netErr()
                !linked -> "Linking this device… connect to the internet and reopen Wallet to claim free credits."
                else -> ""
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().background(bg).padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wallet", color = fg, fontSize = 20.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = fg) }
        }
        Spacer(Modifier.height(10.dp))

        // ---- Status card ----
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(fg).padding(12.dp)) {
            Column {
                Text("Current plan", color = bg.copy(alpha = 0.65f), fontSize = 10.sp, fontFamily = Montserrat)
                Text(tier.uppercase(), color = bg, fontSize = 22.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
                Text("Active until $expiry", color = bg.copy(alpha = 0.75f), fontSize = 11.sp, fontFamily = Montserrat)
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = bg.copy(alpha = 0.2f))
                Spacer(Modifier.height(6.dp))
                Text("Credits: $credits / $creditsMo per month", color = bg, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
                Text("Today · $useToday", color = bg, fontSize = 11.sp, fontFamily = Montserrat)
                Text("This month · $useMonth", color = bg.copy(alpha = 0.8f), fontSize = 11.sp, fontFamily = Montserrat)
            }
        }
        Spacer(Modifier.height(10.dp))

        // ---- Wallet balance card (tap to fund) ----
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(surf)
                .border(1.dp, stroke, RoundedCornerShape(12.dp))
                .clickable { fundOpen = !fundOpen }.padding(12.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Spending wallet", color = fg.copy(alpha = 0.65f), fontSize = 10.sp, fontFamily = Montserrat)
                        Text(naira(balanceKobo), color = fg, fontSize = 20.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
                    }
                    Text(if (fundOpen) "▲" else "▼", color = fg, fontSize = 12.sp)
                }
                if (fundOpen) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Money for the AI to spend FOR you — flight and order bookings, services. " +
                            "Fund it below; unspent balance stays yours and is withdrawable anytime.",
                        color = fg.copy(alpha = 0.75f), fontSize = 12.sp, fontFamily = Montserrat
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Pay to: $bankLine", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
                    if (bscAddress.isNotBlank()) Text("Crypto (BNB Smart Chain): $bscAddress", color = fg, fontSize = 11.sp, fontFamily = Montserrat)
                    Spacer(Modifier.height(6.dp))
                    WalletField(value = topupNaira, onChange = { topupNaira = it }, label = "Amount in naira (min ₦100)", fg = fg, bg = bg, stroke = stroke, numbersOnly = true)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("bank" to "Bank", "usdt" to "USDT", "btc" to "BTC").forEach { (v, l) ->
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                    .background(if (methodSel == v) fg else bg)
                                    .border(1.dp, stroke, RoundedCornerShape(8.dp))
                                    .clickable { methodSel = v }.padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) { Text(l, color = if (methodSel == v) bg else fg, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    WalletButton("Fund wallet", !busy, fg = fg, bg = bg) {
                        val kobo = (topupNaira.toLongOrNull() ?: 0L) * 100L
                        if (kobo < 10000) { notice = "Minimum top-up is ₦100."; return@WalletButton }
                        scope.launch {
                            busy = true
                            try {
                                val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.frit.app.data.NetworkClient.walletTopup(kobo, if (methodSel == "bank") "bank" else "crypto")
                                })
                                payId = o.optString("payment_id", "")
                                orderPass = o.optString("passcode", "")
                                orderInfo = o.optString("instruction", "")
                                notice = ""
                            } catch (e: Exception) { notice = "Network error — check connection and try again." }
                            busy = false
                        }
                    }
                }
            }
        }
        if (notice.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(notice, color = Color(0xFF9C3A2F), fontSize = 12.sp, fontFamily = Montserrat)
        }

        Spacer(Modifier.height(10.dp))
        Text("Choose your plan — yearly = 10x monthly (2 months free)", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
        Spacer(Modifier.height(6.dp))
        SelectBox("Automation", "$starterPrice\n$starterFeat", tierSel == "starter", { tierSel = "starter" }, fg = fg, bg = bg, stroke = stroke, modifier = Modifier.weight(1f))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectBox("Content Creator", "$creatorPrice\n$creatorFeat", tierSel == "creator", { tierSel = "creator" }, fg = fg, bg = bg, stroke = stroke, modifier = Modifier.weight(1f))
            SelectBox("Trader", "$traderPrice\n$traderFeat", tierSel == "trader", { tierSel = "trader" }, fg = fg, bg = bg, stroke = stroke, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        SelectBox("Pro — everything", "$proPrice\n$proFeat", tierSel == "pro", { tierSel = "pro" }, fg = fg, bg = bg, stroke = stroke, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text("Free forever: 200 credits/mo · chat + market data. No card needed.", color = fg.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = Montserrat)
        Spacer(Modifier.height(8.dp))

        // ---- Credit top-up (any amount) ----
        Text("Top up credits — any amount, ₦10 = 1 credit", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
        Spacer(Modifier.height(6.dp))
        WalletField(value = creditTopupNaira, onChange = { creditTopupNaira = it }, label = "Amount in naira (min ₦100 = 10 credits)", fg = fg, bg = bg, stroke = stroke, numbersOnly = true)
        val previewCredits = (creditTopupNaira.toLongOrNull() ?: 0L) / 10L
        if (previewCredits > 0) Text("≈ $previewCredits credits (1 credit ≈ 1 chat · 4 credits ≈ 1 image · 65 credits ≈ 1 video clip)", color = fg.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = Montserrat)
        Spacer(Modifier.height(6.dp))
        WalletButton("Buy credits", !busy, fg = fg, bg = bg) {
            val kobo = (creditTopupNaira.toLongOrNull() ?: 0L) * 100L
            if (kobo < 10000) { notice = "Minimum credit top-up is ₦100."; return@WalletButton }
            scope.launch {
                busy = true
                try {
                    val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.frit.app.data.NetworkClient.billingTopupCredits(kobo, if (methodSel == "bank") "bank" else "crypto")
                    })
                    if (o.optString("status") == "error") { notice = o.optString("message", o.optString("error", "Top-up failed.")) }
                    else {
                        payId = o.optString("payment_id", "")
                        orderPass = o.optString("passcode", "")
                        orderInfo = o.optString("instruction", "")
                        notice = ""
                    }
                } catch (e: Exception) { notice = "Network error — check connection and try again." }
                busy = false
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Pay with", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectBox("Bank", "Transfer + narration", methodSel == "bank", { methodSel = "bank" }, fg = fg, bg = bg, stroke = stroke, modifier = Modifier.weight(1f))
            SelectBox("USDT", "BNB Smart Chain", methodSel == "usdt", { methodSel = "usdt" }, fg = fg, bg = bg, stroke = stroke, modifier = Modifier.weight(1f))
            SelectBox("BTC", "BNB Smart Chain", methodSel == "btc", { methodSel = "btc" }, fg = fg, bg = bg, stroke = stroke, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        WalletButton("Subscribe — $tierSel", !busy, fg = fg, bg = bg) {
            scope.launch {
                busy = true
                try {
                    val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.frit.app.data.NetworkClient.billingSubscribe(tierSel, if (methodSel == "bank") "bank" else "crypto")
                    })
                    if (o.optString("status") == "error") { notice = o.optString("message", o.optString("error", "Subscribe failed.")) }
                    else {
                        payId = o.optString("payment_id", "")
                        orderPass = o.optString("passcode", "")
                        orderInfo = o.optString("instruction", "")
                        notice = ""
                    }
                } catch (e: Exception) { notice = "Network error — check connection and try again." }
                busy = false
            }
        }
        if (orderPass.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            PasscodeCard(orderPass, "Your payment passcode — use as transfer narration", fg = fg, bg = bg)
            Spacer(Modifier.height(6.dp))
            Text(orderInfo, color = fg.copy(alpha = 0.8f), fontSize = 12.sp, fontFamily = Montserrat)
        }

        Spacer(Modifier.height(10.dp))
        Text("I've paid", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
        Spacer(Modifier.height(6.dp))
        WalletField(value = passcode, onChange = { passcode = it }, label = "Passcode from your order", fg = fg, bg = bg, stroke = stroke)
        Spacer(Modifier.height(6.dp))
        WalletField(value = sender, onChange = { sender = it }, label = "Sender name (bank only, optional)", fg = fg, bg = bg, stroke = stroke)
        Spacer(Modifier.height(6.dp))
        WalletField(value = txid, onChange = { txid = it }, label = "Crypto tx hash (bank payers skip)", fg = fg, bg = bg, stroke = stroke)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("usdt" to "USDT", "btc" to "BTC").forEach { (v, l) ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (txNet == v) fg else bg)
                        .border(1.dp, stroke, RoundedCornerShape(8.dp))
                        .clickable { txNet = v }.padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) { Text(l, color = if (txNet == v) bg else fg, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.height(6.dp))
        WalletButton("Confirm payment", !busy, fg = fg, bg = bg) {
            if (payId.isBlank()) { notice = "Create a subscribe or top-up order first."; return@WalletButton }
            scope.launch {
                busy = true
                try {
                    val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.frit.app.data.NetworkClient.billingSubmit(payId, passcode, sender, txid, txNet)
                    })
                    paidResult = if (o.optString("status") == "approved") {
                        val ac = o.optString("activation_code", "")
                        if (ac.isNotBlank()) "Approved! Activation code: $ac" else "Approved! Wallet credited."
                    } else {
                        o.optString("error", o.optString("note", "Queued for confirmation."))
                    }
                    passcode = ""
                    notice = ""
                    refresh()
                } catch (e: Exception) { notice = "Network error — check connection and try again." }
                busy = false
            }
        }
        if (paidResult.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(paidResult, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
        }

        Spacer(Modifier.height(10.dp))
        Text("Redeem a code", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
        Spacer(Modifier.height(6.dp))
        WalletField(value = redeemCode, onChange = { redeemCode = it }, label = "Activation, trial or master code", fg = fg, bg = bg, stroke = stroke)
        Spacer(Modifier.height(6.dp))
        WalletButton("Redeem", !busy, fg = fg, bg = bg) {
            scope.launch {
                busy = true
                try {
                    val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.frit.app.data.NetworkClient.billingRedeem(redeemCode)
                    })
                    notice = if (o.optString("status") == "error" || o.has("error")) o.optString("error", o.optString("message", "Redeem failed."))
                    else if (o.optBoolean("refreshed", false)) "Unlocked ${o.optString("tier", "?")} until ${o.optString("expires_at", "?").take(10)}."
                    else "Unlocked ${o.optString("tier", "?")} until ${o.optString("expires_at", "?").take(10)}. (Re-pasting later only shows status — it never extends.)"
                    redeemCode = ""
                    refresh()
                } catch (e: Exception) { notice = "Network error — check connection and try again." }
                busy = false
            }
        }
        Text("Got a tester code? Paste it above — full features unlock instantly. Each code works once per subscription; re-pasting only shows status.", color = fg.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = Montserrat)
        Spacer(Modifier.height(12.dp))

        // ---- Withdraw ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Withdraw to my bank", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat, modifier = Modifier.weight(1f))
            Text(if (wdOpen) "▲" else "▼", color = fg, fontSize = 13.sp, modifier = Modifier.clickable { wdOpen = !wdOpen }.padding(8.dp))
        }
        if (wdOpen) {
            Spacer(Modifier.height(6.dp))
            WalletField(value = wdBank, onChange = { wdBank = it }, label = "Your bank name", fg = fg, bg = bg, stroke = stroke)
            Spacer(Modifier.height(6.dp))
            WalletField(value = wdNumber, onChange = { wdNumber = it }, label = "Your account number", fg = fg, bg = bg, stroke = stroke, numbersOnly = true)
            Spacer(Modifier.height(6.dp))
            WalletField(value = wdName, onChange = { wdName = it }, label = "Account name", fg = fg, bg = bg, stroke = stroke)
            Spacer(Modifier.height(6.dp))
            WalletField(value = wdNaira, onChange = { wdNaira = it }, label = "Amount in naira (min ₦500)", fg = fg, bg = bg, stroke = stroke, numbersOnly = true)
            Spacer(Modifier.height(6.dp))
            WalletButton("Request withdrawal", !busy, fg = fg, bg = bg) {
                val kobo = (wdNaira.toLongOrNull() ?: 0L) * 100L
                scope.launch {
                    busy = true
                    try {
                        val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.frit.app.data.NetworkClient.walletWithdraw(wdBank, wdNumber, wdName, kobo)
                        })
                        notice = if (o.has("error")) o.optString("error") else "Withdrawal requested — paid manually within 24h."
                        wdBank = ""; wdNumber = ""; wdName = ""; wdNaira = ""
                        refresh()
                    } catch (e: Exception) { notice = "Network error — check connection and try again." }
                    busy = false
                }
            }
        }
        if (withdrawals.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            withdrawals.forEach { (_, amt, st) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(amt, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(st.uppercase(), color = if (st == "paid") Color(0xFF4A7C59) else fg.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- Trial feedback ----
        Text("Testing the app? Tell us", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { r ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (fbRating == r) fg else bg)
                        .border(1.dp, stroke, RoundedCornerShape(8.dp))
                        .clickable { fbRating = r }.padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) { Text("$r", color = if (fbRating == r) bg else fg, fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.height(6.dp))
        WalletField(value = fbMsg, onChange = { fbMsg = it }, label = "What worked, what broke?", fg = fg, bg = bg, stroke = stroke)
        Spacer(Modifier.height(6.dp))
        WalletButton("Send feedback", !busy, fg = fg, bg = bg) {
            scope.launch {
                busy = true
                try {
                    withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.postFeedback(fbMsg, fbRating) }
                    notice = "Thanks — feedback received."
                    fbMsg = ""
                } catch (e: Exception) { notice = "Network error — check connection and try again." }
                busy = false
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Creator console",
            color = fg.copy(alpha = 0.55f), fontSize = 11.sp, fontFamily = Montserrat,
            modifier = Modifier.clickable { onCreatorTap() }.padding(8.dp)
        )
        Spacer(Modifier.height(24.dp))
    }
}

// Creator console — the owner's phone only. Paste ADMIN_TOKEN once, enable
// watch: the app polls /admin/inbox every 60s and posts a system notification
// for each new payment/withdrawal. Approve/decline replies carry ADMIN_TOKEN.
@Composable
fun CreatorScreen(
    fg: Color, surf: Color, stroke: Color, bg: Color,
    onClose: () -> Unit,
    onOwnerTap: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adminToken by remember { mutableStateOf(com.frit.app.core.AdminStore.get()) }
    var watchOn by remember { mutableStateOf(com.frit.app.core.AdminStore.creatorMode()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var payments by remember { mutableStateOf(listOf<org.json.JSONObject>()) }
    var withdrawals by remember { mutableStateOf(listOf<org.json.JSONObject>()) }
    var mintDays by remember { mutableStateOf("7") }
    var mintCount by remember { mutableStateOf("5") }
    var mintTier by remember { mutableStateOf("tester") }
    var mintedCodes by remember { mutableStateOf("") }
    var modelRows by remember { mutableStateOf(listOf<org.json.JSONObject>()) }
    var modelEdits by remember { mutableStateOf(mapOf<String, String>()) }
    var modelsLoaded by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            busy = true
            try {
                val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.frit.app.data.NetworkClient.adminInbox()
                })
                if (o.optString("status") == "error") { notice = o.optString("message", "Error"); }
                else {
                    val ps = mutableListOf<org.json.JSONObject>()
                    val arr = o.optJSONArray("payments")
                    for (i in 0 until (arr?.length() ?: 0)) ps.add(arr.getJSONObject(i))
                    payments = ps
                    val ws = mutableListOf<org.json.JSONObject>()
                    val warr = o.optJSONArray("withdrawals")
                    for (i in 0 until (warr?.length() ?: 0)) ws.add(warr.getJSONObject(i))
                    withdrawals = ws
                    notice = if (ps.isEmpty() && ws.isEmpty()) "Inbox clear — nothing waiting." else ""
                }
            } catch (e: Exception) { notice = "Network error — check connection." }
            busy = false
        }
    }

    LaunchedEffect(Unit) { if (adminToken.isNotBlank()) load() }

    fun nairaL(kobo: Long): String = "₦${"%,.0f".format(kobo / 100.0)}"

    Column(Modifier.fillMaxSize().background(FritCream).padding(20.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Creator console", color = FritEspresso, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = FritEspresso) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Owner only. The admin token approves payments and pays withdrawals — never share it, never screenshot it.",
            color = FritEspresso.copy(alpha = 0.7f), fontSize = 13.sp, fontFamily = Montserrat
        )
        Spacer(Modifier.height(12.dp))
        WalletField(value = adminToken, onChange = { adminToken = it }, label = "Admin token (paste once)", fg = FritEspresso, bg = FritCream, stroke = FritStroke)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(if (watchOn) FritEspresso else FritCream)
                    .border(1.dp, FritEspresso.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .clickable {
                        com.frit.app.core.AdminStore.set(adminToken)
                        com.frit.app.core.AdminStore.setCreatorWatch(!watchOn)
                        watchOn = !watchOn
                        com.frit.app.core.CreatorWatcher.sync(context)
                        Toast.makeText(context, if (!watchOn) "Watching for payments" else "Watch off", Toast.LENGTH_SHORT).show()
                    }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text(if (watchOn) "Watch ON" else "Watch OFF", color = if (watchOn) FritCream else FritEspresso, fontWeight = FontWeight.Bold) }
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(FritCream)
                    .border(1.dp, FritEspresso.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .clickable {
                        com.frit.app.core.AdminStore.set(adminToken)
                        com.frit.app.core.CreatorWatcher.sync(context)
                        load()
                    }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("Save + refresh", color = FritEspresso, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(FritEspresso)
                .clickable { onOwnerTap() }.padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) { Text("Endpoint console (owner tier)", color = FritCream, fontWeight = FontWeight.Bold) }
        if (notice.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(notice, color = FritEspresso, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Text("Payments waiting (${payments.size})", color = FritEspresso, fontSize = 17.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
        Spacer(Modifier.height(8.dp))
        payments.forEach { p ->
            val pid = p.optString("id", "")
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FritSurface)
                    .border(1.dp, FritEspresso.copy(alpha = 0.25f), RoundedCornerShape(16.dp)).padding(14.dp)
            ) {
                Column {
                    Text(
                        "${nairaL(p.optLong("amount_kobo", 0))} · ${p.optString("tier", p.optString("purpose", ""))}",
                        color = FritEspresso, fontSize = 15.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat
                    )
                    Text("From ${p.optString("user_id", "?")} · ref ${p.optString("reference", "?")}", color = FritEspresso.copy(alpha = 0.7f), fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    WalletButton("Approve", !busy, FritEspresso, FritCream) {
                        scope.launch {
                            busy = true
                            try {
                                withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.adminApprovePayment(pid) }
                                Toast.makeText(context, "Approved", Toast.LENGTH_SHORT).show()
                                load()
                            } catch (e: Exception) { notice = "Approve failed." }
                            busy = false
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text("Tester trial codes — full features, no payment", color = FritEspresso, fontSize = 17.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
        Spacer(Modifier.height(4.dp))
        Text("Mint codes, send one per tester. They open Wallet → \"Redeem a code\" and paste it — chat, images, video, voice, automation and trading all unlock until expiry. Re-pasting refreshes on their device.", color = FritEspresso.copy(alpha = 0.7f), fontSize = 12.sp, fontFamily = Montserrat)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("tester", "creator", "pro").forEach { t ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (mintTier == t) FritEspresso else FritCream)
                        .border(1.dp, FritEspresso.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .clickable { mintTier = t }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) { Text(t.replaceFirstChar { it.uppercase() }, color = if (mintTier == t) FritCream else FritEspresso, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { WalletField(value = mintDays, onChange = { mintDays = it }, label = "Days each (1-365)", fg = FritEspresso, bg = FritCream, stroke = FritStroke, numbersOnly = true) }
            Box(Modifier.weight(1f)) { WalletField(value = mintCount, onChange = { mintCount = it }, label = "How many (1-100)", fg = FritEspresso, bg = FritCream, stroke = FritStroke, numbersOnly = true) }
        }
        Spacer(Modifier.height(8.dp))
        WalletButton("Mint codes", !busy, FritEspresso, FritCream) {
            scope.launch {
                busy = true
                try {
                    val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.frit.app.data.NetworkClient.adminMint(mintTier, mintDays.toIntOrNull() ?: 7, mintCount.toIntOrNull() ?: 5)
                    })
                    if (o.optString("status") == "error") { notice = o.optString("message", "Mint failed.") }
                    else {
                        val arr = o.optJSONArray("codes")
                        val sb = StringBuilder()
                        for (i in 0 until (arr?.length() ?: 0)) sb.append(arr.getJSONObject(i).optString("code", "")).append("\n")
                        mintedCodes = sb.toString().trim()
                        notice = "Minted ${arr?.length() ?: 0} × $mintTier — copy from below, one per tester."
                    }
                } catch (e: Exception) { notice = "Network error — check connection." }
                busy = false
            }
        }
        if (mintedCodes.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(mintedCodes, color = FritEspresso, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
        }
        Spacer(Modifier.height(8.dp))
        Text("Brain & media models — change live, no rebuild", color = FritEspresso, fontSize = 17.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
        Spacer(Modifier.height(4.dp))
        Text("New model on OpenRouter? Paste its slug (vendor/model-name) and Save — it takes effect instantly. Reset returns to the default. Verify slugs at openrouter.ai/models.", color = FritEspresso.copy(alpha = 0.7f), fontSize = 12.sp, fontFamily = Montserrat)
        Spacer(Modifier.height(8.dp))
        WalletButton(if (modelsLoaded) "Reload models" else "Load models", !busy, FritEspresso, FritCream) {
            scope.launch {
                busy = true
                try {
                    val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.frit.app.data.NetworkClient.adminModels()
                    })
                    if (o.optString("status") == "error") { notice = o.optString("message", "Failed.") }
                    else {
                        val rows = mutableListOf<org.json.JSONObject>()
                        val arr = o.optJSONArray("models")
                        for (i in 0 until (arr?.length() ?: 0)) rows.add(arr.getJSONObject(i))
                        modelRows = rows
                        modelEdits = rows.associate { it.optString("key", "") to it.optString("value", "") }
                        modelsLoaded = true
                        notice = ""
                    }
                } catch (e: Exception) { notice = "Network error — check connection." }
                busy = false
            }
        }
        modelRows.forEach { m ->
            val k = m.optString("key", "")
            val label = m.optString("label", k)
            val hint = m.optString("hint", "")
            val src = m.optString("source", "")
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(FritSurface)
                    .border(1.dp, FritEspresso.copy(alpha = 0.25f), RoundedCornerShape(12.dp)).padding(10.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = FritEspresso, fontSize = 13.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                        Text(src.uppercase(), color = FritEspresso.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    if (hint.isNotBlank()) Text(hint, color = FritEspresso.copy(alpha = 0.6f), fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    WalletField(value = modelEdits[k] ?: "", onChange = { modelEdits = modelEdits + (k to it) }, label = k, fg = FritEspresso, bg = FritCream, stroke = FritStroke)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(FritEspresso)
                                .clickable {
                                    scope.launch {
                                        busy = true
                                        try {
                                            val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                com.frit.app.data.NetworkClient.adminSetModel(k, modelEdits[k] ?: "")
                                            })
                                            notice = if (o.optString("status") == "error" || o.has("error")) o.optString("error", o.optString("message", "Save failed."))
                                            else "$k live → ${o.optString("value", "")}"
                                        } catch (e: Exception) { notice = "Network error." }
                                        busy = false
                                    }
                                }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("Save", color = FritCream, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(FritCream)
                                .border(1.dp, FritEspresso.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch {
                                        busy = true
                                        try {
                                            val o = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                com.frit.app.data.NetworkClient.adminSetModel(k, "")
                                            })
                                            val d = o.optString("value", "")
                                            modelEdits = modelEdits + (k to d)
                                            notice = "$k reset → $d"
                                        } catch (e: Exception) { notice = "Network error." }
                                        busy = false
                                    }
                                }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("Reset", color = FritEspresso, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Withdrawals waiting (${withdrawals.size})", color = FritEspresso, fontSize = 17.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
        Spacer(Modifier.height(8.dp))
        withdrawals.forEach { w ->
            val wid = w.optString("id", "")
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FritSurface)
                    .border(1.dp, FritEspresso.copy(alpha = 0.25f), RoundedCornerShape(16.dp)).padding(14.dp)
            ) {
                Column {
                    Text(
                        "${nairaL(w.optLong("amount_kobo", 0))} → ${w.optString("bank_name", "?")} ${w.optString("account_number", "?")}",
                        color = FritEspresso, fontSize = 15.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat
                    )
                    Text("${w.optString("account_name", "?")} · by ${w.optString("user_id", "?")}", color = FritEspresso.copy(alpha = 0.7f), fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Send the money from YOUR bank app first, then tap Paid.", color = FritEspresso.copy(alpha = 0.7f), fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(FritEspresso)
                                .clickable {
                                    scope.launch {
                                        busy = true
                                        try {
                                            withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.adminApproveWithdrawal(wid) }
                                            Toast.makeText(context, "Marked paid", Toast.LENGTH_SHORT).show()
                                            load()
                                        } catch (e: Exception) { notice = "Failed." }
                                        busy = false
                                    }
                                }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("Paid", color = FritCream, fontWeight = FontWeight.Bold) }
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(FritCream)
                                .border(1.dp, FritEspresso.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                .clickable {
                                    scope.launch {
                                        busy = true
                                        try {
                                            withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.adminDeclineWithdrawal(wid) }
                                            load()
                                        } catch (e: Exception) { notice = "Failed." }
                                        busy = false
                                    }
                                }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("Decline", color = FritEspresso, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    } catch (_: Exception) { null }
}

private fun matchTierForFile(
    context: android.content.Context,
    filename: String
): com.frit.app.core.llm.ModelManager.ModelSpec? {
    return try {
        val lower = filename.lowercase()
        // Exact tier filename wins; otherwise size hints (0.6b/1.7b/4b, q4/q8).
        when {
            "1.7b" in lower -> com.frit.app.core.llm.ModelManager.tierByName("1.7B")
            "0.6b" in lower || "0_6b" in lower -> com.frit.app.core.llm.ModelManager.tierByName("0.6B")
            "4b" in lower -> com.frit.app.core.llm.ModelManager.tierByName("4B")
            else -> null // fall through to the RAM-picked tier
        }
    } catch (_: Exception) { null }
}

// On-Device Model — the screen the offline error messages point to. Shows which
// RAM tier this phone gets, connection type, what is actually on disk, and the
// download/load controls. Rule: download ONCE (Wi-Fi or mobile data, your
// choice), then runs fully offline forever. The download runs in
// ModelDownloadService so its notification shows real bytes/speed/path and
// survives leaving this screen.
@Composable
fun OnDeviceModelSection(fg: Color, stroke: Color, bg: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Checking…") }
    var connLine by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var showMeteredDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingApproxMB by remember { mutableStateOf(0L) }
    var customUrl by remember { mutableStateOf("") }
    var partialInfo by remember { mutableStateOf<com.frit.app.core.llm.ModelManager.PartialInfo?>(null) }
    var hasCached by remember { mutableStateOf(false) }

    fun refresh() {
        try {
            val ram = com.frit.app.core.llm.ModelManager.totalRamGB(context)
            val picked = com.frit.app.core.llm.ModelManager.pickSpec(context)
            // Report the tier actually on disk, which may differ from the tier
            // this phone's RAM picks (imported file, or a smaller fallback).
            val cached = com.frit.app.core.llm.ModelManager.findCachedModel(context)
            val partial = try {
                com.frit.app.core.llm.ModelManager.partialDownload(context)
            } catch (_: Exception) { null }
            partialInfo = partial
            hasCached = cached != null
            val state = com.frit.app.core.llm.LocalLLMEngine.statusLabel(context)
            status = buildString {
                append("This phone (${"%.1f".format(ram)}GB RAM) → ${picked.filename} (~${picked.approxMB}MB)")
                if (cached != null) {
                    append("\nOn disk: ${cached.spec.filename} (${cached.sizeMB}MB)")
                    append("\nWhere: ${com.frit.app.core.llm.ModelManager.displayPath(context, cached.file)}")
                } else {
                    append("\nNothing downloaded yet")
                }
                if (partial != null && (cached == null || cached.spec.filename != partial.name)) {
                    val have = partial.bytes / 1_000_000
                    val total = if (partial.totalBytes > 0) " of ${partial.totalBytes / 1_000_000}MB" else ""
                    append("\nInterrupted: ${partial.name} — $have MB$total (resume below)")
                }
                append("\n$state")
            }
            connLine = when (com.frit.app.core.llm.ModelManager.connectionType(context)) {
                "wifi" -> "Connection: Wi-Fi — downloads free to start"
                "mobile" -> "Connection: mobile data — downloads ask first"
                else -> "Connection: offline — only an already-downloaded model can load"
            }
        } catch (e: Exception) { status = "Status unavailable." }
    }

    // Import a GGUF you already downloaded (browser/PC copy): picker → copy
    // into the app's model folder under the matching tier filename → verify
    // (when that tier has a SHA) → auto-load. No second download needed.
    // Stored in app-private storage (shown in Settings as "App storage › …").
    val ggufPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            progress = "Importing…"
            try {
                val pickedName = queryDisplayName(context, uri) ?: "model.gguf"
                // ANY GGUF works: it becomes the active model and is loaded with
                // its own embedded chat template (no Qwen-only restriction).
                val dest = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.frit.app.core.llm.ModelManager.importCustomGguf(context, uri, pickedName)
                }
                progress = "Imported ${dest.name} — loading…"
                // The file is already on disk, so this must not fetch anything.
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.frit.app.core.llm.LocalLLMEngine.unloadModel()
                    com.frit.app.core.llm.LocalLLMEngine.loadModel(context, allowDownload = false)
                }
                progress = if (result.success) "Imported + loaded — fully offline."
                else "Imported, but it would not load: ${result.error}"
            } catch (e: Exception) {
                progress = "Import failed: ${e.message}"
            }
            busy = false
            refresh()
        }
    }

    // The download itself is owned by ModelDownloadService (foreground): it
    // keeps running when the user leaves this screen and reports real bytes,
    // speed and the destination path in its notification. This screen only
    // starts it and mirrors its state.
    fun startDownload() {
        if (com.frit.app.core.llm.ModelManager.connectionType(context) == "none") {
            progress = "No internet connection — cannot download now."
            return
        }
        progress = "Starting download…"
        com.frit.app.core.llm.ModelDownloadService.start(context)
    }

    fun stopDownload() {
        com.frit.app.core.llm.ModelDownloadService.stop(context)
        progress = "Stopping…"
    }

    fun resumeDownload() {
        val p = partialInfo ?: return
        if (com.frit.app.core.llm.ModelManager.connectionType(context) == "none") {
            progress = "No internet connection — cannot resume now."
            return
        }
        progress = "Resuming ${p.name}…"
        // Null URL = built-in tier: the service re-enters the normal path and
        // Range-resumes the .part. A stored URL resumes a custom link download.
        com.frit.app.core.llm.ModelDownloadService.start(context, p.url)
    }

    fun deleteDownloads() {
        scope.launch {
            busy = true
            progress = "Stopping download…"
            com.frit.app.core.llm.ModelDownloadService.stop(context)
            com.frit.app.core.llm.LocalLLMEngine.unloadModel()
            kotlinx.coroutines.delay(800)
            val ok = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.frit.app.core.llm.ModelManager.deleteModel(context)
                }
            } catch (_: Exception) { false }
            progress = if (ok) "Deleted all downloaded model files."
            else "Delete incomplete — some files remained."
            busy = false
            refresh()
        }
    }

    // Mirror the service so the button and status reflect reality.
    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            val running = com.frit.app.core.llm.ModelDownloadService.isRunning
            if (running != downloading) {
                downloading = running
                progress = if (running) "Downloading… see the notification for bytes, speed and path."
                else "Download finished."
                refresh()
            }
            // F-29: poll fast only while a download is live; idle checks are
            // 4x cheaper (isRunning is a plain flag, so it can't be observed).
            kotlinx.coroutines.delay(if (downloading) 500L else 2000L)
        }
    }

    if (showMeteredDialog) {
        AlertDialog(
            onDismissRequest = { showMeteredDialog = false },
            title = { Text("Use mobile data?", fontFamily = Montserrat) },
            text = { Text("This download is ~${pendingApproxMB}MB on mobile data. Continue?", fontFamily = Montserrat) },
            confirmButton = {
                TextButton(onClick = { showMeteredDialog = false; startDownload() }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { showMeteredDialog = false }) { Text("Wait for Wi-Fi") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete downloaded models?", fontFamily = Montserrat) },
            text = { Text("Removes every downloaded GGUF, interrupted partials and custom imports from app storage. This cannot be undone.", fontFamily = Montserrat) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; deleteDownloads() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Keep") }
            }
        )
    }

    Column(Modifier.fillMaxWidth()) {
        Text("On-Device Model", color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
        Spacer(Modifier.height(4.dp))
        Text(status, color = fg.copy(alpha = 0.8f), fontSize = 13.sp, fontFamily = Montserrat)
        Spacer(Modifier.height(2.dp))
        Text(connLine, color = fg.copy(alpha = 0.7f), fontSize = 12.sp, fontFamily = Montserrat)
        if (progress.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(progress, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (downloading) return@Button
                    val conn = com.frit.app.core.llm.ModelManager.connectionType(context)
                    if (conn == "mobile") {
                        pendingApproxMB = try {
                            com.frit.app.core.llm.ModelManager.pickSpec(context).approxMB
                        } catch (_: Exception) { 1100L }
                        showMeteredDialog = true
                    } else startDownload()
                },
                enabled = !busy && !downloading, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = fg)
            ) { Text(if (downloading) "Downloading…" else "Download / Load", color = bg, fontWeight = FontWeight.Bold) }
            if (downloading) {
                OutlinedButton(
                    onClick = { stopDownload() },
                    modifier = Modifier.weight(1f)
                ) { Text("Stop", color = fg) }
            }
            OutlinedButton(
                onClick = {
                    com.frit.app.core.llm.LocalLLMEngine.unloadModel()
                    progress = "Unloaded from memory (file kept)."
                    refresh()
                },
                enabled = !busy && !downloading, modifier = Modifier.weight(1f)
            ) { Text("Unload", color = fg) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (partialInfo != null && !downloading) {
                Button(
                    onClick = { resumeDownload() },
                    enabled = !busy, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = fg)
                ) { Text("Resume download", color = bg, fontWeight = FontWeight.Bold) }
            }
            if (hasCached || partialInfo != null) {
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    enabled = !busy && !downloading, modifier = Modifier.weight(1f)
                ) { Text("Delete files", color = fg) }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { ggufPicker.launch("*/*") },
            enabled = !busy, modifier = Modifier.fillMaxWidth()
        ) { Text("Import GGUF file I already have", color = fg) }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = customUrl,
            onValueChange = { customUrl = it },
            label = { Text("Or paste a .gguf link (Hugging Face etc.)", fontFamily = Montserrat) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = fg, unfocusedTextColor = fg, focusedBorderColor = fg, unfocusedBorderColor = stroke)
        )
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = {
                val link = customUrl.trim()
                when {
                    downloading -> progress = "A download is already running."
                    com.frit.app.core.llm.ModelManager.connectionType(context) == "none" ->
                        progress = "No internet connection — cannot download now."
                    !link.startsWith("https://") -> progress = "Paste a full https:// link to a .gguf file."
                    else -> {
                        progress = "Starting download…"
                        com.frit.app.core.llm.ModelDownloadService.start(context, link)
                    }
                }
            },
            enabled = !busy && !downloading && customUrl.isNotBlank(), modifier = Modifier.fillMaxWidth()
        ) { Text("Download this GGUF and load it", color = fg) }
        if (com.frit.app.core.llm.ModelManager.activeCustomModel(context) != null) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    com.frit.app.core.llm.ModelManager.clearCustomSelection(context)
                    com.frit.app.core.llm.LocalLLMEngine.unloadModel()
                    progress = "Back to the built-in model (your imported file is kept on disk)."
                    refresh()
                },
                enabled = !busy && !downloading, modifier = Modifier.fillMaxWidth()
            ) { Text("Use built-in model instead", color = fg) }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Download once (Wi-Fi or mobile data — your choice). After that, Local Only works with zero internet. Keep this screen open or watch the notification — and on Tecno/Infinix/Xiaomi, set FRIT to \"Unrestricted\" battery use so the system doesn't kill long downloads.",
            color = fg.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = Montserrat
        )
    }
}

// Owner endpoint console — visible ONLY to mastercode redeemers (tier=owner).
// Every server endpoint as grouped buttons; results render in the card below.
// App endpoints use the app token; /admin/* use the admin token (creator has it).
@Composable
fun OwnerConsole(
    fg: Color, surf: Color, stroke: Color, bg: Color,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var unlocked by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var resultTitle by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("XAUUSD") }
    var postBody by remember { mutableStateOf("{}") }
    var catalog by remember { mutableStateOf(listOf<org.json.JSONObject>()) }

    fun call(title: String, fn: suspend () -> String) {
        scope.launch {
            busy = true
            resultTitle = title
            try {
                val raw = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fn() }
                resultText = try {
                    org.json.JSONObject(raw).toString(1).take(3000)
                } catch (_: Exception) { raw.take(3000) }
            } catch (e: Exception) { resultText = "Error: ${e.message}" }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        try {
            val s = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.frit.app.data.NetworkClient.billingStatus()
            })
            unlocked = s.optString("tier") == "owner"
        } catch (_: Exception) { unlocked = false }
    }
    // Auto endpoint catalog: every server endpoint, fetched live. New server
    // endpoints appear here with NO app update.
    LaunchedEffect(unlocked) {
        if (unlocked != true) return@LaunchedEffect
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.frit.app.data.NetworkClient.ensureInstallKey() }
            val c = org.json.JSONObject(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.frit.app.data.NetworkClient.catalogEndpoints()
            })
            val arr = c.optJSONArray("endpoints")
            val list = mutableListOf<org.json.JSONObject>()
            for (i in 0 until (arr?.length() ?: 0)) list.add(arr.getJSONObject(i))
            catalog = list
        } catch (_: Exception) { catalog = emptyList() }
    }

    fun runCatalog(e: org.json.JSONObject) {
        val method = e.optString("method", "GET")
        var p = e.optString("path", "/")
        // :params in the path are filled from the Input box above.
        Regex(":([A-Za-z0-9_]+)").findAll(p).forEach { m ->
            if (input.isNotBlank()) p = p.replace(m.value, input)
        }
        val admin = e.optBoolean("admin", false)
        val body = if (e.optBoolean("needs_body", false)) postBody.ifBlank { "{}" } else "{}"
        call("$method $p") {
            if (admin) com.frit.app.data.NetworkClient.adminRaw(method, p, body)
            else com.frit.app.data.NetworkClient.appRaw(method, p, body)
        }
    }

    fun masked(t: String): String = if (t.length <= 10) "••••" else t.take(6) + "…" + t.takeLast(4)

    @Composable
    fun Group(title: String, content: @Composable () -> Unit) {
        Spacer(Modifier.height(14.dp))
        Text(title, color = FritEspresso, fontSize = 15.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
        Spacer(Modifier.height(6.dp))
        content()
    }

    @Composable
    fun EpBtn(label: String, onClick: () -> Unit) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(FritSurface)
                .border(1.dp, FritEspresso.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .clickable(enabled = !busy, onClick = onClick).padding(12.dp)
        ) { Text(label, color = FritEspresso, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat) }
    }

    Column(Modifier.fillMaxSize().background(FritCream).padding(20.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Endpoint console", color = FritEspresso, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = FritEspresso) }
        }
        Spacer(Modifier.height(8.dp))
        when (unlocked) {
            null -> Text("Checking owner tier…", color = FritEspresso, fontSize = 14.sp)
            false -> Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FritEspresso).padding(18.dp)
            ) {
                Text(
                    "Locked — redeem your master code in Wallet → Redeem first. This console opens only for owner tier.",
                    color = FritCream, fontSize = 14.sp, fontFamily = Montserrat
                )
            }
            else -> {
                // Token card
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FritEspresso).padding(16.dp)) {
                    Column {
                        Text("Call credentials on this phone", color = FritCream.copy(alpha = 0.65f), fontSize = 12.sp, fontFamily = Montserrat)
                        Spacer(Modifier.height(4.dp))
                        TokenRow("App token", com.frit.app.core.TokenStore.get())
                        TokenRow("Admin token", com.frit.app.core.AdminStore.get())
                    }
                }

                Group("System") {
                    EpBtn("GET /health") { call("health", { com.frit.app.data.NetworkClient.sysHealth() }) }
                    Spacer(Modifier.height(6.dp))
                    EpBtn("GET /models/free-health") { call("free-health", { com.frit.app.data.NetworkClient.modelFreeHealth() }) }
                    Spacer(Modifier.height(6.dp))
                    EpBtn("GET /systems/status") { call("systems", { com.frit.app.data.NetworkClient.getSystemsStatus() }) }
                    Spacer(Modifier.height(6.dp))
                    EpBtn("GET /strategy/status") { call("strategy", { com.frit.app.data.NetworkClient.strategyStatus() }) }
                }
                Group("Market & positions") {
                    WalletField(value = input, onChange = { input = it.uppercase().filter { c -> c.isLetterOrDigit() } }, label = "Symbol (e.g. XAUUSD)", fg = FritEspresso, bg = FritCream, stroke = FritStroke)
                    Spacer(Modifier.height(6.dp))
                    EpBtn("POST /enhanced/analyze") { val s = input.ifBlank { "XAUUSD" }; call("analyze $s", { com.frit.app.data.NetworkClient.marketAnalyze(s) }) }
                    Spacer(Modifier.height(6.dp))
                    EpBtn("GET /positions") { call("positions", { com.frit.app.data.NetworkClient.positionsList() }) }
                    Spacer(Modifier.height(6.dp))
                    EpBtn("GET /tools/search (query = input)") { val q = input.ifBlank { "XAUUSD" }; call("search", { com.frit.app.data.NetworkClient.webSearch(q) }) }
                }
                Group("Creative & media") {
                    EpBtn("GET /creative/jobs") { call("creative jobs", { com.frit.app.data.NetworkClient.creativeJobs() }) }
                    Spacer(Modifier.height(6.dp))
                    EpBtn("GET /wallet/withdrawals") { call("my withdrawals", { com.frit.app.data.NetworkClient.walletWithdrawals() }) }
                }
                Group("Money (admin token)") {
                    EpBtn("GET /admin/inbox") { call("inbox", { com.frit.app.data.NetworkClient.adminInbox() }) }
                    Spacer(Modifier.height(6.dp))
                    EpBtn("GET /admin/snapshots") { call("snapshots", { com.frit.app.data.NetworkClient.adminSnapshots() }) }
                    Spacer(Modifier.height(6.dp))
                    EpBtn("GET /admin/ledger/verify") { call("ledger verify", { com.frit.app.data.NetworkClient.adminLedgerVerify() }) }
                    Spacer(Modifier.height(6.dp))
                    EpBtn("POST /admin/backup") { call("backup", { com.frit.app.data.NetworkClient.adminBackupNow() }) }
                    Spacer(Modifier.height(6.dp))
                    EpBtn("GET /feedback (trial reports)") { call("feedback", { com.frit.app.data.NetworkClient.feedbackAll() }) }
                }
                Group("All endpoints — auto-updates (${catalog.size})") {
                    Text(
                        "Every endpoint on the server, fetched live — new ones appear here with no app update. :params in a path are filled from the Symbol/Input box; POST bodies use the JSON box below (admin endpoints need the admin token in Creator console).",
                        color = FritEspresso.copy(alpha = 0.65f), fontSize = 11.sp, fontFamily = Montserrat
                    )
                    Spacer(Modifier.height(6.dp))
                    WalletField(value = postBody, onChange = { postBody = it }, label = "POST body JSON, e.g. {\"symbol\":\"XAUUSD\"}", fg = FritEspresso, bg = FritCream, stroke = FritStroke)
                    Spacer(Modifier.height(6.dp))
                    if (catalog.isEmpty()) {
                        Text("Catalog unavailable offline — curated buttons above still work.", color = FritEspresso.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = Montserrat)
                    } else {
                        var lastGroup = ""
                        catalog.forEach { e ->
                            val g = e.optString("group", "Other")
                            if (g != lastGroup) {
                                if (lastGroup.isNotEmpty()) Spacer(Modifier.height(6.dp))
                                Text(g, color = FritEspresso, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
                                Spacer(Modifier.height(4.dp))
                                lastGroup = g
                            }
                            val tag = if (e.optBoolean("admin", false)) " · admin" else ""
                            EpBtn("${e.optString("method", "GET")} ${e.optString("path", "")}$tag") { runCatalog(e) }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
                if (resultTitle.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text(resultTitle, color = FritEspresso, fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(FritEspresso).padding(12.dp)
                    ) {
                        Text(
                            resultText.ifBlank { "(empty)" },
                            color = FritCream, fontSize = 11.sp, fontFamily = Montserrat
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun TokenRow(label: String, token: String) {
    val clipboard = LocalClipboardManager.current
    val shown = if (token.length <= 10) "••••" else token.take(6) + "…" + token.takeLast(4)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = FritCream.copy(alpha = 0.6f), fontSize = 11.sp)
            Text(if (token.isBlank()) "not set" else shown, color = FritCream, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = { if (token.isNotBlank()) clipboard.setText(AnnotatedString(token)) },
            border = BorderStroke(1.dp, FritCream.copy(alpha = 0.5f))
        ) { Text("Copy", color = FritCream, fontSize = 12.sp) }
    }
}

@Composable
fun UserProfileSection(fg: Color, surf: Color, stroke: Color, bg: Color) {    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(com.frit.app.core.UserProfile.load()) }
    // Refresh every time the section opens (e.g. after auto-learn updated it mid-chat).
    LaunchedEffect(expanded) { if (expanded) profile = com.frit.app.core.UserProfile.load() }
    val callAs = profile.preferredName.ifBlank { profile.displayName }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Personal Details", color = fg, fontSize = 16.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
                Text(
                    if (callAs.isNotBlank()) "Saved for $callAs — tap to edit"
                    else "Tell FRIT about you — tap to fill",
                    color = fg.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = Montserrat
                )
            }
            Text(if (expanded) "▲" else "▼", color = fg, fontSize = 14.sp)
        }
        if (!expanded) return@Column
        Spacer(Modifier.height(8.dp))
        Text(
            "Used to personalise replies and AutoMode. Updates automatically when you say 'remember...' or 'my name is...'. Stored on this phone only.",
            color = fg.copy(alpha = 0.7f), fontSize = 12.sp, fontFamily = Montserrat
        )
        Spacer(Modifier.height(12.dp))
        ProfileField(fg = fg, stroke = stroke, label = "Full name", value = profile.displayName) { profile = profile.copy(displayName = it) }
        ProfileField(fg = fg, stroke = stroke, label = "What should FRIT call you?", value = profile.preferredName) { profile = profile.copy(preferredName = it) }
        ProfileField(fg = fg, stroke = stroke, label = "Language (e.g. English)", value = profile.language) { profile = profile.copy(language = it) }
        ProfileField(fg = fg, stroke = stroke, label = "City", value = profile.city) { profile = profile.copy(city = it) }
        ProfileField(fg = fg, stroke = stroke, label = "Country", value = profile.country) { profile = profile.copy(country = it) }
        ProfileField(fg = fg, stroke = stroke, label = "Birthday", value = profile.birthday) { profile = profile.copy(birthday = it) }
        ProfileField(fg = fg, stroke = stroke, label = "Occupation", value = profile.occupation) { profile = profile.copy(occupation = it) }
        ProfileField(fg = fg, stroke = stroke, label = "Work schedule (e.g. 9am-5pm Mon-Fri)", value = profile.workSchedule) { profile = profile.copy(workSchedule = it) }
        ProfileField(fg = fg, stroke = stroke, label = "Interests (comma separated)", value = profile.interests) { profile = profile.copy(interests = it) }
        ProfileField(fg = fg, stroke = stroke, label = "Preferred tone (Friendly / Formal / Casual)", value = profile.tone) { profile = profile.copy(tone = it) }
        ProfileField(fg = fg, stroke = stroke, label = "Reply length (Short / Detailed)", value = profile.verbosity) { profile = profile.copy(verbosity = it) }
        ProfileField(fg = fg, stroke = stroke, label = "Trading risk (Conservative / Moderate / Aggressive)", value = profile.tradingRisk) { profile = profile.copy(tradingRisk = it) }
        ProfileField(fg = fg, stroke = stroke, label = "Emergency contact (name + number)", value = profile.emergencyContact) { profile = profile.copy(emergencyContact = it) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    com.frit.app.core.UserProfile.save(profile)
                    com.frit.app.core.UserProfile.setOnboarded(true)
                    Toast.makeText(context, "Personal details saved", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = fg)
            ) { Text("Save", color = bg, fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = {
                    profile = com.frit.app.core.UserProfile.load()
                    Toast.makeText(context, "Changes discarded", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Reset", color = fg) }
        }
    }
}

@Composable
fun OnboardingDialog(fg: Color, surf: Color, stroke: Color, bg: Color, onDone: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var occupation by remember { mutableStateOf("") }
    var interests by remember { mutableStateOf("") }
    var tone by remember { mutableStateOf("") }
    Dialog(onDismissRequest = {}) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(surf)
                .border(1.dp, stroke, RoundedCornerShape(16.dp)).padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Welcome to FRIT 👋", color = fg, fontSize = 20.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
            Spacer(Modifier.height(8.dp))
            Text(
                "Tell FRIT a little about you so it can personalise replies and act like you in AutoMode. You can change everything later in Settings → Personal Details.",
                color = fg.copy(alpha = 0.8f), fontSize = 13.sp, fontFamily = Montserrat
            )
            Spacer(Modifier.height(12.dp))
            ProfileField(fg = fg, stroke = stroke, label = "Your name", value = name) { name = it }
            ProfileField(fg = fg, stroke = stroke, label = "Language (e.g. English)", value = language) { language = it }
            ProfileField(fg = fg, stroke = stroke, label = "City", value = city) { city = it }
            ProfileField(fg = fg, stroke = stroke, label = "Occupation", value = occupation) { occupation = it }
            ProfileField(fg = fg, stroke = stroke, label = "Interests", value = interests) { interests = it }
            ProfileField(fg = fg, stroke = stroke, label = "Preferred tone (Friendly / Formal / Casual)", value = tone) { tone = it }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    com.frit.app.core.UserProfile.save(
                        com.frit.app.core.UserProfile.load().copy(
                            displayName = name.trim(),
                            preferredName = name.trim(),
                            language = language.trim(),
                            city = city.trim(),
                            occupation = occupation.trim(),
                            interests = interests.trim(),
                            tone = tone.trim()
                        )
                    )
                    com.frit.app.core.UserProfile.setOnboarded(true)
                    Toast.makeText(context, "Thanks — FRIT will remember this", Toast.LENGTH_SHORT).show()
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = fg)
            ) { Text("Save & start", color = bg, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                com.frit.app.core.UserProfile.setOnboarded(true)
                onDone()
            }, modifier = Modifier.fillMaxWidth()) { Text("Skip for now", color = fg) }
        }
    }
}

@Composable
fun ProfileField(fg: Color, stroke: Color, label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, color = fg.copy(alpha = 0.6f), fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = fg, unfocusedTextColor = fg, focusedBorderColor = fg, unfocusedBorderColor = stroke)
    )
}

@Composable
fun SettingItem(label: String, fg: Color, stroke: Color, content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 12.dp)) {
        Text(label, color = fg.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
        Spacer(Modifier.height(8.dp))
        content()
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = stroke)
    }
}

@Composable
fun SettingToggle(label: String, value: Boolean, onValueChange: (Boolean) -> Unit, fg: Color, stroke: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = fg, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onValueChange)
    }
    HorizontalDivider(color = stroke)
}

// Collapsible dropdown section (e.g. Accessibilities) — tap header to reveal.
@Composable
fun ExpandableSection(
    title: String,
    subtitle: String,
    fg: Color,
    stroke: Color,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
                Text(subtitle, color = fg.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = Montserrat)
            }
            Text(if (expanded) "▲" else "▼", color = fg, fontSize = 14.sp)
        }
        if (expanded) {
            content()
        }
    }
    HorizontalDivider(color = stroke)
}

@Composable
private fun AttachmentPreviewStrip(
    imageB64: String?,
    fileName: String?,
    fg: Color,
    surf: Color,
    stroke: Color,
    onRemoveImage: () -> Unit,
    onRemoveFile: () -> Unit
) {
    if (imageB64 == null && fileName == null) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        imageB64?.let { b64 ->
            val bmp = remember(b64) {
                try {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (_: Exception) { null }
            }
            Box(
                modifier = Modifier.size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(surf)
                    .border(0.5.dp, stroke, RoundedCornerShape(10.dp))
            ) {
                bmp?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Attached image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp)
                        .clip(CircleShape).background(fg.copy(alpha = 0.75f))
                        .clickable(onClick = onRemoveImage),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, "Remove image", tint = surf, modifier = Modifier.size(11.dp))
                }
            }
        }
        fileName?.let { name ->
            Row(
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(surf)
                    .border(0.5.dp, stroke, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.AttachFile, "Attached file", tint = fg.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(name.take(24), color = fg, fontSize = 12.sp, fontFamily = Montserrat, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier.size(18.dp).clip(CircleShape)
                        .background(fg.copy(alpha = 0.12f)).clickable(onClick = onRemoveFile),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, "Remove file", tint = fg, modifier = Modifier.size(11.dp))
                }
            }
        }
    }
}

@Composable
fun ChatContent(
    bg: Color, fg: Color, surf: Color, stroke: Color, isDark: Boolean, fritMode: FritMode,
    messages: List<ChatMessage>, input: String, onInputChange: (String) -> Unit,
    replyQuotes: androidx.compose.runtime.snapshots.SnapshotStateList<com.frit.app.data.ReplyQuote> = remember { androidx.compose.runtime.mutableStateListOf() },
    onSend: (String) -> Unit, onVoiceTap: () -> Unit, onVoiceLongPress: () -> Unit,
    isVisionActive: Boolean = false, onVisionTap: () -> Unit, onVisionLongPress: () -> Unit,
    onAttachmentTap: () -> Unit, onAttachmentLongPress: () -> Unit = {},
    onSettingsTap: () -> Unit, onAutomationTap: () -> Unit,
    onChatsTap: () -> Unit, onNewChatTap: () -> Unit,
    onEdit: (Int, String) -> Unit, onDelete: (Int) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState, thinkSteps: List<String>,
    isQuickRecording: Boolean = false,
    isFileAttached: Boolean = false,
    isGenerating: Boolean = false,
    onStop: () -> Unit = {},
    onThumbsUp: ((String) -> Unit)? = null,
    onThumbsDown: ((String) -> Unit)? = null,
    brainMode: com.frit.app.core.RouteController.Brain = com.frit.app.core.RouteController.Brain.AUTO,
    onBrainModeChange: (com.frit.app.core.RouteController.Brain) -> Unit = {},
    pendingImageB64: String? = null,
    pendingFileName: String? = null,
    streamingText: String = "",
    onRemoveImage: () -> Unit = {},
    onRemoveFile: () -> Unit = {},
    onOpenCreativeSuite: (() -> Unit)? = null
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            com.frit.app.ui.FritTopBar(
                onLogoTap = onChatsTap,
                onAutomationTap = onAutomationTap, onSettingsTap = onSettingsTap,
                fg = fg, bg = bg, brainMode = brainMode, onBrainModeChange = onBrainModeChange, isDark = isDark
            )
            Box(Modifier.weight(1f)) {
                val context = LocalContext.current
                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                LaunchedEffect(messages.lastOrNull()?.content) {
                    if (messages.isNotEmpty()) {
                        listState.animateScrollToItem(messages.size - 1)
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    itemsIndexed(messages) { idx, msg ->
                        com.frit.app.ui.FritChatBubble(
                            msg = msg, isDark = isDark, fg = fg, bg = bg, surf = surf, stroke = stroke,
                            onCopy = { text ->
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(text))
                                android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            onThumbsUp = onThumbsUp,
                            onThumbsDown = onThumbsDown,
                            onEdit = if (msg.role == "user") { { txt -> onEdit(idx, txt) } } else null,
                            onDelete = if (msg.role == "user") { { onDelete(idx) } } else null,
                            onReply = { m ->
                                val t = m.content.take(200)
                                if (replyQuotes.size < 3 && replyQuotes.none { it.text == t }) {
                                    replyQuotes.add(com.frit.app.data.ReplyQuote(m.role, t))
                                }
                            },
                            onOpenCreativeSuite = onOpenCreativeSuite
                        )
                    }
                    // Live streaming bubble (audit: streaming AI chat). While the
                    // model is producing text, a provisional assistant bubble
                    // grows in place so the user sees the reply as it forms
                    // instead of staring at a spinner.
                    if (streamingText.isNotBlank()) {
                        item(key = "streaming") {
                            com.frit.app.ui.FritChatBubble(
                                msg = com.frit.app.data.ChatMessage("assistant", streamingText, isStreaming = true),
                                isDark = isDark, fg = fg, bg = bg, surf = surf, stroke = stroke,
                                onCopy = null, onThumbsUp = null, onThumbsDown = null,
                                onEdit = null, onDelete = null, onReply = null,
                                onOpenCreativeSuite = onOpenCreativeSuite
                            )
                        }
                    }
                    if (fritMode == FritMode.THINKING) {
                        item { com.frit.app.ui.FritThinkingBar(thinkSteps, fg) }
                    }
                }
                if (isQuickRecording || fritMode == FritMode.LISTENING) {
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
                        RecordingOverlay(fg, bg, fritMode == FritMode.LISTENING)
                    }
                }

            }
            com.frit.app.ui.ReplyQuoteStrip(
                quotes = replyQuotes, fg = fg, surf = surf, stroke = stroke,
                onRemove = { replyQuotes.removeAt(it) }
            )
            AttachmentPreviewStrip(
                imageB64 = pendingImageB64, fileName = pendingFileName,
                fg = fg, surf = surf, stroke = stroke,
                onRemoveImage = onRemoveImage, onRemoveFile = onRemoveFile
            )
            com.frit.app.ui.FritInputBar(
                input = input, onInputChange = onInputChange, onSend = { onSend(input) },
                onVoiceTap = onVoiceTap, onVoiceLongPress = onVoiceLongPress, 
                onAttachImage = onAttachmentTap, onAttachLongPress = onAttachmentLongPress,
                fg = fg, bg = bg, surf = surf, stroke = stroke, isListening = isQuickRecording || fritMode == FritMode.LISTENING, isFileAttached = isFileAttached,
                isGenerating = isGenerating || fritMode == FritMode.THINKING || fritMode == FritMode.SPEAKING, onStop = onStop
            )
        }
    }
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

/**
 * Downscale a bitmap for brain upload: full-resolution camera shots and
 * screenshots are several MB as base64 and make vision calls slow or fail.
 * Keeps the aspect ratio, caps the long edge, re-encodes as JPEG.
 */
private fun downscaleForUpload(
    src: android.graphics.Bitmap,
    quality: Int = 78,
    maxDim: Int = 1568
): ByteArray {
    val w = src.width; val h = src.height
    if (w <= 0 || h <= 0) return ByteArray(0)
    val scale = (maxDim / maxOf(w, h).toFloat()).coerceAtMost(1f)
    val bmp = if (scale < 1f) {
        android.graphics.Bitmap.createScaledBitmap(src, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
    } else src
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
    if (bmp !== src) bmp.recycle()
    return out.toByteArray()
}

/**
 * Persist an edited image (data: URL or http URL) to Downloads via the
 * shared artifact saver. Returns the location label, "" on failure.
 */
private suspend fun saveEditedImageToDownloads(ctx: android.content.Context, url: String): String =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            if (url.isBlank()) return@withContext ""
            val bytes = if (url.startsWith("data:")) {
                val b64 = url.substringAfter(",", "")
                if (b64.isBlank()) return@withContext ""
                android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            } else if (url.startsWith("http")) {
                java.net.URL(url).openStream().use { it.readBytes() }
            } else return@withContext ""
            if (bytes.isEmpty()) return@withContext ""
            val art = com.frit.app.data.Artifact(
                name = "frit-edit-${System.currentTimeMillis()}.png",
                mime = "image/png",
                size = bytes.size.toLong(),
                base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            )
            com.frit.app.ui.saveArtifactToDownloads(art, ctx)
        } catch (e: Exception) { com.frit.app.core.Diag.warn("MainActivity", "saveEditedImageToDownloads", e); "" }
    }

/**
 * Share an exported creative asset. Remote (http) assets go out through the
 * system share sheet; in-app data: URLs can't leave as text, so confirm
 * with a toast instead of firing a multi-MB intent that would fail.
 * Safe to call from any thread.
 */
private fun shareExportAsset(ctx: android.content.Context, url: String) {
    android.os.Handler(ctx.mainLooper).post {
        try {
            if (url.startsWith("http")) {
                val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, url)
                }
                ctx.startActivity(android.content.Intent.createChooser(share, "Share export"))
            } else if (url.startsWith("data:")) {
                android.widget.Toast.makeText(ctx, "Export ready — use Save for a file copy", android.widget.Toast.LENGTH_LONG).show()
            } else {
                android.widget.Toast.makeText(ctx, "Nothing to share yet", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(ctx, "Share failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun TopoBackground(isDark: Boolean, modifier: Modifier = Modifier.fillMaxSize()) {
    val bgRes = if (isDark) R.drawable.chat_background_white else R.drawable.chat_background_dark
    Image(
        painter = painterResource(id = bgRes),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .alpha(if (isDark) 0.12f else 0.18f)
    )
}
