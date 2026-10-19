package com.frit.app.core

import android.content.Context
import android.util.Log
import com.frit.app.data.Artifact
import com.frit.app.data.ChatMessage
import com.frit.app.data.NetworkClient
import com.frit.app.features.automation.AutomationEngine
import com.frit.app.features.market.FritAccessibilityService
import com.frit.app.features.memory.MemoryEngine
import com.frit.app.features.vision.ScreenCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
/**
 * Intelligent Chat + Task Router.
 *
 * Uses LocalInferenceEngine to decide whether to execute locally or route to cloud.
 * Handles vision context, memory, and recursive tool calls.
 */
object IntegrationCoordinator {
    private const val TAG = "FritCoordinator"
    private const val DEV_TOOLS_ENABLED = false

    private val _thoughtSteps = MutableStateFlow<List<String>>(emptyList())
    val thoughtSteps = _thoughtSteps.asStateFlow()

    /** Artifacts (files: charts, CSVs, reports) produced by the last agent turn. */
    private val _lastArtifacts = MutableStateFlow<List<Artifact>>(emptyList())
    val lastArtifacts = _lastArtifacts.asStateFlow()

    /**
     * Live text of a streaming chat reply (audit: "streaming responses for AI
     * chat"). Empty when nothing is streaming. The UI renders this as a
     * provisional bubble that grows as the model produces text, so the user
     * sees the answer appear instead of waiting on a spinner.
     */
    private val _streamText = MutableStateFlow("")
    val streamText = _streamText.asStateFlow()

    /**
     * True when the last reply was already shown live as a stream, so the UI
     * must NOT replay the typing animation for it (that would show the same
     * text twice).
     */
    @Volatile var lastReplyWasStreamed: Boolean = false

    private var appContext: Context? = null

    // Call-loop guard: the dialer gives no programmatic "picked up" signal back
    // to us, so without this the brain re-dials thinking the task is undone.
    // Same number within 3 min => report already-placed, do NOT redial.
    @Volatile private var lastCallNumber: String = ""
    @Volatile private var lastCallAt: Long = 0L
    private const val CALL_DEDUPE_MS = 180_000L

    /**
     * SensitiveGate — on-device last line of defence against invented
     * money/message/call actions (e.g. prompt-injected tool calls the user
     * never asked for). A sensitive tool runs ONLY if the user's own message
     * names its target (contact/number/symbol). Otherwise the brain gets a
     * confirmation-required message forcing it to ask the user in chat first.
     * Every decision is audit-logged to frit_prefs (cap 100).
     * Kill switches: frit_prefs allow_calls / allow_sms / allow_trading.
     */
    private object SensitiveGate {
        private val SENSITIVE = setOf(
            "make_call", "send_sms", "send_whatsapp",
            "place_mt5_trade", "modify_mt5_order", "close_mt5_order"
        )
        fun isSensitive(name: String) = name in SENSITIVE

        fun check(name: String, args: JSONObject, userMessage: String, prefs: android.content.SharedPreferences?): String? {
            if (name !in SENSITIVE) return null // not sensitive: proceed
            val kind = when (name) {
                "make_call" -> "allow_calls"
                "send_sms", "send_whatsapp" -> "allow_sms"
                else -> "allow_trading"
            }
            if (prefs != null && !prefs.getBoolean(kind, true)) {
                audit(prefs, name, targetOf(name, args), "denied:killed")
                return "BLOCKED: the user disabled $kind on this device. Tell the user and stop."
            }
            val msg = userMessage.lowercase()
            val target = targetOf(name, args).lowercase()
            val named = target.isNotBlank() && target.split(Regex("\\s+")).filter { it.length >= 3 }
                .any { msg.contains(it) } || target.filter { it.isDigit() }.length >= 7 && msg.contains(target.filter { it.isDigit() }.take(7))
            audit(prefs, name, targetOf(name, args), if (named) "allowed" else "denied:confirm")
            if (named) return null // explicit user intent: proceed
            return "CONFIRMATION REQUIRED: the user never named '${targetOf(name, args)}' for '$name'. " +
                "Do NOT call this tool again until you have asked the user in plain chat " +
                "(\"Shall I $name ${targetOf(name, args)}?\") and they said yes. End your turn with that question."
        }

        private fun targetOf(name: String, args: JSONObject): String = when (name) {
            "make_call" -> args.optString("contact_name").ifBlank { args.optString("phone_number") }
            "send_sms", "send_whatsapp" -> args.optString("contact_name")
            else -> args.optString("symbol")
        }

        // FIX (F-31): the audit log previously lived in a SharedPreferences
        // StringSet — a Set is UNSORTED, so "add(0, entry)" was meaningless and
        // the 100-entry cap rotated arbitrary entries instead of the newest.
        // It is now one joined String (\u0001-separated) with a true LIFO cap.
        // Legacy String-typed value migration: preferring getString throws
        // ClassCastException on a stored Set, which is caught and the old value
        // replaced on first write. (No reader existed prior to this fix.)
        private const val AUDIT_KEY = "sensitive_audit"
        private const val AUDIT_SEP = "\u0001"
        private fun audit(prefs: android.content.SharedPreferences?, tool: String, target: String, verdict: String) {
            if (prefs == null) return
            try {
                val entry = "${System.currentTimeMillis()}|$tool|$target|$verdict"
                    .replace("\u0001", " ").replace("\n", " ")
                var cur = try { prefs.getString(AUDIT_KEY, "") ?: "" } catch (_: ClassCastException) {
                    prefs.edit().remove(AUDIT_KEY).apply(); ""
                }
                val next = if (cur.isBlank()) entry else "$cur$AUDIT_SEP$entry"
                prefs.edit().putString(AUDIT_KEY, next.split(AUDIT_SEP).takeLast(100).joinToString(AUDIT_SEP)).apply()
            } catch (e: Exception) { Diag.warn("SensitiveGate", "audit-ring write", e) }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        RouteController.init(context)
    }

    fun getAppContext(): Context? = appContext

    fun clearArtifacts() {
        _lastArtifacts.value = emptyList()
    }

    private fun addThought(step: String) {
        _thoughtSteps.value = _thoughtSteps.value + step
    }

    private fun clearThoughts() {
        _thoughtSteps.value = emptyList()
    }

    suspend fun process(text: String, history: List<ChatMessage>, imageBase64: String? = null, allowReschedule: Boolean = true): String = withContext(Dispatchers.IO) {
        // Veil task accounting (Phase 1): every brain turn counts as task
        // activity so the veil can arm while the AI works. Balanced in the
        // finally below — early returns stay counted.
        com.frit.app.features.veil.VeilManager.taskStarted()
        try {
        // Stranger-pickup pause: no new brain turns until the owner re-verifies.
        if (com.frit.app.features.veil.VeilManager.areTasksPaused())
            return@withContext "Tasks are paused — the phone was picked up mid-task. Verify it's you on the veil screen, then try again."
        clearThoughts()

        // Personal-details auto-learn: explicit facts ("remember…", "my name
        // is…", "call me…", "I work as…") persist to UserProfile + memory
        // immediately, with a one-line chat confirmation (never silent).
        // "@@GENERIC@@" = a remember-fact that matched no profile field.
        var learnedNote: String? = null
        var genericFact: String? = null
        try {
            val learned = UserProfile.learnFromText(text)
            if (learned != null) {
                if (learned.startsWith("@@GENERIC@@")) {
                    genericFact = learned.removePrefix("@@GENERIC@@").trim()
                    if (genericFact.isNotBlank()) {
                        MemoryEngine.add("user_fact: $genericFact")
                        learnedNote = "Saved to memory: $genericFact"
                    }
                } else {
                    learnedNote = "✅ $learned"
                    // Mirror profile facts into memory so relevant() retrieval
                    // keeps working for prompts that only read memory.
                    val block = UserProfile.toPromptBlock()
                    if (block.isNotBlank()) {
                        // Store just the new delta, not the whole block.
                        MemoryEngine.add("user_profile: $learned")
                    }
                }
            }
        } catch (e: Exception) { Diag.warn("IntegrationCoordinator", "profile learning", e) }

        fun withLearnedNote(reply: String): String =
            if (learnedNote != null) "$learnedNote\n\n$reply" else reply

        // Recurring-task scheduling is a purely local utility — resolve it
        // before any brain routing so it never gets hijacked or lost.
        // allowReschedule=false when invoked FROM the automation engine itself,
        // otherwise each fire would fork a new schedule instead of executing.
        clearArtifacts()
        if (allowReschedule) {
            val parsed = com.frit.app.features.automation.AutomationScheduler.parseRecurringTask(text)
            if (parsed != null) {
                addThought("Scheduling repetitive automation...")
                com.frit.app.features.automation.AutomationScheduler.scheduleTask(
                    parsed.task, parsed.task, parsed.intervalSeconds
                )
                val human = com.frit.app.features.automation.AutomationScheduler.formatInterval(parsed.intervalSeconds)
                return@withContext withLearnedNote("Scheduled: '${parsed.task}' every $human")
            }
        }

        // Fix 6 — conversation sticky routing. decide() locks the brain for the
        // whole conversation window, so the local model never hijacks a cloud
        // session (and vice versa) in the middle of a chat.
        val isOffline = !isOnline()
        val brain = RouteController.decide(isOffline, imageBase64 != null)

        when (brain) {
            RouteController.Brain.LOCAL -> {
                addThought("Consulting local AI engine...")
                // forceLocal: the user's routing preference says the local brain
                // owns this conversation — do NOT let it fall through to cloud.
                withLearnedNote(LocalInferenceEngine.process(text, history, forceLocal = true))
            }
            else -> {
                addThought("Routing to Cloud Brain...")
                // Streaming: the server streams the reply token by token. If the
                // message turns out to be a task, the server says so and
                // chatStreamed falls through to the full agent loop.
                withLearnedNote(chatStreamed(text, history, imageBase64))
            }
        }
        } finally {
            com.frit.app.features.veil.VeilManager.taskFinished()
        }
    }

    /**
     * Chat with a STREAMED reply (audit: "streaming responses for AI chat").
     *
     * The server decides whether the message is plain conversation or real
     * work. Conversation is streamed token-by-token into [streamText] so the
     * UI can show the answer as it forms. Real work is handed straight to the
     * agent loop, which owns tool calls and device actions.
     *
     * Falls back to the plain agent path whenever the stream is unavailable,
     * so a server without the streaming endpoint still works.
     */
    suspend fun chatStreamed(message: String, history: List<ChatMessage>, imageBase64: String? = null): String = withContext(Dispatchers.IO) {
        if (com.frit.app.features.veil.VeilManager.areTasksPaused())
            return@withContext "Tasks are paused — owner verification needed on the veil screen."
        try {
            val result = NetworkClient.streamChat(message, history) { delta ->
                _streamText.value = _streamText.value + delta
            }
            when {
                result.ok && !result.isTask -> {
                    _streamText.value = ""
                    lastReplyWasStreamed = true
                    return@withContext result.reply
                }
                // A task (or a failed stream) needs the full agent loop.
                else -> {
                    _streamText.value = ""
                    lastReplyWasStreamed = false
                    return@withContext chat(message, history, imageBase64)
                }
            }
        } catch (e: Exception) {
            Diag.warn("IntegrationCoordinator", "chatStreamed", e)
            _streamText.value = ""
            lastReplyWasStreamed = false
            chat(message, history, imageBase64)
        }
    }

    /**
     * Consolidated Agent Loop — Server owned, Client executed.
     */
    suspend fun chat(message: String, history: List<ChatMessage>, imageBase64: String? = null): String = withContext(Dispatchers.IO) {        if (com.frit.app.features.veil.VeilManager.areTasksPaused())
            return@withContext "Tasks are paused — owner verification needed on the veil screen."
        addThought("Initializing agent session...")

        val relevantMemories = try { MemoryEngine.relevant(message).take(5) } catch (e: Exception) { emptyList() }
        // Fix 1 — installed_apps are included in EVERY /agent/start and
        // /agent/resume device_state so the server can ground open_app calls.
        val deviceState = DeviceStateCollector.build(
            memory = JSONArray(relevantMemories),
            imageBase64 = imageBase64,
            includeInstalledApps = true
        ).apply {
            put("system_override", "You are FRIT, an expert autonomous Android AI assistant developed by the FRIT team. You are NOT ChatGPT, Gemini, or Claude. Always identify as FRIT.")
        }

        var responseStr = NetworkClient.startAgent(message, deviceState, history)
        var response = try { JSONObject(responseStr) } catch (e: Exception) { JSONObject() }

        // Surface server errors immediately instead of falling through to a
        // misleading "Task completed." default later.
        if (response.optString("status") == "error") {
            return@withContext errorMessageFor(response, "/agent/start")
        }

        var sessionId = response.optString("sessionId", "")
        collectArtifacts(response)

        var iterations = 0
        // 30 steps: multi-app flows (Opay transfer, Facebook post, MT5 modify)
        // need far more turns than a single chat reply. Do NOT lower this —
        // a low cap is what makes long tasks look "timed out / lost".
        while (!response.optBoolean("done", true) && iterations < 30) {
            val pendingActions = response.optJSONArray("pending_actions") ?: JSONArray()
            val results = JSONArray()

            for (i in 0 until pendingActions.length()) {
                val action = pendingActions.getJSONObject(i)
                val toolId = action.getString("id")
                val name = action.getString("tool")
                val args = action.optJSONObject("arguments") ?: JSONObject()

                // Dual Profile: any UI-changing step runs PRO (verified), pure reads run FLASH.
                // PRO = tap/type/open/scroll/press + app actions (MT5, WhatsApp, calls) + money/post verbs.
                // This is why you mostly saw FLASH before: read_screen steps are FLASH by design.
                val lowerMsg = message.lowercase()
                val isProExecutionMode = name.contains("tap") || name.contains("type") ||
                    name.contains("open") || name.contains("scroll") || name.contains("press") ||
                    name.contains("go_back") || name.contains("mt5") || name.contains("modify") ||
                    name.contains("close") || name.contains("whatsapp") || name.contains("sms") ||
                    name.contains("call") || name.contains("alarm") || name.contains("timer") ||
                    lowerMsg.contains("verify") || lowerMsg.contains("pay") || lowerMsg.contains("transfer") ||
                    lowerMsg.contains("send") || lowerMsg.contains("post") || lowerMsg.contains("buy") ||
                    lowerMsg.contains("sell") || lowerMsg.contains("trade")
                
                addThought(if (isProExecutionMode) "Executing (PRO Verified): $name..." else "Executing (FLASH): $name...")
                
                val initialScreenContext = if (isProExecutionMode) FritAccessibilityService.getScreenText() else ""
                // SensitiveGate: invented calls/messages/trades die here with a
                // ask-the-user message instead of reaching the dialer/MT5.
                val prefs = appContext?.getSharedPreferences("frit_prefs", Context.MODE_PRIVATE)
                val gateMsg = SensitiveGate.check(name, args, message, prefs)
                var result = gateMsg ?: executeEdgeTool(name, args, message)

                // Artemis Wait & Verify Mindset implementation
                if (isProExecutionMode && initialScreenContext.isNotBlank()) {
                    // Verify wait trimmed 2500 -> 1500ms: executeEdgeTool's
                    // freshScreenFeed already settled ~700ms post-action, so a
                    // full extra 2.5s here was pure dead time per PRO step.
                    kotlinx.coroutines.delay(1500) // allow the UI thread state layer to refresh
                    val updatedScreenContext = FritAccessibilityService.getScreenText()
                    if (updatedScreenContext == initialScreenContext) {
                        addThought("Verification warning: Screen content hasn't changed. Re-verifying element targeting...")
                        result += " [Verification Notice: The action was triggered but the screen text contents remained identical. If the button click missed or failed to load, please verify coordinates or toggle scrolling fallback]"
                    } else {
                        addThought("Verification PASSED: UI change detected.")
                    }
                }

                results.put(JSONObject().apply {
                    put("tool_call_id", toolId)
                    put("content", result)
                })
            }

            addThought("Reporting results to brain...")
            // Resume turns skip the installed-app inventory: the server cached
            // it from /agent/start (last_device_state merge), so resending
            // 150+ labels+packages every turn was pure token burn + exfil.
            val nextDeviceState = DeviceStateCollector.build(includeInstalledApps = false)

            // Resume retry: one transient network blip must NOT kill a 20-step
            // Opay/Facebook flow and surface as "timed out / lost".
            var resumeTries = 0
            while (true) {
                try {
                    responseStr = NetworkClient.resumeAgent(sessionId, results, nextDeviceState)
                    break
                } catch (e: Exception) {
                    if (++resumeTries >= 2) throw e
                    Log.w(TAG, "resumeAgent transient failure, retrying...", e)
                    kotlinx.coroutines.delay(2000)
                }
            }
            response = try { JSONObject(responseStr) } catch (e: Exception) {
                Log.e(TAG, "Resume failed", e)
                val fallbackText = if (pendingActions.length() > 0) {
                    "Task completed! Executed action and switched to target app."
                } else {
                    "Task complete. Network session closed."
                }
                JSONObject().put("done", true).put("assistant_text", fallbackText)
            }
            if (response.optString("status") == "error") {
                return@withContext errorMessageFor(response, "/agent/resume")
            }
            collectArtifacts(response)
            iterations++
        }

        val text = response.optString("assistant_text")
        return@withContext text.ifBlank {
            // The brain returned nothing meaningful — give a clear, actionable message.
            "I wasn't able to get a complete response. This can happen when the server is busy or a step took too long. Please try again — if it keeps happening, check your API token in Settings."
        }
    }

    private fun errorMessageFor(response: JSONObject, endpoint: String): String {
        val code = response.optInt("code", 0)
        var raw = response.optString("message").ifBlank { response.optString("error").ifBlank { "unknown error" } }
        // Network envelopes wrap the raw server body as a JSON string
        // (e.g. {"error":"Goal required"}) — surface the inner message
        // instead of the raw JSON blob.
        if (raw.trimStart().startsWith("{")) {
            raw = try {
                val inner = JSONObject(raw)
                inner.optString("error").ifBlank { inner.optString("message").ifBlank { raw } }
            } catch (_: Exception) { raw }
        }
        Log.e(TAG, "$endpoint returned error code=$code message=$raw")
        return when (code) {
            401 -> "Authentication failed (401). Open Settings → API Token and set a valid token."
            403 -> "Forbidden (403). Your API token doesn't allow this operation."
            429 -> "Rate-limited by the server. Please wait a moment and try again."
            503 -> "All AI providers are currently rate-limited. Please retry shortly."
            else -> "The brain returned an error ($code): $raw"
        }
    }

    /**
     * Collect server-generated artifacts (run_code outputs: PNG charts, CSVs,
     * backtest reports) from an agent response so the UI can render/download them.
     */
    private fun collectArtifacts(response: JSONObject) {
        try {
            val arr = response.optJSONArray("artifacts") ?: return
            val list = mutableListOf<Artifact>()
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                val name = a.optString("name", "file")
                val mime = a.optString("mime", "application/octet-stream")
                val size = a.optLong("size", 0)
                val rawBase64 = when {
                    a.has("content_base64") && !a.isNull("content_base64") -> a.getString("content_base64")
                    a.has("base64") && !a.isNull("base64") -> a.getString("base64")
                    a.has("b64") && !a.isNull("b64") -> a.getString("b64")
                    a.has("data") && !a.isNull("data") -> a.getString("data")
                    else -> null
                }
                val rawContent = when {
                    a.has("content") && !a.isNull("content") -> a.getString("content")
                    a.has("text") && !a.isNull("text") -> a.getString("text")
                    a.has("code") && !a.isNull("code") -> a.getString("code")
                    else -> null
                }
                val cleaned = rawBase64?.substringAfter(',', rawBase64)
                if (list.none { it.name == name && it.mime == mime }) {
                    list.add(Artifact(name, mime, size, cleaned, rawContent))
                }
            }
            if (list.isNotEmpty()) {
                _lastArtifacts.value = _lastArtifacts.value + list
            }
        } catch (e: Exception) {
            Log.e(TAG, "collectArtifacts error", e)
        }
    }

    /**
     * read_screen must NEVER silently return "" — that is what made the agent
     * loop on an "empty screen". If the accessibility layer has no text, report
     * WHY and give the brain a path forward (structured dump / screenshot).
     */
    private suspend fun readScreenDiagnostic(): String {
        val text = FritAccessibilityService.getScreenText()
        if (text.isNotBlank()) return text.take(1500)

        val dump = FritAccessibilityService.getStructuredScreenDump()
        if (dump != """{"elements":[]}""") {
            return "No plain text readable, but structured elements exist:\n$dump"
        }

        // Auto vision fallback: previously this just TOLD the brain to call
        // analyze_screenshot itself, costing a whole extra turn (and often the
        // brain wouldn't bother). If a screen capture is already sitting there
        // (MediaProjection/ContinuousVisionPipeline), analyze it right now and
        // hand back a real description instead of an instruction to try again.
        val hasCapture = ScreenCaptureService.lastScreenshotBase64 != null
        if (hasCapture) {
            val described = tryAutoVisionDescribe("Describe the UI elements and any actionable items on this screen.")
            if (described != null) return "No accessibility text on this screen (custom-drawn UI). Auto vision description:\n$described"
        }

        val app = FritAccessibilityService.getCurrentApp()
        val act = FritAccessibilityService.getCurrentActivity()
        val offlineHint = accessibilityHint()
        return buildString {
            append("Screen context EMPTY. ")
            append("$offlineHint ")
            append("Current app: ${app.ifBlank { "unknown" }}, activity: ${act.ifBlank { "unknown" }}. ")
            append(if (hasCapture) "A screen capture is available but auto-analysis failed — call analyze_screenshot to retry." else "No screen capture active either — open the target app (open_app or press_home + tap) so the phone screen has readable content.")
        }
    }

    /** Best-effort auto vision call; returns null (never throws) so callers can
     *  fall back to the old "ask the brain to call analyze_screenshot" text. */
    private suspend fun tryAutoVisionDescribe(prompt: String): String? {
        return try {
            val sc = ScreenCaptureService.lastScreenshotBase64 ?: return null
            val response = NetworkClient.analyzeScreenFrame(JSONObject().put("prompt", prompt).put("frameData", sc))
            val json = JSONObject(response)
            val desc = json.optString("analysis", json.optString("description", ""))
            desc.ifBlank { null }?.take(1200)
        } catch (e: Exception) {
            Log.w(TAG, "Auto vision describe failed (non-fatal)", e)
            null
        }
    }

    /**
     * Generic tap fallback: text tap missed (common in Opay/Facebook/MT5 custom
     * views), so scan the structured dump for a bounds-center near the label
     * and gesture-tap it. Keeps the agent from getting "lost" on one miss.
     */
    // AUTO-VISION: after EVERY device action we re-capture the fresh screen text
    // and append it to the tool result, so the brain's next observation is always
    // current without having to remember to request read_screen itself.
    private suspend fun freshScreenFeed(tail: String): String {
        kotlinx.coroutines.delay(700L) // let ripple/animation + async UI settle
        val fresh = FritAccessibilityService.getScreenText().take(1200)
        return "$tail. Screen text:\n${fresh.ifBlank { "(Screen text empty or custom-drawn — call take_screenshot + analyze_screenshot)" }}"
    }
    private suspend fun tapFallbackByCoordinates(label: String): String {
        return try {
            val dump = FritAccessibilityService.getStructuredScreenDump()
            val arr = try {
                JSONObject(dump).optJSONArray("elements")
            } catch (_: Exception) { null }
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val el = arr.optJSONObject(i) ?: continue
                    val t = (el.optString("text") + " " + el.optString("desc")).lowercase()
                    if (label.lowercase() in t || t.contains(label.lowercase().take(4))) {
                        val b = el.optJSONObject("bounds")
                        if (b != null) {
                            val cx = (b.optInt("l") + b.optInt("r")) / 2
                            val cy = (b.optInt("t") + b.optInt("b")) / 2
                            if (cx > 0 && cy > 0) {
                                FritAccessibilityService.executeStep("tap_coordinates", x = cx, y = cy)
                                return "Text tap missed '$label'; fallback coordinate tap at ($cx,$cy) dispatched"
                            }
                        }
                    }
                }
            }
            // Last resort: scroll once and ask the brain to re-read — the target
            // is often just below the fold in long app screens.
            FritAccessibilityService.executeStep("scroll", label = "down")
            "Could not find '$label' — scrolled down once, call read_screen again and retry (or use tap_coordinates from read_screen_structured)"
        } catch (e: Exception) {
            "Could not find '$label' (fallback error: ${e.message})"
        }
    }

    private fun accessibilityHint(): String {
        return if (com.frit.app.features.market.FritAccessibilityService.instance == null) {
            "The Accessibility Service is NOT running (enable it in Android Settings → Accessibility → FRIT Assistant), so the screen cannot be read."
        } else {
            "The current screen exposes no accessibility text (custom-drawn UI, permission dialog, or screen off)."
        }
    }

    private suspend fun executeEdgeTool(name: String, args: JSONObject, userMessage: String = ""): String {
        return try {
            when (name) {
                "search_web"      -> {
                    val query = args.optString("query")
                    NetworkClient.webSearch(query)
                }
                "get_market_data"  -> NetworkClient.getMarketQuote(args.optString("symbol", "BTCUSD"))
                "analyze_market"   -> NetworkClient.analyzeMarket(args)
                "place_mt5_trade"  -> {
                    val symbol = args.optString("symbol", "BTCUSD")
                    val action = args.optString("action", "BUY")
                    val volume = args.optDouble("volume", 0.01)
                    val sl = args.optDouble("sl", 0.0)
                    val tp = args.optDouble("tp", 0.0)
                    com.frit.app.features.market.MT5Agent.placeTrade(
                        com.frit.app.features.market.MT5Agent.TradeOrder(symbol, action, volume, sl, tp)
                    )
                }
                // Cloud browses first (search_web), then directs these concrete
                // order-management steps; local hands execute them on-device.
                "modify_mt5_order" -> {
                    val symbol = args.optString("symbol", "")
                    val sl = args.optDouble("sl", 0.0)
                    val tp = args.optDouble("tp", 0.0)
                    com.frit.app.features.market.MT5Agent.modifyPosition(symbol, sl, tp)
                }
                "close_mt5_order" -> {
                    val symbol = args.optString("symbol", "")
                    com.frit.app.features.market.MT5Agent.closePosition(symbol)
                }
                "get_mt5_positions" -> {
                    val pos = FritAccessibilityService.getMT5Positions()
                    if (pos.isEmpty()) "No open MT5 positions readable on screen"
                    else pos.joinToString("\n") { "${it.type} ${it.symbol} Vol:${it.volume} P/L:${it.profit}" }
                }
                "get_weather"      -> NetworkClient.getWeather(args.optString("city", "Lagos"))
                "get_systems_status" -> NetworkClient.getSystemsStatus()
                "read_screen"      -> readScreenDiagnostic()
                "read_screen_structured" -> FritAccessibilityService.getStructuredScreenDump()
                "wait_and_verify"  -> {
                    val sec = args.optLong("seconds", 2L)
                    kotlinx.coroutines.delay(sec * 1000L)
                    val screen = FritAccessibilityService.getScreenText().take(1500)
                    "Waited ${sec}s. Screen context: ${if (screen.isNotBlank()) screen else "EMPTY — " + accessibilityHint()}"
                }
                "assert_text_visible" -> {
                    val targetText = args.optString("text")
                    val screen = FritAccessibilityService.getScreenText()
                    if (screen.contains(targetText, ignoreCase = true)) {
                        "Assertion PASSED: '$targetText' is visible on screen."
                    } else {
                        "Assertion FAILED: '$targetText' not found on screen."
                    }
                }
                "take_screenshot" -> {
                    ScreenCaptureService.captureNow()
                    "Screenshot refreshed. Use analyze_screenshot or check the next context turn."
                }
                "analyze_screenshot" -> {
                    val prompt = args.optString("prompt", "Analyze the UI of this screen.")
                    val sc = ScreenCaptureService.lastScreenshotBase64
                    if (sc != null) {
                        // Forward to cloud vision model specifically for analysis
                        NetworkClient.analyzeScreenFrame(JSONObject().put("prompt", prompt).put("frameData", sc))
                    } else "Error: No screen capture active."
                }
                "get_current_app"  -> FritAccessibilityService.getCurrentApp()
                "get_current_activity" -> FritAccessibilityService.getCurrentActivity()
                "tap_button"       -> {
                    val label = args.optString("label")
                    val res = if (FritAccessibilityService.clickButtonWithText(label)) "Clicked $label"
                    else tapFallbackByCoordinates(label)
                    freshScreenFeed(res)
                }
                "tap_coordinates" -> {
                    val x = args.optInt("x")
                    val y = args.optInt("y")
                    FritAccessibilityService.executeStep("tap_coordinates", x = x, y = y)
                    freshScreenFeed("Tapped coordinates ($x, $y)")
                }
                "type_text"        -> {
                    val text = args.optString("text", args.optString("value"))
                    val field = args.optString("field", "")
                    FritAccessibilityService.executeStep("type_text", value = text, field = field)
                    freshScreenFeed("Typed '$text' into $field")
                }
                "scroll"           -> {
                    val dir = args.optString("direction", "down")
                    FritAccessibilityService.executeStep("scroll", label = dir)
                    freshScreenFeed("Scrolled $dir")
                }
                "go_back"          -> {
                    FritAccessibilityService.executeStep("press_back")
                    freshScreenFeed("Pressed back")
                }
                "press_home"       -> {
                    FritAccessibilityService.executeStep("press_home")
                    freshScreenFeed("Pressed home")
                }
                // Wired to FritAccessibilityService.executeStep global actions that
                // already existed but had no dispatch case here — the brain could
                // never reach the notification shade, quick settings, recents, or
                // lock screen even though the on-device capability was present.
                "toggle_notifications" -> {
                    val res = FritAccessibilityService.executeStep("toggle_notifications")
                    freshScreenFeed(res)
                }
                "toggle_quick_settings" -> {
                    val res = FritAccessibilityService.executeStep("toggle_quick_settings")
                    freshScreenFeed(res)
                }
                "recent_apps"      -> {
                    val res = FritAccessibilityService.executeStep("recent_apps")
                    freshScreenFeed(res)
                }
                "lock_screen"      -> FritAccessibilityService.executeStep("lock_screen")
                // App-PIN vault: returns the stored transaction PIN ONLY when the
                // brain explicitly asks (an app screen demands a PIN it was never
                // given). Every access is logged; empty vault tells the brain to
                // ask the user to set Settings → App PIN vault first.
                "get_app_pin"      -> {
                    val pin = try {
                        appContext?.let { com.frit.app.core.SecureStore.getString(it, "app_pin") }.orEmpty()
                    } catch (_: Exception) { "" }
                    Log.i(TAG, "get_app_pin requested by brain (stored=${pin.isNotBlank()})")
                    if (pin.isBlank()) "NO_PIN_STORED — ask the user to set Settings → App PIN vault first"
                    else pin
                }
                // Ambient listening: record N seconds of room audio (e.g. a
                // contact's voice note playing on speaker) and transcribe it.
                // Used by the voicenote flow: play the bubble, then this.
                "listen_ambient" -> {
                    val secs = args.optInt("seconds", 20).coerceIn(5, 60)
                    // Our own TTS would contaminate the capture — cut it and
                    // let the room settle before opening the mic.
                    com.frit.app.features.voice.VoiceEngine.stopSpeaking()
                    kotlinx.coroutines.delay(500)
                    var heard = ""
                    com.frit.app.features.voice.VoiceEngine.recordAndTranscribe(durationMs = secs * 1000) { text -> heard = text }
                    if (heard.isBlank()) {
                        // One automatic second pass (matches the voicenote
                        // skill's retry rule) so a slow-starting bubble doesn't
                        // cost a brain round-trip before reporting failure.
                        kotlinx.coroutines.delay(500)
                        com.frit.app.features.voice.VoiceEngine.recordAndTranscribe(durationMs = secs * 1000) { text -> heard = text }
                    }
                    if (heard.isBlank()) "Heard nothing transcribable in ${secs}s of ambient audio. Check the bubble is actually playing on speaker with volume up, then retry once."
                    else "Heard (${secs}s ambient): $heard"
                }
                "swipe_coordinates" -> {
                    val x = args.optInt("x")
                    val y = args.optInt("y")
                    val res = FritAccessibilityService.executeStep("swipe_coordinates", x = x, y = y)
                    freshScreenFeed(res)
                }
                "long_press_element" -> {
                    val label = args.optString("label")
                    val res = FritAccessibilityService.executeStep("long_press_element", label = label)
                    freshScreenFeed(res)
                }
                "take_native_screenshot" -> FritAccessibilityService.executeStep("take_native_screenshot")
                "open_app"         -> {
                    val name = args.optString("app_name", args.optString("package"))
                    if (name.isBlank()) return "Error: app_name required"
                    // Agent leaves FRIT app => auto-enable screen vision for the
                    // whole task (uses cached MediaProjection consent; silent
                    // no-op if the user never granted capture permission).
                    appContext?.let { ScreenCaptureService.autoStartIfPermitted(it) }
                    val report = AutomationEngine.openAppReport(name)
                    // Heavy apps (Opay, Facebook, MT5) cold-start slowly — 900ms
                    // truncated them and the next tap landed on the old screen.
                    kotlinx.coroutines.delay(1600L)
                    var freshScreen = FritAccessibilityService.getScreenText().take(1200)
                    if (freshScreen.isBlank()) {
                        kotlinx.coroutines.delay(2000L)
                        freshScreen = FritAccessibilityService.getScreenText().take(1200)
                    }
                    "App open report: $report. Screen text:\n${freshScreen.ifBlank { "(Screen text empty or custom-drawn — call take_screenshot + analyze_screenshot)" }}"
                }
                "execute_local_action" -> {
                    val action = args.optString("action", args.optString("command"))
                    if (action.isBlank()) return "Error: action required"
                    // F-18a: this tool is a generic escape hatch — the brain can
                    // ask for "call 080..." through it, and it never passes
                    // through SensitiveGate (which only knows the explicit
                    // `make_call` tool name). So the call target is checked
                    // HERE against the user's own message: if the user did not
                    // name the target, the dialer opens for them to confirm
                    // instead of the call being placed.
                    val callTarget = CallGuard.callTargetFromAction(action)
                    if (callTarget != null) {
                        val allowed = try {
                            appContext?.getSharedPreferences("frit_prefs", Context.MODE_PRIVATE)
                                ?.getBoolean("allow_calls", true) ?: true
                        } catch (e: Exception) { true }
                        when (CallGuard.decide(userMessage, callTarget, allowed)) {
                            CallGuard.Decision.DENY ->
                                return "BLOCKED: calls are disabled on this device (allow_calls off). Tell the user and stop."
                            CallGuard.Decision.PLACE_NOW -> { /* fall through to execute */ }
                            CallGuard.Decision.CONFIRM_FIRST ->
                                return "CONFIRMATION REQUIRED: the user never named '$callTarget'. " +
                                    "Open the dialer for $callTarget and ask the user to tap call. Do NOT place the call yourself."
                        }
                    }
                    val result = AutomationEngine.executeLocalOnly(action, automated = callTarget != null)
                        ?: "Local engine can't handle '$action' — use read_screen + tap tools instead"
                    kotlinx.coroutines.delay(1600L)
                    val freshScreen = FritAccessibilityService.getScreenText().take(1200)
                    "Local result: $result. Screen text:\n${freshScreen.ifBlank { "(Screen text empty or custom-drawn — call take_screenshot + analyze_screenshot)" }}"
                }
                "return_to_frit"   -> {
                    AutomationEngine.returnToFrit()
                    // Task over => vision back off (only if the agent turned it on).
                    appContext?.let { ScreenCaptureService.autoStopIfAuto(it) }
                    "FRIT brought back to foreground"
                }
                "send_whatsapp"    -> {
                    val contact = args.optString("contact_name")
                    val msg = args.optString("message")
                    AutomationEngine.openWhatsApp(contact, msg)
                    "Opened WhatsApp for $contact"
                }
                "send_sms"         -> {
                    val contact = args.optString("contact_name")
                    val msg = args.optString("message")
                    val num = AutomationEngine.resolveContactPublic(contact) ?: contact
                    AutomationEngine.openSmsCompose(num, msg)
                    "Opened SMS for $contact"
                }
                "make_call"        -> {
                    val contact = args.optString("contact_name")
                    val num = AutomationEngine.resolveContactPublic(contact) ?: args.optString("phone_number")
                    val converse = args.optBoolean("converse", false)
                    if (num.isNotBlank()) {
                        val now = System.currentTimeMillis()
                        if (num == lastCallNumber && now - lastCallAt < CALL_DEDUPE_MS) {
                            "Call to $contact ($num) was ALREADY placed ${((now - lastCallAt) / 1000)}s ago — task is DONE, do not redial. End the task now."
                        } else {
                            // F-18a: SensitiveGate already established that the
                            // user named this target in their own message, so
                            // the call may be placed. The kill switch is
                            // re-checked here because the generic
                            // `execute_local_action` path can reach this
                            // function without passing through SensitiveGate.
                            val allowed = try {
                                appContext?.getSharedPreferences("frit_prefs", Context.MODE_PRIVATE)
                                    ?.getBoolean("allow_calls", true) ?: true
                            } catch (e: Exception) { true }
                            if (!allowed) {
                                "Calls are disabled on this device — the user disabled allow_calls. Do not call. Tell the user and stop."
                            } else {
                                lastCallNumber = num
                                lastCallAt = now
                                AutomationEngine.makeCall(num, confirmed = true)
                                if (converse) {
                                    // Hand off to the live conversation service (outgoing mode:
                                    // waits for the other side to answer, then greet/listen/reply).
                                    try {
                                        appContext?.let { ctx ->
                                            com.frit.app.features.call.CallConversationService.start(
                                                ctx, num, outgoing = true
                                            )
                                        }
                                        "Calling $contact ($num) — FRIT will hold a live voice conversation once the call is answered. The task continues until the conversation ends (greet → listen → reply → hang up on goodbye/silence/5-min cap)."
                                    } catch (e: Exception) {
                                        "Calling $contact ($num) — dialer opened via call. Task is DONE."
                                    }
                                } else {
                                    "Calling $contact ($num) — dialer opened, task is DONE. Do NOT call again; end the task and tell the user the call was placed."
                                }
                            }
                        }
                    } else "Could not resolve number for $contact"
                }
                // Incoming-call auto-answer toggle: answers via Telecom and holds
                // a live half-duplex AI voice conversation (speakerphone bridge).
                "answer_calls"     -> {
                    val enable = args.optBoolean("enabled", true)
                    appContext?.getSharedPreferences("frit_prefs", Context.MODE_PRIVATE)
                        ?.edit()?.putBoolean("auto_answer_calls", enable)?.apply()
                    if (enable) "Auto-answer ENABLED — FRIT will answer incoming calls and hold a live voice conversation (greets, listens, replies, hangs up on goodbye/silence/5-min cap; half-duplex, needs a reasonably quiet room)"
                    else "Auto-answer disabled"
                }
                "set_alarm"        -> {
                    val time = args.optString("time")
                    val label = args.optString("label", "FRIT Alarm")
                    AutomationEngine.setAlarm(label, time)
                    "Alarm set for $time"
                }
                "set_timer"        -> {
                    val duration = args.optString("duration")
                    AutomationEngine.setTimer(duration)
                    "Timer started for $duration"
                }
                "play_music"       -> {
                    val query = args.optString("query")
                    AutomationEngine.playMusic(query)
                    "Playing $query"
                }
                "navigate_to"      -> {
                    val dest = args.optString("destination")
                    AutomationEngine.navigateTo(dest)
                    "Navigating to $dest"
                }
                "take_photo"       -> {
                    val front = args.optBoolean("front_camera", false)
                    AutomationEngine.launchAppPublic("camera")
                    "Camera app opened. Please use 'read_screen' to find the capture button and 'tap_coordinates' or 'tap_button' to take the picture."
                }
                "set_volume"       -> {
                    val level = args.optInt("level")
                    AutomationEngine.setVolume(level)
                    "Volume set to $level"
                }
                "toggle_wifi"      -> {
                    val enabled = args.optBoolean("enabled")
                    AutomationEngine.toggleWifi(enabled)
                    "Wi-Fi settings opened"
                }
                "toggle_blue_tooth" -> {
                    val enabled = args.optBoolean("enabled")
                    AutomationEngine.toggleBluetooth(enabled)
                    "Bluetooth settings opened"
                }
                "run_command"      -> {
                    if (!DEV_TOOLS_ENABLED) return "Error: Shell command execution tool is disabled for security (DEV_TOOLS_ENABLED=false)."
                    val cmd = args.optString("command")
                    runShellCommand(cmd)
                }
                "write_file"       -> {
                    if (!DEV_TOOLS_ENABLED) return "Error: File write tool is disabled for security (DEV_TOOLS_ENABLED=false)."
                    val path = args.optString("path")
                    val content = args.optString("content")
                    writeFile(path, content)
                }
                "run_code", "run_sandbox_code" -> {
                    val lang = args.optString("language", "python")
                    val code = args.optString("code")
                    val stdin = args.optString("stdin", "")
                    NetworkClient.runSandboxCode(lang, code, stdin)
                }
                else -> "Error: Tool $name not available on Android Edge."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution error: $name", e)
            "Error executing $name: ${e.message}"
        }
    }

    private fun writeFile(path: String, content: String): String {
        return try {
            val file = java.io.File(path)
            file.writeText(content)
            "File written to ${file.absolutePath}"
        } catch (e: Exception) {
            "Failed to write file: ${e.message}"
        }
    }

    private fun runShellCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            if (error.isNotEmpty()) "Error: $error" else output.ifBlank { "Executed successfully (no output)" }
        } catch (e: Exception) {
            "Failed: ${e.message}"
        }
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = appContext?.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) { false }
    }
}