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
import com.frit.app.ui.chat.WaveformBars
import com.frit.app.ui.chat.RecordingOverlay
import com.frit.app.ui.chat.WakeWordTrainingDialog
import com.frit.app.ui.theme.*
import com.frit.app.ui.chat.ChatContent
import com.frit.app.ui.model.OnDeviceModelSection
import com.frit.app.ui.settings.OnboardingDialog
import com.frit.app.ui.settings.SettingsScreen
import com.frit.app.ui.automation.AutomationScreen
import com.frit.app.ui.chats.ChatsScreen
import com.frit.app.ui.wallet.WalletScreen
import com.frit.app.ui.creator.CreatorScreen
import com.frit.app.ui.creator.OwnerConsole
import com.frit.app.ui.TopoBackground
import com.frit.app.ui.downscaleForUpload
import com.frit.app.ui.normalizeAssistantReplyForUi
import com.frit.app.ui.shareExportAsset
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
