package com.frit.app.data

import android.util.Log
import com.frit.app.core.FritConfig
import com.frit.app.core.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * NetworkClient — The Unified API Gateway.
 *
 * Security posture:
 *  - The API token lives in TokenStore (app-private storage), NEVER in source.
 *  - TLS certificate pinning is limited to the production server; local dev
 *    endpoints (10.0.2.2 / localhost / LAN) fall back to normal TLS validation.
 *  - Cleartext is disabled by network_security_config for all non-dev hosts.
 */
object NetworkClient {
    private const val TAG = "NetworkClient"

    /**
     * The outcome of a streamed chat request.
     *
     * @param reply the assistant text (empty when the server routed to a task)
     * @param isTask true when the server decided this needs the agent loop
     *        (tool calls, device actions) rather than a plain chat reply —
     *        the caller must then run the normal agent path.
     * @param ok false when the request failed outright (no usable reply)
     */
    data class StreamChatResult(val reply: String, val isTask: Boolean, val ok: Boolean)

    private var BASE_URL = FritConfig.BASE_URL

    // NOTE: certificate pinning was deliberately removed. The production server
    // runs on Render/Railway free-tier, which auto-renews TLS certs behind shared
    // Google/Cloudflare frontends. A hardcoded leaf pin went stale after renewal
    // and made every request fail with "certificate failure". This project relies
    // on standard TLS via the system trust store instead — the network security
    // config still disables cleartext for all non-dev hosts, which is the right
    // security posture here.

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // 150s read: a cold-starting server + a full provider fallback chain
            // can legitimately exceed 90s on first touch; the user can still
            // Stop the chat job mid-wait. Previously this fired "timeout" on
            // slow-but-healthy brains.
            .readTimeout(150, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun setBaseUrl(url: String) {
        BASE_URL = url.trimEnd('/')
    }

    /** Call the current token: per-install key first, baked default as bootstrap. */
    fun auth(): String {
        val install = try { TokenStore.installKey() } catch (_: Exception) { "" }
        if (install.isNotBlank()) return install
        return TokenStore.get().ifBlank { FritConfig.AUTH_TOKEN }
    }

    fun isAuthorized(): Boolean = auth().isNotBlank()

    // One-shot guard so concurrent first-launch calls register exactly once.
    @Volatile private var installRegAttempted = false

    /**
     * Registers this install with the server (POST /auth/install, no token
     * needed) and stores the returned per-install key. After this, monthly
     * credit grants and tier/wallet rows bind to a real device instead of a
     * self-asserted id — this is what stops free-credit farming.
     */
    suspend fun ensureInstallKey(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (TokenStore.installKey().isNotBlank()) return@withContext true
            if (installRegAttempted) return@withContext false
            installRegAttempted = true
            val iid = TokenStore.installId()
            if (iid.isBlank() || BASE_URL.isBlank()) return@withContext false
            val url = if (BASE_URL.startsWith("http")) "$BASE_URL/auth/install" else "https://$BASE_URL/auth/install"
            val body = JSONObject().put("install_id", iid).toString().toRequestBody(JSON)
            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string() ?: ""
                if (!response.isSuccessful) return@withContext false
                val key = JSONObject(raw).optString("api_key", "")
                if (key.isNotBlank()) {
                    TokenStore.setInstallKey(key)
                    // New identity: refresh rows keyed to it on next status call.
                    return@withContext true
                }
                false
            }
        } catch (e: Exception) { com.frit.app.core.Diag.warn("NetworkClient", "ensureInstallKey", e); false }
    }

    // --- Core Agent Loop ---
    // Maps raw transport failures to actionable messages. Previously every
    // one of these surfaced as the cryptic "The brain returned an error (0)".
    private fun friendlyNetError(e: Exception): String {
        val m = (e.message ?: "").lowercase()
        return when {
            "timeout" in m || "timed out" in m ->
                "Brain timeout (server may be cold-starting). Wait a minute and try again."
            "closed by peer" in m || "connection reset" in m || m.startsWith("eof") ->
                "Lost connection to the brain server (it may have restarted). Try again."
            "unable to resolve host" in m || "unknownhost" in m || "no address" in m ->
                "No route to the brain server (DNS/network). Check data/WiFi and the server URL."
            "failed to connect" in m || "connection refused" in m || "unreachable" in m ->
                "Can't reach the brain server. Check it is running and the URL in Settings."
            else -> (e.message ?: "network error").replace("\"", "'")
        }
    }

    suspend fun startAgent(goal: String, deviceState: JSONObject, history: List<com.frit.app.data.ChatMessage> = emptyList()): String = withContext(Dispatchers.IO) {
        val hist = JSONArray()
        history.takeLast(8).forEach { m ->
            hist.put(JSONObject()
                .put("role", if (m.role == "assistant") "assistant" else "user")
                .put("content", m.content))
        }
        val payload = JSONObject().apply {
            put("goal", goal)
            put("device_state", deviceState)
            put("history", hist)
        }
        post("/agent/start", payload.toString())
    }

    suspend fun resumeAgent(sessionId: String, toolResults: JSONArray, deviceState: JSONObject): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("sessionId", sessionId)
            put("tool_results", toolResults)
            put("device_state", deviceState)
        }
        post("/agent/resume", payload.toString())
    }

    suspend fun getAgentStatus(sessionId: String): String = get("/agent/status?sessionId=${eq(sessionId)}")

    /**
     * Streaming chat (audit: "streaming responses for AI chat").
     *
     * POST /agent/chat/stream returns `text/event-stream`. The server sends
     * `delta` events as the model produces text, then a final `done` event.
     *
     * [onDelta] is called for every text chunk, so the UI can render the reply
     * as it arrives instead of waiting for the whole thing.
     *
     * @return the assistant reply, plus whether the server decided this was a
     *         TASK (real work that must go through the agent loop instead).
     */
    suspend fun streamChat(
        goal: String,
        history: List<com.frit.app.data.ChatMessage> = emptyList(),
        onDelta: (String) -> Unit = {},
    ): StreamChatResult = withContext(Dispatchers.IO) {
        if (BASE_URL.isBlank()) {
            Log.e(TAG, "BASE_URL is blank, cannot stream")
            return@withContext StreamChatResult("", isTask = false, ok = false)
        }
        if (!isAuthorized()) {
            Log.e(TAG, "No API token configured - refusing stream")
            return@withContext StreamChatResult("", isTask = false, ok = false)
        }
        val hist = JSONArray()
        history.takeLast(12).forEach { m ->
            hist.put(JSONObject()
                .put("role", if (m.role == "assistant") "assistant" else "user")
                .put("content", m.content))
        }
        val payload = JSONObject().apply {
            put("goal", goal)
            put("history", hist)
        }.toString()
        val url = if (BASE_URL.startsWith("http")) "$BASE_URL${withUid("/agent/chat/stream")}"
                   else "https://$BASE_URL${withUid("/agent/chat/stream")}"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${auth()}")
            .addHeader("Accept", "text/event-stream")
            .addHeader("Cache-Control", "no-cache")
            .post(payload.toRequestBody(JSON))
            .build()

        val parser = SseParser()
        val sb = StringBuilder()
        var isTask = false
        var sawDone = false
        fun handle(ev: SseEvent) {
            when (ev.event) {
                "delta" -> {
                    val t = ev.deltaText()
                    if (t.isNotEmpty()) { sb.append(t); onDelta(t) }
                }
                "done" -> {
                    sawDone = true
                    val o = ev.json()
                    if (o != null) {
                        isTask = o.optBoolean("task", false)
                        // The server sends the full text in the done frame too;
                        // use it when the deltas were lost (e.g. a proxy that
                        // buffered the response).
                        val whole = o.optString("text", "")
                        if (sb.isEmpty() && whole.isNotEmpty()) sb.append(whole)
                    }
                }
                "error" -> Log.e(TAG, "stream error: ${ev.data}")
            }
        }
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    Log.e(TAG, "stream HTTP ${response.code}: $errBody")
                    return@withContext StreamChatResult("", isTask = false, ok = false)
                }
                val source = response.body?.source() ?: return@withContext StreamChatResult("", isTask = false, ok = false)
                // readUtf8Line() returns null at EOF (readUtf8LineStrict throws).
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty()) {
                        // A blank line terminates an SSE frame.
                        parser.feed("\n").forEach { handle(it) }
                    } else {
                        parser.feed("$line\n").forEach { handle(it) }
                    }
                }
                parser.flush().forEach { handle(it) }
            }
        } catch (e: Exception) {
            com.frit.app.core.Diag.warn("NetworkClient", "streamChat", e)
            // A partial reply is better than nothing: keep what arrived.
        }
        StreamChatResult(sb.toString(), isTask = isTask, ok = sawDone || sb.isNotEmpty())
    }

    // --- Screen / Vision ---
    suspend fun uploadScreenFrame(frameData: JSONObject): String = post("/screen/frame", frameData.toString())
    suspend fun analyzeScreenFrame(params: JSONObject): String = post("/screen/analyze-frame", params.toString())
    suspend fun getScreenStatus(): String = get("/screen/status")
    suspend fun clearScreenBuffer(): String = post("/screen/clear", "{}")

    /**
     * FIX (F-15): crash ingest. CrashGuard pushes scrubbed crash dumps here so
     * remote devices are visible. Server-side POST /crash is deliberately
     * unauthenticated, but our own `post` helper refuses when no token is set,
     * so callers check isAuthorized() first and retry later (files stay on
     * disk until the server acks). Payload must be PrivacyScrubber-clean.
     */
    suspend fun sendCrash(payload: JSONObject): String = post("/crash", payload.toString())

    // --- Search & Web Tools ---
    suspend fun webSearch(query: String): String = post("/tools/search", JSONObject().put("query", query).toString())

    // --- Market Data & Position Monitoring ---
    suspend fun getMarketQuote(symbol: String): String = get("/market/quote?symbol=${eq(symbol)}")
    suspend fun getMarketBatch(symbols: List<String>): String = post("/market/batch", JSONObject().put("symbols", JSONArray(symbols)).toString())
    suspend fun analyzeMarket(params: JSONObject): String = post("/enhanced/analyze", params.toString())
    suspend fun placeTrade(order: JSONObject): String = post("/trade", order.toString())

    suspend fun getPositions(): String = get("/positions")
    suspend fun getPositionsStatus(): String = get("/positions/status")
    suspend fun resolvePosition(id: String, outcome: String, closePrice: Double = 0.0, note: String = ""): String =
        post("/positions/resolve", JSONObject().apply {
            put("id", id)
            put("outcome", outcome)
            if (closePrice > 0) put("close_price", closePrice)
            if (note.isNotBlank()) put("note", note)
        }.toString())

    // --- Memory & Trade Journaling ---
    suspend fun addTradeMemory(entry: JSONObject): String = post("/memory/trade", entry.toString())
    suspend fun getTradeMemory(symbol: String): String = get("/memory/trade?symbol=${eq(symbol)}")

    // --- Utilities ---
    suspend fun getWeather(city: String): String = get("/weather?city=${eq(city)}")

    // Server only speaks JSON on /transcribe — multipart form posts are
    // rejected, so voice notes are base64'd into the JSON contract instead.
    suspend fun transcribeAudio(audioBytes: ByteArray): String = withContext(Dispatchers.IO) {
        transcribeBase64(android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP))
    }

    suspend fun transcribeBase64(base64Audio: String): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("audio_base64", base64Audio)
            // VoiceEngine records 16 kHz mono PCM wrapped as WAV — say so, or the
            // server's STT tries audio/webm and Whisper/Voxtral reject it.
            put("mime_type", "audio/wav")
        }
        post("/transcribe", payload.toString())
    }

    // --- Strategy Engine (engine.js only) ---
    suspend fun getSystemsStatus(): String = get("/systems/status")

    // --- Billing, Wallet & Trials (server binds user_id to this app's key) ---
    suspend fun billingTiers(): String = get("/billing/tiers")
    suspend fun billingPayInfo(): String = get("/billing/pay-info")
    suspend fun billingStatus(): String = get("/billing/status")
    suspend fun billingSubscribe(tier: String, method: String): String = post("/billing/subscribe",
        JSONObject().put("tier", tier).put("method", method).toString())
    suspend fun billingTopupCredits(amountKobo: Long, method: String): String = post("/billing/topup-credits",
        JSONObject().put("amount_kobo", amountKobo).put("method", method).toString())
    suspend fun billingCredits(): String = get("/billing/credits")
    suspend fun billingSubmit(paymentId: String, passcode: String, senderName: String, txid: String = "", network: String = ""): String = post("/billing/submit-reference",
        JSONObject().put("payment_id", paymentId).put("passcode", passcode)
            .put("sender_name", senderName).put("txid", txid).put("network", network).toString())
    suspend fun billingRedeem(code: String): String = post("/billing/redeem",
        JSONObject().put("code", code).toString())
    suspend fun walletBalance(): String = get("/wallet/balance")
    suspend fun walletTopup(amountKobo: Long, method: String): String = post("/wallet/topup",
        JSONObject().put("amount_kobo", amountKobo).put("method", method).toString())
    suspend fun walletWithdraw(bankName: String, accountNumber: String, accountName: String, amountKobo: Long): String = post("/wallet/withdraw",
        JSONObject().put("bank_name", bankName).put("account_number", accountNumber)
            .put("account_name", accountName).put("amount_kobo", amountKobo).toString())
    suspend fun walletWithdrawals(): String = get("/wallet/withdrawals")
    suspend fun postFeedback(message: String, rating: Int): String = post("/feedback",
        JSONObject().put("message", message).put("rating", rating).toString())

    // --- Creator console (separate admin token — never the app token) ---
    // The creator pastes ADMIN_TOKEN once in the Creator console; it lives in
    // app-private prefs and is only ever sent to /admin/* endpoints.
    fun adminAuth(): String = try {
        com.frit.app.core.AdminStore.get()
    } catch (_: Exception) { "" }

    suspend fun adminInbox(): String = adminGet("/admin/inbox")
    suspend fun adminModels(): String = adminGet("/admin/models")
    suspend fun adminSetModel(key: String, value: String): String = adminPost("/admin/models",
        JSONObject().put("key", key).put("value", value).toString())
    suspend fun catalogEndpoints(): String = get("/models/endpoints")
    // Generic console runner: any catalog endpoint, either credential.
    suspend fun appRaw(method: String, path: String, body: String): String =
        rawCall(method, path, body, auth())
    suspend fun adminRaw(method: String, path: String, body: String): String =
        rawCall(method, path, body, adminAuth())
    suspend fun adminMint(tier: String, days: Int, count: Int): String = adminPost("/billing/mint",
        JSONObject().put("tier", tier).put("days", days).put("count", count).toString())
    suspend fun adminApprovePayment(paymentId: String): String = adminPost("/billing/approve",
        JSONObject().put("payment_id", paymentId).toString())
    suspend fun adminApproveWithdrawal(id: String, note: String = ""): String = adminPost("/admin/withdrawals/approve",
        JSONObject().put("id", id).put("admin_note", note).toString())
    suspend fun adminDeclineWithdrawal(id: String, note: String = ""): String = adminPost("/admin/withdrawals/decline",
        JSONObject().put("id", id).put("admin_note", note).toString())

    private suspend fun adminGet(endpoint: String): String = authedCall(endpoint, null, adminAuth())
    private suspend fun adminPost(endpoint: String, json: String): String = authedCall(endpoint, json, adminAuth())

    // Generic runner for the auto-populating endpoint catalog: any HTTP
    // method, optional JSON body (GET/HEAD never send one). Every call still
    // carries this install's user_id via withUid().
    private suspend fun rawCall(method: String, endpoint: String, json: String?, token: String): String = withContext(Dispatchers.IO) {
        if (BASE_URL.isBlank()) return@withContext "{\"status\":\"error\",\"message\":\"BASE_URL not set\"}"
        if (token.isBlank()) return@withContext "{\"status\":\"error\",\"code\":401,\"message\":\"Token not set.\"}"
        val url = if (BASE_URL.startsWith("http")) "$BASE_URL${withUid(endpoint)}" else "https://$BASE_URL${withUid(endpoint)}"
        val m = method.uppercase()
        val builder = Request.Builder().url(url).addHeader("Authorization", "Bearer $token")
        if (m == "GET" || m == "HEAD") builder.method(m, null)
        else builder.method(m, (json ?: "{}").toRequestBody(JSON))
        try {
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) return@withContext errorJson(response.code, body)
                body
            }
        } catch (e: Exception) {
            "{\"status\":\"error\",\"message\":\"${friendlyNetError(e)}\"}"
        }
    }

    // --- Owner endpoint console (owner tier gate enforced in UI) ---
    suspend fun sysHealth(): String = get("/health")
    suspend fun modelFreeHealth(): String = get("/models/free-health")
    suspend fun strategyStatus(): String = get("/strategy/status")
    suspend fun marketAnalyze(symbol: String): String = post("/enhanced/analyze",
        JSONObject().put("symbol", symbol).toString())
    suspend fun positionsList(): String = get("/positions")
    suspend fun creativeJobs(): String = get("/creative/jobs")
    suspend fun adminSnapshots(): String = adminGet("/admin/snapshots")
    suspend fun adminLedgerVerify(): String = adminGet("/admin/ledger/verify")
    suspend fun adminBackupNow(): String = adminPost("/admin/backup", "{}")
    suspend fun feedbackAll(): String = adminGet("/feedback")

    private suspend fun authedCall(endpoint: String, json: String?, token: String): String = withContext(Dispatchers.IO) {
        if (BASE_URL.isBlank()) return@withContext "{\"status\":\"error\",\"message\":\"BASE_URL not set\"}"
        if (token.isBlank()) return@withContext "{\"status\":\"error\",\"code\":401,\"message\":\"Admin token not set. Open Creator console and paste it.\"}"
        val url = if (BASE_URL.startsWith("http")) "$BASE_URL$endpoint" else "https://$BASE_URL$endpoint"
        val builder = Request.Builder().url(url).addHeader("Authorization", "Bearer $token")
        if (json != null) builder.post(json.toRequestBody(JSON)) else builder.get()
        try {
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) return@withContext errorJson(response.code, body)
                body
            }
        } catch (e: Exception) {
            "{\"status\":\"error\",\"message\":\"${friendlyNetError(e)}\"}"
        }
    }

    // --- Sandbox Code Execution ---
    suspend fun runSandboxCode(language: String, code: String, stdin: String = ""): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("language", language)
            put("code", code)
            put("stdin", stdin)
        }
        post("/sandbox/run", payload.toString())
    }

    // --- Creative Suite ---
    suspend fun generateCreativeImage(prompt: String, stylePreset: String?, aspectRatio: String?, n: Int, referenceImage: String? = null): String =
        post("/creative-suite/images/generate", JSONObject().apply {
            put("prompt", prompt)
            if (stylePreset != null) put("style_preset", stylePreset)
            if (aspectRatio != null) put("aspect_ratio", aspectRatio)
            put("n", n)
            if (referenceImage != null) put("reference_images", org.json.JSONArray().put(referenceImage))
        }.toString())

    suspend fun editCreativeImage(imageBase64: String, operations: JSONArray): String =
        post("/creative-suite/images/edit", JSONObject().apply {
            put("image", imageBase64)
            put("operations", operations)
        }.toString())

    suspend fun removeBackground(imageBase64: String): String =
        post("/creative-suite/images/remove-background", JSONObject().put("image", imageBase64).toString())

    suspend fun retouchImage(imageBase64: String, options: JSONObject): String =
        post("/creative-suite/images/retouch", JSONObject().apply {
            put("image", imageBase64)
            put("options", options)
        }.toString())

    suspend fun styleTransfer(imageBase64: String, style: String): String =
        post("/creative-suite/images/style-transfer", JSONObject().apply {
            put("image", imageBase64)
            put("style", style)
        }.toString())

    suspend fun upscaleImage(imageBase64: String, scale: Int): String =
        post("/creative-suite/images/upscale", JSONObject().apply {
            put("image", imageBase64)
            put("scale", scale)
        }.toString())

    suspend fun generativeFill(imageBase64: String, maskBase64: String, prompt: String): String =
        post("/creative-suite/images/generative-fill", JSONObject().apply {
            put("image", imageBase64)
            put("mask", maskBase64)
            put("prompt", prompt)
        }.toString())

    suspend fun batchEdit(images: JSONArray, operation: String, params: JSONObject): String =
        post("/creative-suite/images/batch", JSONObject().apply {
            put("images", images)
            put("operation", operation)
            put("params", params)
        }.toString())

    suspend fun getBatchStatus(jobId: String): String =
        get("/creative-suite/images/batch/${seg(jobId)}/status")

    suspend fun generateVideo(prompt: String, duration: Int?, resolution: String?, aspectRatio: String?): String =
        post("/creative-suite/videos/generate", JSONObject().apply {
            put("prompt", prompt)
            if (duration != null) put("duration", duration)
            if (resolution != null) put("resolution", resolution)
            if (aspectRatio != null) put("aspect_ratio", aspectRatio)
        }.toString())

    suspend fun generateSubtitles(audioBase64: String, language: String): String =
        post("/creative-suite/videos/subtitles", JSONObject().apply {
            put("audio", audioBase64)
            put("language", language)
        }.toString())

    // Video input: server extracts the audio track via sandbox ffmpeg before STT,
    // so the bytes must go under "video", not "audio".
    suspend fun generateSubtitlesFromVideo(videoBase64: String, language: String): String =
        post("/creative-suite/videos/subtitles", JSONObject().apply {
            put("video", videoBase64)
            put("language", language)
        }.toString())

    suspend fun translateSubtitles(subtitles: JSONArray, targetLanguage: String): String =
        post("/creative-suite/videos/translate-subtitles", JSONObject().apply {
            put("subtitles", subtitles)
            put("target_language", targetLanguage)
        }.toString())

    suspend fun storyboard(script: String, style: String?, numFrames: Int): String =
        post("/creative-suite/storyboard", JSONObject().apply {
            put("script", script)
            if (style != null) put("style", style)
            put("num_frames", numFrames)
        }.toString())

    suspend fun getTemplates(category: String?, platform: String?): String =
        get("/creative-suite/templates?category=${eq(category ?: "")}&platform=${eq(platform ?: "")}")

    suspend fun applyTemplate(templateId: String, assets: JSONArray): String =
        post("/creative-suite/templates/$templateId/apply", JSONObject().put("assets", assets).toString())

    suspend fun getStylePresets(): String = get("/creative-suite/style-presets")

    suspend fun suggestPrompts(idea: String, context: String?): String =
        post("/creative-suite/prompt-suggest", JSONObject().apply {
            put("idea", idea)
            if (context != null) put("context", context)
        }.toString())

    suspend fun enhancePrompt(prompt: String, context: String?): String =
        post("/creative-suite/prompt-enhance", JSONObject().apply {
            put("prompt", prompt)
            if (context != null) put("context", context)
        }.toString())

    suspend fun getRecommendations(imageBase64: String, type: String): String =
        post("/creative-suite/recommend", JSONObject().apply {
            put("image", imageBase64)
            put("type", type)
        }.toString())

    suspend fun createProject(name: String, type: String, metadata: JSONObject?): String =
        post("/creative-suite/projects", JSONObject().apply {
            put("name", name)
            put("type", type)
            if (metadata != null) put("metadata", metadata)
        }.toString())

    suspend fun getProjects(): String = get("/creative-suite/projects")

    suspend fun getProject(projectId: String): String = get("/creative-suite/projects/$projectId")

    suspend fun updateProject(projectId: String, updates: JSONObject): String =
        rawCall("PUT", "/creative-suite/projects/$projectId", updates.toString(), auth())

    suspend fun deleteProject(projectId: String): String =
        rawCall("DELETE", "/creative-suite/projects/$projectId", "{}", auth())

    suspend fun addAssetToProject(projectId: String, asset: JSONObject): String =
        post("/creative-suite/projects/$projectId/assets", asset.toString())

    suspend fun syncProject(projectId: String, deviceId: String, syncData: JSONObject): String =
        post("/creative-suite/projects/$projectId/sync", JSONObject().apply {
            put("device_id", deviceId)
            put("sync_data", syncData)
        }.toString())

    suspend fun getSyncState(projectId: String): String =
        get("/creative-suite/projects/$projectId/sync")

    suspend fun addCollaborator(projectId: String, userId: String, role: String): String =
        post("/creative-suite/projects/$projectId/collaborators", JSONObject().apply {
            put("user_id", userId)
            put("role", role)
        }.toString())

    suspend fun getCinemaOptions(): String = get("/creative-suite/cinema/options")

    suspend fun cinemaGenerate(prompt: String, kind: String, director: JSONObject, references: JSONArray, aspectRatio: String?, duration: Int?, resolution: String?, brandkitId: String? = null): String =
        post("/creative-suite/cinema/generate", JSONObject().apply {
            put("prompt", prompt)
            put("kind", kind)
            put("director", director)
            put("references", references)
            if (aspectRatio != null) put("aspect_ratio", aspectRatio)
            if (duration != null) put("duration", duration)
            if (resolution != null) put("resolution", resolution)
            // F-13: the brand lock is applied server-side; the client only
            // sends the kit id.
            if (brandkitId != null) put("brandkit_id", brandkitId)
        }.toString())

    suspend fun director(brief: String, referenceVideo: String?, numShots: Int, autoCorrect: Boolean, aspectRatio: String): String =
        post("/creative-suite/director", JSONObject().apply {
            put("brief", brief)
            if (referenceVideo != null) put("reference_video", referenceVideo)
            put("num_shots", numShots)
            put("auto_correct", autoCorrect)
            put("aspect_ratio", aspectRatio)
        }.toString())

    suspend fun getCinemaRecipes(): String = get("/creative-suite/cinema/recipes")

    suspend fun autopilot(script: String, perShotSecs: Int, aspectRatio: String? = null): String =
        post("/creative-suite/autopilot", JSONObject().apply {
            put("script", script)
            put("per_shot_secs", perShotSecs)
            if (aspectRatio != null) put("aspect_ratio", aspectRatio)
        }.toString())

    suspend fun createBrandKit(name: String, colors: JSONArray, font: String?, logoUrl: String?, palette: String?, tone: String?): String =
        post("/creative-suite/brandkit", JSONObject().apply {
            put("name", name)
            put("colors", colors)
            if (font != null) put("font", font)
            if (logoUrl != null) put("logo_url", logoUrl)
            if (palette != null) put("palette", palette)
            if (tone != null) put("tone", tone)
        }.toString())

    suspend fun getBrandKits(): String = get("/creative-suite/brandkit")

    suspend fun deleteBrandKit(id: String): String =
        post("/creative-suite/brandkit/$id/delete", "{}")

    suspend fun getDesignTemplates(): String = get("/creative-suite/design/templates")

    suspend fun design(template: String?, headline: String, body: String?, cta: String?, palette: String?, brandkitId: String?, logoUrl: String?, craft: String, styleRefs: JSONArray, variations: Int, aspectRatio: String? = null, font: String? = null): String =
        post("/creative-suite/design", JSONObject().apply {
            put("headline", headline)
            if (template != null) put("template", template)
            if (body != null) put("body", body)
            if (cta != null) put("cta", cta)
            if (palette != null) put("palette", palette)
            if (brandkitId != null) put("brandkit_id", brandkitId)
            if (logoUrl != null) put("logo_url", logoUrl)
            put("craft", craft)
            put("style_refs", styleRefs)
            put("variations", variations)
            if (aspectRatio != null) put("aspect_ratio", aspectRatio)
            if (font != null) put("font", font)
        }.toString())

    /**
     * Design Agent (Week 6): research visual references -> one detailed brief
     * (layout, alignment, palette, fonts/sizes, elements, background) -> render
     * -> verify the render against the brief. Long-running (10-90s), which the
     * shared 150s read timeout covers. Returns the whole trail, not just an image.
     */
    suspend fun designAgent(
        goal: String,
        headline: String? = null,
        body: String? = null,
        cta: String? = null,
        palette: String? = null,
        brandkitId: String? = null,
        aspectRatio: String? = null,
        font: String? = null,
        inspiration: Boolean = true,
        verify: Boolean = true,
    ): String = post("/creative-suite/design/agent", JSONObject().apply {
        put("goal", goal)
        if (headline != null) put("headline", headline)
        if (body != null) put("body", body)
        if (cta != null) put("cta", cta)
        if (palette != null) put("palette", palette)
        if (brandkitId != null) put("brandkit_id", brandkitId)
        if (aspectRatio != null) put("aspect_ratio", aspectRatio)
        if (font != null) put("font", font)
        put("inspiration", inspiration)
        put("verify", verify)
    }.toString())

    suspend fun editVideoOps(video: String?, audio: String?, subtitles: JSONArray?, clips: JSONArray?, operations: JSONArray): String =
        post("/creative-suite/videos/edit", JSONObject().apply {
            if (video != null) put("video", video)
            if (audio != null) put("audio", audio)
            if (subtitles != null) put("subtitles", subtitles)
            if (clips != null) put("clips", clips)
            put("operations", operations)
        }.toString())

    suspend fun getVideoModels(): String = get("/creative-suite/videos/models")

    /** Poll a provider video job (the id inside provider_job, not our vid_ id). */
    suspend fun videoStatus(providerId: String): String = get("/videos/status/${seg(providerId)}")

    /** Download a finished provider video into app cache; null if not ready/failed. */
    suspend fun downloadVideoToCache(ctx: android.content.Context, providerId: String): java.io.File? = withContext(Dispatchers.IO) {
        try {
            val ep = "/videos/content/${seg(providerId)}"
            val url = if (BASE_URL.startsWith("http")) "$BASE_URL${withUid(ep)}" else "https://$BASE_URL${withUid(ep)}"
            val req = Request.Builder().url(url).addHeader("Authorization", "Bearer ${auth()}").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.e(TAG, "video download HTTP ${resp.code}"); return@withContext null }
                val out = java.io.File(ctx.cacheDir, "frit_video_${seg(providerId).takeLast(24)}.mp4")
                resp.body?.byteStream()?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return@withContext null
                if (out.length() > 0) out else null
            }
        } catch (e: Exception) { Log.e(TAG, "video download failed", e); null }
    }

    suspend fun getLipSyncStatus(jobId: String): String = get("/creative-suite/lipsync/${seg(jobId)}/status")

    suspend fun katana(command: String, video: String?, audio: String?, language: String?): String =
        post("/creative-suite/katana", JSONObject().apply {
            put("command", command)
            if (video != null) put("video", video)
            if (audio != null) put("audio", audio)
            if (language != null) put("language", language)
        }.toString())

    suspend fun genjutsu(mode: String, video: String, references: JSONArray, description: String?, preset: String?): String =
        post("/creative-suite/genjutsu", JSONObject().apply {
            put("mode", mode)
            put("video", video)
            put("references", references)
            if (description != null) put("description", description)
            if (preset != null) put("preset", preset)
        }.toString())

    suspend fun createElement(name: String, type: String, references: JSONArray, metadata: JSONObject?): String =
        post("/creative-suite/elements", JSONObject().apply {
            put("name", name)
            put("type", type)
            put("references", references)
            if (metadata != null) put("metadata", metadata)
        }.toString())

    suspend fun getElements(type: String?): String =
        get("/creative-suite/elements" + (if (type != null) "?type=$type" else ""))

    suspend fun deleteElement(id: String): String =
        post("/creative-suite/elements/$id/delete", "{}")

    suspend fun influencer(attributes: JSONObject, posts: Int): String =
        post("/creative-suite/influencer", JSONObject().apply {
            put("attributes", attributes)
            put("posts", posts)
        }.toString())

    suspend fun ads(brief: String, product: String?, productImage: String?): String =
        post("/creative-suite/ads", JSONObject().apply {
            put("brief", brief)
            if (product != null) put("product", product)
            if (productImage != null) put("product_image", productImage)
        }.toString())

    suspend fun lipsync(video: String, audio: String, language: String): String =
        post("/creative-suite/lipsync", JSONObject().apply {
            put("mode", "dub")
            put("video", video)
            put("audio", audio)
            put("language", language)
        }.toString())

    suspend fun lipsyncAvatar(image: String, audio: String, language: String, aspectRatio: String): String =
        post("/creative-suite/lipsync", JSONObject().apply {
            put("mode", "avatar")
            put("image", image)
            put("audio", audio)
            put("language", language)
            put("aspect_ratio", aspectRatio)
        }.toString())

    suspend fun exportAsset(assetUrl: String, platform: String, options: JSONObject?): String =
        post("/creative-suite/export", JSONObject().apply {
            put("asset_url", assetUrl)
            put("platform", platform)
            if (options != null) put("options", options)
        }.toString())

    /**
     * Wrap a non-2xx response as VALID JSON. The old code string-interpolated the raw body into
     * "message", but server errors are themselves JSON ({"error":"..."}) so the nested quotes
     * broke parsing and every 400/402/429 (wrong passcode, bad code, feature_locked,
     * insufficient_credits) surfaced to the UI as a generic "Network error".
     * Server fields are passed through (code = "feature_locked" / "insufficient_credits",
     * balance, needed). status/http/message/error are always present.
     */
    private fun errorJson(httpCode: Int, body: String): String {
        val o = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        val msg = o.optString("error", o.optString("message", body.take(300)))
        o.put("status", "error")
        o.put("http", httpCode)
        if (!o.has("code")) o.put("code", httpCode)
        o.put("message", msg)
        if (!o.has("error")) o.put("error", msg)
        return o.toString()
    }

    // --- Internal Helpers ---
    // Every call carries this install's stable id as user_id (query string —
    // the server's boundUser() picks it up for both GET and POST). Key-based
    // callers are unaffected (server binds to the key instead). This is what
    // keeps each device's subscription tier, wallet and quotas on its own row.
    private fun withUid(endpoint: String): String {
        val uid = try { TokenStore.installId() } catch (_: Exception) { "" }
        if (uid.isBlank()) return endpoint
        val sep = if ("?" in endpoint) "&" else "?"
        return "$endpoint${sep}user_id=${eq(uid)}"
    }

    /** URL-encode a query value (symbols, cities, session ids are user-influenced). */
    private fun eq(v: String?): String = try {
        java.net.URLEncoder.encode(v ?: "", "UTF-8")
    } catch (_: Exception) { v ?: "" }

    /** Sanitize a server-issued path id to URL-safe chars. */
    private fun seg(v: String?): String = (v ?: "").filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }

    /** Master (baked) token — the shared verifier every install carries. */
    private fun masterToken(): String {
        return try { TokenStore.get().ifBlank { FritConfig.AUTH_TOKEN } } catch (_: Exception) {
            try { FritConfig.AUTH_TOKEN } catch (_: Exception) { "" }
        }
    }

    /**
     * One-shot 401 recovery: the stored per-install key dies on every server
     * redeploy (ephemeral SQLite wipes api_keys). When the used token was NOT
     * the master token, clear the dead key, best-effort re-register, and let
     * the caller retry once with [masterToken]. Returns true if caller should retry.
     */
    private suspend fun recoverFrom401(usedToken: String): Boolean {
        val master = masterToken()
        if (usedToken.isBlank() || (master.isNotBlank() && usedToken == master)) return false
        try { TokenStore.setInstallKey("") } catch (_: Exception) { }
        installRegAttempted = false
        try { ensureInstallKey() } catch (_: Exception) { }
        return masterToken().isNotBlank()
    }

    private suspend fun post(endpoint: String, json: String): String = withContext(Dispatchers.IO) {
        if (BASE_URL.isBlank()) {
            Log.e(TAG, "BASE_URL is blank, cannot POST to $endpoint")
            return@withContext "{\"status\":\"error\",\"message\":\"BASE_URL not set\"}"
        }
        if (!isAuthorized()) {
            Log.e(TAG, "No API token configured — refusing request")
            return@withContext "{\"status\":\"error\",\"code\":401,\"message\":\"API token not set. Add it in Settings.\"}"
        }
        val body = json.toRequestBody(JSON)
        val url = if (BASE_URL.startsWith("http")) "$BASE_URL${withUid(endpoint)}" else "https://$BASE_URL${withUid(endpoint)}"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${auth()}")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "POST Error $response: $responseBody")
                    if (response.code == 401) {
                        val used = request.header("Authorization")?.removePrefix("Bearer ") ?: ""
                        if (recoverFrom401(used)) {
                            val retry = Request.Builder()
                                .url(url)
                                .addHeader("Authorization", "Bearer ${auth()}")
                                .post(json.toRequestBody(JSON))
                                .build()
                            client.newCall(retry).execute().use { retryResp ->
                                val retryBody = retryResp.body?.string() ?: ""
                                if (!retryResp.isSuccessful) {
                                    Log.e(TAG, "POST Retry Error $retryResp: $retryBody")
                                    return@withContext errorJson(retryResp.code, retryBody)
                                }
                                return@withContext retryBody
                            }
                        }
                    }
                    return@withContext errorJson(response.code, responseBody)
                }
                responseBody
            }
        } catch (e: Exception) {
            Log.e(TAG, "POST Exception at $endpoint", e)
            "{\"status\":\"error\",\"message\":\"${friendlyNetError(e)}\"}"
        }
    }

    private suspend fun postMultipart(endpoint: String, body: RequestBody): String = withContext(Dispatchers.IO) {
        if (BASE_URL.isBlank()) return@withContext "{\"error\":\"BASE_URL not set\"}"
        val url = if (BASE_URL.startsWith("http")) "$BASE_URL${withUid(endpoint)}" else "https://$BASE_URL${withUid(endpoint)}"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${auth()}")
            .post(body)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                response.body?.string() ?: ""
            }
        } catch (e: Exception) {
            "{\"error\":\"${friendlyNetError(e)}\"}"
        }
    }

    private suspend fun get(endpoint: String): String = withContext(Dispatchers.IO) {
        if (BASE_URL.isBlank()) return@withContext "{\"status\":\"error\",\"message\":\"BASE_URL not set\"}"
        if (!isAuthorized()) {
            Log.e(TAG, "No API token configured — refusing request")
            return@withContext "{\"status\":\"error\",\"code\":401,\"message\":\"API token not set. Add it in Settings.\"}"
        }
        val url = if (BASE_URL.startsWith("http")) "$BASE_URL${withUid(endpoint)}" else "https://$BASE_URL${withUid(endpoint)}"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${auth()}")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "GET Error $response: $responseBody")
                    if (response.code == 401) {
                        val used = request.header("Authorization")?.removePrefix("Bearer ") ?: ""
                        if (recoverFrom401(used)) {
                            val retry = Request.Builder()
                                .url(url)
                                .addHeader("Authorization", "Bearer ${auth()}")
                                .get()
                                .build()
                            client.newCall(retry).execute().use { retryResp ->
                                val retryBody = retryResp.body?.string() ?: ""
                                if (!retryResp.isSuccessful) {
                                    Log.e(TAG, "GET Retry Error $retryResp: $retryBody")
                                    return@withContext errorJson(retryResp.code, retryBody)
                                }
                                return@withContext retryBody
                            }
                        }
                    }
                    return@withContext errorJson(response.code, responseBody)
                }
                responseBody
            }
        } catch (e: Exception) {
            Log.e(TAG, "GET Exception at $endpoint", e)
            "{\"status\":\"error\",\"message\":\"${friendlyNetError(e)}\"}"
        }
    }
}