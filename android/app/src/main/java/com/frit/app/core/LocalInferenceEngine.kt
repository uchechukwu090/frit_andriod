package com.frit.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import com.frit.app.core.llm.LocalLLMEngine
import com.frit.app.data.ChatMessage
import com.frit.app.features.market.FritAccessibilityService
import com.frit.app.features.vision.ScreenCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * LocalInferenceEngine — on-device hybrid "AI" for 3GB RAM phones.
 *
 * Tier 1: LocalLLMEngine (Gemma 3 1B IT via llama.cpp) — full LLM, offline capable
 * Tier 2: Pattern matching — fast, zero-RAM for simple actions
 * Tier 3: Mistral cloud — complex/analytical tasks, reached by auto-toggling
 *         data on when the local brain can't comprehend (NetworkSwitch)
 *
 * Battery-friendly: LLM unloads after 10min idle.
 */
object LocalInferenceEngine {
    private const val TAG = "LocalInference"
    private var appContext: Context? = null
    private var tts: TextToSpeech? = null

    /** Public accessor so other components (DeviceStateCollector, etc.) can reuse the context. */
    val appContextSafe: Context? get() = appContext

    // Fix 7 — bounded multi-step local agentic loop.
    private const val MAX_LOCAL_AGENT_ITERS = 4

    private val AGENTIC_LOCAL_SYSTEM_PROMPT = """
        You are FRIT, an expert autonomous Android AI assistant. You are NOT ChatGPT or Gemini. You were built by the FRIT team.
        You perform MULTI-STEP tasks one step at a time. Each step you may
        either (a) call a tool by replying with ONLY a JSON object, or (b) give
        the final answer in plain text. Always introduce yourself as FRIT if asked.
        Supported tools:
        - {"tool":"open_app","target":"app name"}
        - {"tool":"call","target":"number"}
        - {"tool":"sms","target":"number"}
        - {"tool":"whatsapp","target":"number"}
        - {"tool":"set_alarm","target":"7:00 AM"}
        - {"tool":"set_timer","target":"5 minutes"}
        - {"tool":"navigate","target":"destination"}
        - {"tool":"play_music","target":"query"}
        - {"tool":"read_screen","target":""}
        - {"tool":"screenshot","target":""}
        - {"tool":"tap_button","target":"button label text"}
        - {"tool":"tap_coordinates","target":"x,y"}
        - {"tool":"type_text","target":"text to type"}
        - {"tool":"scroll","target":"up|down"}
        - {"tool":"go_back","target":""}
        - {"tool":"press_home","target":""}
        Rules:
        - NO TRADING TOOLS EXIST HERE. You cannot fetch prices, analyse a market,
          or place/modify/close an MT5 order from this on-device brain — those
          tools are deliberately not available. If the user asks about a market
          or a trade, say plainly that you cannot get live market data or place
          orders from the local engine and that it needs the cloud brain (or the
          Trading screen). NEVER guess a price, direction, entry, stop or target,
          and never claim a trade was placed.
        - For a real task, always start with the FIRST action (e.g. open the app).
        - After each tool call you will receive the tool result and the current
          screen text. Use that to decide the next action until the task is done.
        - When the task is complete, reply with a final plain-text summary.
        - If a tool failed, try a different approach. Do not repeat the same call.
    """.trimIndent()

    fun init(context: Context) {
        appContext = context.applicationContext
        LocalLLMEngine.init(context)
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "local_tts")
    }

    /**
     * Main entry point: classify intent and route to appropriate engine.
     *
     * @param forceLocal when true (brain locked to LOCAL), never fall through to
     *                   the cloud even if online — fixes mid-chat local takeover.
     */
    suspend fun process(text: String, history: List<ChatMessage> = emptyList(), forceLocal: Boolean = false): String = withContext(Dispatchers.IO) {
        val t = text.lowercase().trim()

        // ===== GREETINGS / CASUAL CHAT — never a tool call =====
        if (isCasualConversation(t)) {
            return@withContext casualReply(t)
        }

        // ===== DIRECT LOCAL ACTIONS (fast path, no LLM needed) =====
        val localResult = classify(text)
        // Device actions only. Market/trading intents are deliberately absent:
        // the engine is reached through the AI agent loop, never by keyword.
        val localOnly = setOf(
            "open_app", "call", "sms", "whatsapp", "set_alarm", "set_timer",
            "navigate", "play_music", "open_camera", "open_settings_wifi",
            "open_settings_bluetooth", "open_settings_data", "open_app_settings",
            "adjust_volume", "copy_to_clipboard", "help", "stop_all_tasks", "screenshot"
        )
        if (localResult.intent in localOnly && localResult.confidence >= 0.85f) {
            return@withContext executeLocal(localResult.intent, localResult.target, localResult.converse, text) ?: "Could not execute action"
        }

        // ===== OFFLINE-ONLY LOCAL LLM (Gemma 3 1B) =====
        if (!isOnline() || forceLocal) {
            if (LocalLLMEngine.isModelLoaded()) {
                val llmResponse = runLocalAgenticLoop(text, history)
                if (llmResponse != null) return@withContext llmResponse

            } else if (LocalLLMEngine.isNativeAvailable()) {
                // Offline: try to load model — cached file only, never a download.
                LocalLLMEngine.loadModel(appContext ?: return@withContext "App not initialized", allowDownload = false)
                val llmResponse = runLocalAgenticLoop(text, history)
                if (llmResponse != null) return@withContext llmResponse
            }
        }

        // ===== PATTERN MATCHING FALLBACK =====
        if (localResult.intent != "cloud_chat") {
            val result = executeLocal(localResult.intent, localResult.target, localResult.converse, text)
            if (result != null) return@withContext result
        }

        // ===== ONLINE CLOUD ROUTING =====
        // Prefer local when forced/offline, but NEVER strand the user: if local
        // can't actually serve (model not loaded, native engine missing), fall
        // back to the cloud so they always get an answer.
        val hasNativeEngine = LocalLLMEngine.isNativeAvailable()
        val canUseLocal = hasNativeEngine && LocalLLMEngine.isModelLoaded()

        if (canUseLocal) {
            val llmResponse = runLocalAgenticLoop(text, history)
            if (llmResponse != null) return@withContext llmResponse
        }

        // Try to (re)load the local model as the next local option.
        if (hasNativeEngine && !LocalLLMEngine.isModelLoaded()) {
            LocalLLMEngine.loadModel(appContext ?: return@withContext "App not initialized", allowDownload = false)
            val loaded = LocalLLMEngine.isModelLoaded()
            if (loaded) {
                val llmResponse = runLocalAgenticLoop(text, history)
                if (llmResponse != null) return@withContext llmResponse
            }
        }

        // Local exhausted -> cloud fallback (works whether or not forceLocal).
        // Auto-toggle data (NetworkSwitch) so the cloud is reachable even when
        // the device reported offline; restore radio state afterwards.
        // NEVER auto-toggle radios while the user forced LOCAL — their intent is
        // explicitly on-device only, so respect it.
        if (!forceLocal && !isOnline()) NetworkSwitch.ensureBestEffortConnectivity()
        if (isOnline()) {
            try {
                return@withContext com.frit.app.core.IntegrationCoordinator.chat(text, history)
            } catch (e: Exception) {
                Log.e(TAG, "Cloud chat error", e)
            } finally {
                if (!forceLocal) NetworkSwitch.restore()
            }
        }

        val online = isOnline()
        return@withContext when {
            forceLocal && LocalLLMEngine.isNativeAvailable() && !LocalLLMEngine.isModelLoaded() ->
                "Local Only is set, but the on-device Qwen3 model couldn't be loaded. Open Settings → On-Device Model to download or preload it (needs internet once), then it runs fully offline."
            forceLocal ->
                "Local Only is set and the 3rd-party on-device AI engine isn't available in this build. Switch the Router to Auto or Cloud Only, or install a build with the native engine."
            !online && !hasNativeEngine ->
                "I can't reach the internet, and this build has no on-device AI engine compiled in. Reconnect to use the cloud brain."
            !online ->
                "I can't reach the internet and the on-device model isn't loaded. Download Qwen3 once (Settings → On-Device Model), then it runs completely offline."
            else ->
                "The cloud brain didn't reply. Check your API token in Settings and your connection, then try again."
        }
    }

    /**
     * Fix 7 — bounded multi-step local reasoning. Instead of one one-shot answer,
     * the local LLM can emit up to MAX_LOCAL_AGENT_ITERS JSON tool calls, each
     * followed by the tool result + fresh screen text, until it produces a plain
     * text final answer. This gives the small model a real (if simple) agent loop.
     */
    private suspend fun runLocalAgenticLoop(userText: String, history: List<ChatMessage>): String? = withContext(Dispatchers.IO) {
        // The 1.7B local model is the HANDS: cloud directs, local executes.
        // So the FIRST turn must already see the screen — otherwise it acts blind.
        val initialScreen = try {
            FritAccessibilityService.getScreenText().take(1500)
        } catch (e: Exception) { "" }
        val working = history.takeLast(12).toMutableList().apply {
            add(ChatMessage("user", userText + if (initialScreen.isNotBlank()) "\nCURRENT SCREEN: $initialScreen" else ""))
        }
        var iter = 0
        var lastText: String? = null

        while (iter < MAX_LOCAL_AGENT_ITERS) {
            val llmResponse = LocalLLMEngine.chat(working, AGENTIC_LOCAL_SYSTEM_PROMPT) ?: return@withContext lastText
            lastText = llmResponse

            val json = parseToolJson(llmResponse)
            val intent = json?.optString("tool").orEmpty()
            if (intent.isBlank() || intent == "reply" || intent == "chat") {
                return@withContext llmResponse
            }

            val target = json?.optString("target", "").orEmpty()
            // F-18a: the model chose this tool, so it does NOT get the user's
            // utterance as authority. Passing the original userText lets
            // CallGuard check whether the user actually named the target;
            // otherwise the call opens the dialer instead of being placed.
            val result = executeLocal(intent, target, userUtterance = userText) ?: "No local handler for $intent"
            val screen = try {
                val s = FritAccessibilityService.getScreenText().take(1500)
                if (s.isNotBlank()) s else "(no readable screen text — screen may be custom-drawn or the target app isn't in the foreground; open the app first or take a screenshot)"
            } catch (e: Exception) { "" }
            working.add(ChatMessage("user", "TOOL RESULT for $intent: $result\nCURRENT SCREEN: $screen"))
            iter++
        }
        return@withContext lastText ?: "I completed as much of that as I can on-device."
    }

    /**
     * LOCAL-ONLY automation attempt (Gemma 3): fast path + one bounded agentic
     * loop. Returns null when the local brain clearly cannot comprehend the
     * task, so callers (AutomationEngine) can escalate to the cloud via
     * NetworkSwitch instead of surfacing a dead-end "I can't" answer.
     */
    suspend fun processLocalOnly(text: String, history: List<ChatMessage> = emptyList()): String? = withContext(Dispatchers.IO) {
        val localResult = classify(text)
        val localOnly = setOf(
            "open_app", "call", "sms", "whatsapp", "set_alarm", "set_timer",
            "navigate", "play_music", "open_camera", "open_settings_wifi",
            "open_settings_bluetooth", "open_settings_data", "open_app_settings",
            "adjust_volume", "copy_to_clipboard", "help", "stop_all_tasks", "screenshot"
        )
        if (localResult.intent in localOnly && localResult.confidence >= 0.85f) {
            return@withContext executeLocal(localResult.intent, localResult.target, localResult.converse, text) ?: "Could not execute action"
        }

        if (LocalLLMEngine.isModelLoaded()) {
            val r = runLocalAgenticLoop(text, history)
            if (r != null && !comprehensionFailed(r)) return@withContext r
        } else if (LocalLLMEngine.isNativeAvailable()) {
            LocalLLMEngine.loadModel(appContext ?: return@withContext null, allowDownload = false)
            if (LocalLLMEngine.isModelLoaded()) {
                val r = runLocalAgenticLoop(text, history)
                if (r != null && !comprehensionFailed(r)) return@withContext r
            }
        }
        return@withContext null
    }

    /** Heuristic: did the small local model admit it can't do the task? */
    private fun comprehensionFailed(answer: String): Boolean {
        val a = answer.lowercase()
        return a.isBlank() ||
            a.startsWith("error:") ||
            a.startsWith("no local handler") ||
            a.contains("i can't") ||
            a.contains("i cannot") ||
            a.contains("can't do") ||
            a.contains("cannot do") ||
            a.contains("not able to") ||
            a.contains("i don't know") ||
            a.contains("i don't understand") ||
            a.contains("i do not know") ||
            a.contains("i do not understand") ||
            a.contains("not supported")
    }

    /** Extract the first JSON object from a model reply, tolerating extra text. */
    private fun parseToolJson(raw: String): JSONObject? {
        val text = raw.trim()
        if (text.startsWith("{")) {
            try { return JSONObject(text) } catch (_: Exception) {}
        }
        val match = Regex("""\{[^{}]*\}""").find(text)
        if (match != null) {
            try { return JSONObject(match.value) } catch (_: Exception) {}
        }
        return null
    }

    /** True for greetings/small talk that should just get a chat reply. */
    private fun isCasualConversation(text: String): Boolean {
        val t = text.lowercase().trim()
        if (t.isBlank() || t.length > 60) return false
        val greetings = listOf(
            "hi", "hello", "hey", "yo", "good morning", "good afternoon", "good evening",
            "good night", "how are you", "how's it going", "how is it going", "what's up",
            "whats up", "how do you do", "thank", "thanks", "thank you", "bye", "goodbye",
            "ok", "okay", "cool", "nice", "great", "lol", "haha", "hmm", "yes", "no", "am fine", "i'm fine"
        )
        return greetings.any { t.startsWith(it) } || t in greetings
    }

    private fun casualReply(text: String): String {
        val t = text.lowercase().trim()
        return when {
            t.startsWith("how are") || t.startsWith("how's") || t.startsWith("how is") ->
                "I'm doing great, ready to help! What would you like me to do?"
            t.startsWith("thank") || t.startsWith("thanks") ->
                "You're welcome! Anything else I can do for you?"
            t.startsWith("bye") || t.startsWith("goodbye") ->
                "Goodbye! I'm here whenever you need me."
            t.isBlank() ||
            t in listOf("hi", "hello", "hey", "yo", "hi there", "hello there", "what's up", "whats up") ||
            (t.length <= 5)-> "Hey! How can I help you today?"
            else -> "Hey! What would you like me to do?"
        }
    }

    fun classify(text: String): ClassificationResult {        val t = text.lowercase().trim()

        // ===== NO TRADING KEYWORD SHORTCUT =====
        // There is deliberately NO market/MT5 keyword branch here. Words like
        // "open mt5", "mt5", "buy", "trade", "gold" or a bare symbol used to
        // return an intent that called the trading engine directly — bypassing
        // the AI, the SensitiveGate and user confirmation, and (for
        // place_mt5_trade) tapping real MT5 order buttons straight from a chat
        // string. Market work now reaches the engine ONLY through the agent
        // loop: the brain calls analyze_market / get_market_data as tools, and
        // anything that spends money still passes SensitiveGate + the Safety
        // Shield confirmation. Questions below are handled as chat.

        // Questions are chat, not actions. "whats camera", "what is an alarm?",
        // "how does bluetooth work?" ask ABOUT things — they must never fire
        // the hardware keyword matchers below. Checked first, before "open",
        // "call", "camera", etc. ("whatsapp ..." is unaffected: "whatsapp" has
        // no word boundary after "whats", so it still routes to WhatsApp.)
        if (isQuestion(t)) {
            return ClassificationResult("cloud_chat", 0.50f, text)
        }

        if (t.contains("open") || t.contains("launch") || t.startsWith("start ")) {
            return ClassificationResult("open_app", 0.95f, extractTarget(t))
        }
        if (t.contains("call") || t.contains("dial") || t.contains("ring")) {
            // "Call X and talk to him / have a conversation" -> dial + hold a live
            // half-duplex voice conversation after the other side answers.
            val converse = listOf("talk", "speak", "conversation", "chat", "ask ", "discuss", "talk to")
                .any { t.contains(it) }
            return ClassificationResult("call", 0.95f, extractTarget(t), converse)
        }
        if (t.contains("text") || t.contains("sms") || t.contains("message")) {
            return ClassificationResult("sms", 0.90f, extractTarget(t))
        }
        if (t.contains("whatsapp") || t.contains("whats app")) {
            return ClassificationResult("whatsapp", 0.90f, extractTarget(t))
        }
        if (t.contains("alarm") || t.contains("wake me")) {
            return ClassificationResult("set_alarm", 0.95f, extractTimeTarget(t))
        }
        if (t.contains("timer") || t.contains("remind me")) {
            return ClassificationResult("set_timer", 0.95f, extractTimeTarget(t))
        }
        if (t.contains("navigate") || t.contains("direction") || t.contains("take me to")) {
            return ClassificationResult("navigate", 0.90f, extractTarget(t))
        }
        if (t.contains("play") || t.contains("music") || t.contains("song")) {
            return ClassificationResult("play_music", 0.85f, extractTarget(t))
        }
        if (t.contains("camera") || t.contains("photo") || t.contains("picture")) {
            return ClassificationResult("open_camera", 0.95f, "")
        }
        if (t.contains("screenshot")) {
            return ClassificationResult("screenshot", 0.80f, "")
        }
        if (t.contains("data") || t.contains("cellular") || t.contains("mobile data")) {
            return ClassificationResult("open_settings_data", 0.95f, "")
        }
        if (t.contains("app info") || t.contains("app settings")) {
            return ClassificationResult("open_app_settings", 0.90f, extractTarget(t))
        }
        if (t.contains("copy") || t.contains("clipboard")) {
            return ClassificationResult("copy_to_clipboard", 0.90f, extractTarget(t))
        }
        if (t.contains("wifi") || t.contains("wi-fi")) {
            return ClassificationResult("open_settings_wifi", 0.90f, "")
        }
        if (t.contains("bluetooth")) {
            return ClassificationResult("open_settings_bluetooth", 0.90f, "")
        }
        if (t.contains("volume") || t.contains("sound")) {
            return ClassificationResult("adjust_volume", 0.90f, t)
        }
        if (t.contains("brightness") || t.contains("light")) {
            return ClassificationResult("adjust_brightness", 0.90f, t)
        }
        if (t.contains("help") || t.contains("what can you do")) {
            return ClassificationResult("help", 1.0f, "")
        }

        // Everything else routes through the local LLM or cloud
        return ClassificationResult("cloud_chat", 0.50f, text)
    }

    /** True when the user is ASKING about something rather than commanding it. */
    private fun isQuestion(t: String): Boolean {
        if (t.endsWith("?")) return true
        val q = listOf(
            "what's ", "whats ", "what is ", "what are ", "what does ", "what do ",
            "why ", "how ", "when ", "where ", "which ", "who ", "whom ", "whose ",
            "is ", "are ", "do ", "does ", "did ", "can ", "could ", "would ",
            "should ", "will ", "have ", "has ", "explain ", "define ", "describe ",
            "tell me about ", "tell me what ", "do you know "
        )
        return q.any { t.startsWith(it) }
    }

    /**
     * Execute a device action.
     *
     * @param userUtterance the user's OWN message that led here. null means the
     *        action came from an autonomous path (the LLM's tool loop, a
     *        background task) — F-18a then requires a confirmation before a
     *        call is placed instead of placing it outright.
     */
    suspend fun executeLocal(
        intent: String,
        target: String,
        converse: Boolean = false,
        userUtterance: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext null
        try {
            when (intent) {
                "open_app" -> {
                    val pkg = resolvePackage(target)
                    if (pkg != null) {
                        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { intent ->
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(intent)
                            "Opening $target"
                        } ?: "Could not open $target"
                    } else "App '$target' not found"
                }
                "call" -> {
                    val number = target.filter { it.isDigit() || it == '+' }
                    if (number.isNotEmpty()) {
                        // F-18a: the call only goes out without a tap when the
                        // user's own utterance named this target. Otherwise the
                        // dialer opens with the number pre-filled and the user
                        // taps call.
                        val allowed = try {
                            ctx.getSharedPreferences("frit_prefs", Context.MODE_PRIVATE)
                                .getBoolean("allow_calls", true)
                        } catch (e: Exception) { true }
                        val decision = CallGuard.decide(userUtterance, target, allowed)
                        if (decision == CallGuard.Decision.DENY) {
                            "Calls are disabled on this device — enable them in Settings if you want FRIT to dial."
                        } else {
                            val confirmed = decision == CallGuard.Decision.PLACE_NOW
                            val action = if (confirmed) Intent.ACTION_CALL else Intent.ACTION_DIAL
                            ctx.startActivity(Intent(action, Uri.parse("tel:$number"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            if (!confirmed) {
                                "Opening the dialer for $target — tap call to place it"
                            } else if (converse) {
                                try {
                                    com.frit.app.features.call.CallConversationService.start(ctx, number, outgoing = true)
                                    "Calling $target — FRIT will hold a live conversation once he picks up"
                                } catch (e: Exception) {
                                    "Calling $target"
                                }
                            } else "Calling $target"
                        }
                    } else "Could not resolve contact"
                }
                "sms" -> {
                    ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$target"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opening SMS to $target"
                }
                "whatsapp" -> {
                    val uri = Uri.parse("https://wa.me/${target.filter { it.isDigit() }}")
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opening WhatsApp for $target"
                }
                "set_alarm" -> {
                    val match = Regex("(\\d{1,2}):(\\d{2})\\s*(AM|PM)?", RegexOption.IGNORE_CASE).find(target)
                    if (match != null) {
                        var hour = match.groupValues[1].toInt()
                        val min = match.groupValues[2].toInt()
                        val ampm = match.groupValues[3].uppercase()
                        if (ampm == "PM" && hour < 12) hour += 12
                        if (ampm == "AM" && hour == 12) hour = 0
                        ctx.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                            putExtra(AlarmClock.EXTRA_HOUR, hour)
                            putExtra(AlarmClock.EXTRA_MINUTES, min)
                            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                        "Alarm set for $target"
                    } else "Could not parse time: $target"
                }
                "set_timer" -> {
                    val seconds = parseDuration(target)
                    ctx.startActivity(Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        putExtra(AlarmClock.EXTRA_MESSAGE, "FRIT Timer")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    "Timer set for $target"
                }
                "navigate" -> {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("google.navigation:q=${Uri.encode(target)}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Navigating to $target"
                }
                "play_music" -> {
                    val uri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(target)}")
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Searching for $target"
                }
                "open_camera" -> {
                    ctx.startActivity(Intent("android.media.action.IMAGE_CAPTURE")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opening camera"
                }
                "open_settings_wifi" -> {
                    com.frit.app.features.automation.AutomationEngine.openSettings("wifi")
                    "Opening Wi-Fi settings"
                }
                "open_settings_bluetooth" -> {
                    com.frit.app.features.automation.AutomationEngine.openSettings("bluetooth")
                    "Opening Bluetooth settings"
                }
                "open_settings_data" -> {
                    com.frit.app.features.automation.AutomationEngine.openSettings("mobile_data")
                    "Opening Mobile Data network settings"
                }
                "open_app_settings" -> {
                    com.frit.app.features.automation.AutomationEngine.openAppSettings(target)
                    "Opening app settings for $target"
                }
                "copy_to_clipboard" -> {
                    com.frit.app.features.automation.AutomationEngine.setClipboardText(target)
                    "Copied to clipboard"
                }
                "adjust_volume" -> {
                    val num = Regex("\\d+").find(target)?.value?.toIntOrNull()
                    if (num != null) {
                        com.frit.app.features.automation.AutomationEngine.setVolume(num)
                        "Volume set to $num%"
                    } else {
                        com.frit.app.features.automation.AutomationEngine.openSettings("sound")
                        "Opening volume settings"
                    }
                }
                "adjust_brightness" -> {
                    com.frit.app.features.automation.AutomationEngine.openSettings("display")
                    "Opening display settings"
                }
                "help" -> {
                    "I can: open apps, make calls, send SMS/WhatsApp, set alarms/timers, navigate, play music, take photos, adjust settings, answer questions, analyze markets, and more."
                }
                "stop_all_tasks" -> {
                    com.frit.app.features.automation.AutomationScheduler.listActiveTasks().forEach {
                        com.frit.app.features.automation.AutomationScheduler.cancelTask(it)
                    }
                    "All recurring tasks stopped"
                }
                "screenshot" -> {
                    // Requires one-time MediaProjection consent per boot; never
                    // start the capture service bare or it crashes without it.
                    val started = try {
                        ScreenCaptureService.autoStartIfPermitted(ctx)
                    } catch (e: Exception) {
                        Log.w(TAG, "screenshot needs consent", e)
                        false
                    }
                    if (started) "Taking a screenshot for analysis"
                    else {
                        Log.w(TAG, "screenshot needs consent")
                        "Screenshot needs screen-capture permission first — enable Vision in Settings"
                    }
                }
                "read_screen" -> {
                    val text = FritAccessibilityService.getScreenText().take(1000)
                    if (text.isNotBlank()) "Visible screen text: $text" else "Screen appears empty or not readable."
                }
                // LOCAL HANDS: the 1.7B model executes taps directed by the cloud brain.
                // Cloud decides WHAT/WHERE, local performs the gesture on-device (fast,
                // no round-trip per tap). These mirror IntegrationCoordinator edge tools.
                "tap_button", "tap_element" -> {
                    val ok = FritAccessibilityService.clickButtonWithText(target)
                    if (ok) "Tapped '$target'" else "Could not find '$target' on screen"
                }
                "tap_coordinates" -> {
                    val xy = Regex("(\\d+)\\s*[,\\s]\\s*(\\d+)").find(target)
                    if (xy != null) {
                        val x = xy.groupValues[1].toIntOrNull()
                        val y = xy.groupValues[2].toIntOrNull()
                        if (x != null && y != null) {
                            FritAccessibilityService.executeStep("tap_coordinates", x = x, y = y)
                        } else "Invalid coordinates: $target (use x,y)"
                    } else "Invalid coordinates: $target (use x,y)"
                }
                "type_text" -> {
                    FritAccessibilityService.executeStep("type_text", value = target)
                }
                "scroll" -> {
                    val dir = if (target.isBlank()) "down" else target.trim().lowercase()
                    FritAccessibilityService.executeStep("scroll", label = dir)
                }
                "go_back", "press_back" -> {
                    FritAccessibilityService.executeStep("press_back")
                }
                "press_home" -> {
                    FritAccessibilityService.executeStep("press_home")
                }
                // NOTE: get_market_quote / analyze_market / place_mt5_trade are
                // intentionally NOT executable here. They used to be reachable
                // from a bare keyword match, which meant a chat line could
                // place a REAL MT5 order with no AI, no SensitiveGate and no
                // confirmation. Market tools now run only through the agent
                // loop (IntegrationCoordinator.executeEdgeTool), where
                // SensitiveGate + the Safety Shield apply.
                // Order management via local hands (cloud browses + directs).
                "modify_mt5_order" -> {
                    val parts = target.split(" ")
                    val symbol = parts.getOrNull(0)?.uppercase().orEmpty()
                    val sl = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                    val tp = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                    if (symbol.isBlank()) "Usage: SYMBOL [SL] [TP]"
                    else com.frit.app.features.market.MT5Agent.modifyPosition(symbol, sl, tp)
                }
                "close_mt5_order" -> {
                    val symbol = target.trim().uppercase()
                    if (symbol.isBlank()) "Usage: SYMBOL"
                    else com.frit.app.features.market.MT5Agent.closePosition(symbol)
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing $intent", e)
            "Error: ${e.message}"
        }
    }

    suspend fun analyzeVisionInput(base64Image: String, source: String): String = withContext(Dispatchers.IO) {
        // Local LLM image analysis is not yet supported — fall back to cloud
        return@withContext "Could not analyze this image locally. Try again with an internet connection."
    }

    private suspend fun extractTextFromImage(base64Image: String): String = withContext(Dispatchers.IO) {
        // OCR requires ML Kit dependency — not available locally
        Log.d(TAG, "extractTextFromImage: ML Kit not available, returning empty")
        ""
    }

    private fun extractTarget(text: String): String {
        val keywords = listOf(
            "open ", "launch ", "start ", "call ", "dial ", "ring ",
            "text ", "sms ", "message ", "play ", "navigate to ",
            "take me to ", "direction to ", "whatsapp "
        )
        for (kw in keywords) {
            val idx = text.lowercase().indexOf(kw)
            if (idx >= 0) {
                val after = text.substring(idx + kw.length).trim()
                if (after.isNotEmpty()) return after
            }
        }
        return text
    }

    private fun extractTimeTarget(text: String): String {
        val timeMatch = Regex("(\\d{1,2}:\\d{2}\\s*(?:AM|PM)?|\\d+\\s*(?:sec|min|hour|second|minute)s?)", RegexOption.IGNORE_CASE).find(text)
        return timeMatch?.value ?: text
    }

    private fun parseDuration(text: String): Int {
        var total = 0
        Regex("(\\d+)\\s*hour").find(text)?.let { total += it.groupValues[1].toInt() * 3600 }
        Regex("(\\d+)\\s*min").find(text)?.let { total += it.groupValues[1].toInt() * 60 }
        Regex("(\\d+)\\s*sec").find(text)?.let { total += it.groupValues[1].toInt() }
        return if (total == 0) 300 else total
    }

    private fun resolvePackage(name: String): String? {
        return com.frit.app.features.automation.AutomationEngine.resolvePackage(name)
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) { false }
    }
}

data class ClassificationResult(
    val intent: String,
    val confidence: Float,
    val target: String,
    val converse: Boolean = false
)