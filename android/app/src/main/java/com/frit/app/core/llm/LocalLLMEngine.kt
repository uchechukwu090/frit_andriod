package com.frit.app.core.llm

import android.content.Context
import android.util.Log
import com.frit.app.core.LocalInferenceEngine
import com.frit.app.data.ChatMessage
import com.frit.app.data.NetworkClient
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * LocalLLMEngine — Runs Qwen3 1.7B Instruct on-device via llama.cpp.
 *
 * - 3GB RAM friendly (~1.6-1.8GB peak usage)
 * - Works fully offline
 * - Handles chat, Q&A, task planning, reasoning (native function-calling)
 * - Falls back to LocalInferenceEngine (pattern matching) for simple tasks
 * - Falls back to Mistral cloud for complex tasks when online
 *
 * Memory management:
 * - Model loaded on demand, unloaded after inactivity (configurable timeout)
 * - GGUF Q4_K_M quantization (~1.28GB file, ~1.6-1.8GB RAM at runtime)
 */
object LocalLLMEngine {
    private const val TAG = "LocalLLM"
    private const val MAX_CONTEXT = 4096
    private const val MAX_TOKENS = 768
    private const val TEMPERATURE = 0.6f
    private const val IDLE_UNLOAD_MINUTES = 10
    private var isInitialized = false
    @Volatile var lastLoadError: String? = null
        private set
    private var appContext: Context? = null
    private var modelPath: String? = null
    private var lastUseTime = 0L
    private var idleJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Qwen3 chat-template tokens (ChatML). Using Gemma's old tokens here would
    // make Qwen output garbage, so these are swapped wholesale.
    private const val BOS = "<|im_start|>"
    private const val END = "<|im_end|>"
    private const val SYSTEM = "system\n"
    private const val USER = "user\n"
    private const val MODEL = "assistant\n"

    fun init(context: Context) {
        appContext = context.applicationContext
        try {
            NativeLib.ensureLoaded()
            isInitialized = true
        } catch (e: Throwable) {
            Log.e(TAG, "Native library load failed. Local LLM will be disabled.", e)
            isInitialized = false
            return
        }

        // Start idle monitor
        idleJob = scope.launch {
            while (isActive) {
                if (NativeLib.isAvailable() && NativeLib.isLoaded()) {
                    val idle = System.currentTimeMillis() - lastUseTime
                    if (idle > IDLE_UNLOAD_MINUTES * 60 * 1000L) {
                        Log.i(TAG, "Unloading model after ${IDLE_UNLOAD_MINUTES}min idle")
                        unloadModel()
                    }
                }
                delay(60_000)
            }
        }
    }

    /**
     * Check if the LLM is available for use.
     */
    fun isAvailable(): Boolean {
        return isInitialized && isNativeAvailable() && modelPath != null
    }

    /**
     * Check if native library is compiled in.
     */
    fun isNativeAvailable(): Boolean {
        return try {
            NativeLib.ensureLoaded()
            NativeLib.isAvailable()
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Returns true if the model is currently loaded in memory.
     */
    fun isModelLoaded(): Boolean {
        return isNativeAvailable() && NativeLib.isLoaded()
    }

    /**
     * What actually happened when we tried to get the model running. The UI
     * needs more than a boolean: whether it was already on disk (no download
     * happened), which file, and how big it is.
     */
    data class LoadResult(
        val success: Boolean,
        val path: String? = null,
        val sizeMB: Long = 0L,
        val tier: String = "",
        val fromCache: Boolean = false,
        val error: String? = null,
    )

    /**
     * Load model (download first if needed).
     *
     * @param allowDownload false = use a cached GGUF only, never the network.
     *   Chat routing passes false so the app can never start a multi-hundred-MB
     *   download because the user typed a message; only the explicit Download
     *   button in Settings (via ModelDownloadService) downloads.
     */
    suspend fun loadModel(
        context: Context,
        onDownloadProgress: ((ModelManager.DownloadProgress) -> Unit)? = null,
        confirmMetered: (suspend (approxMB: Long) -> Boolean)? = null,
        allowDownload: Boolean = true
    ): LoadResult = withContext(Dispatchers.IO) {
        if (!NativeLib.isAvailable()) {
            Log.w(TAG, "Native lib not available — cannot load model")
            return@withContext LoadResult(false, error = "Native library not available in this build")
        }

        if (NativeLib.isLoaded()) {
            Log.d(TAG, "Model already loaded")
            val cached = ModelManager.findCachedModel(context)
            return@withContext LoadResult(
                success = true,
                path = modelPath,
                sizeMB = cached?.sizeMB ?: 0L,
                tier = cached?.spec?.filename ?: "",
                fromCache = true,
            )
        }

        try {
            // Get model path (downloads if needed)
            val resolved = ModelManager.getModelPathResult(context, onDownloadProgress, confirmMetered, allowDownload)
            modelPath = resolved.path

            // Load into memory
            Log.i(TAG, "Loading model from ${resolved.path} ...")
            val success = NativeLib.loadModel(resolved.path, MAX_CONTEXT)
            if (success) {
                lastUseTime = System.currentTimeMillis()
                Log.i(TAG, "Model loaded successfully! ${NativeLib.getModelInfo()}")
                LoadResult(
                    success = true,
                    path = resolved.path,
                    sizeMB = java.io.File(resolved.path).length() / 1_000_000,
                    tier = resolved.spec.filename,
                    fromCache = resolved.fromCache,
                )
            } else {
                val why = NativeLib.lastError().ifBlank { "llama.cpp could not load this model" }
                Log.e(TAG, "Failed to load model: $why")
                modelPath = null
                lastLoadError = why
                LoadResult(false, error = why)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
            modelPath = null
            lastLoadError = e.message ?: "Model load failed"
            LoadResult(false, error = lastLoadError)
        }
    }

    /**
     * Unload model from memory.
     */
    fun unloadModel() {
        if (NativeLib.isAvailable() && NativeLib.isLoaded()) {
            NativeLib.unloadModel()
            Log.i(TAG, "Model unloaded from memory")
        }
    }

    /**
     * Generate a response from the local LLM.
     *
     * @param messages Chat history
     * @param systemPrompt System prompt
     * @return Generated text or null if unavailable
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        systemPrompt: String = """
            You are FRIT, an autonomous Android AI. 
            TOOLS: 
            - {"tool": "open_app", "target": "name"}
            - {"tool": "tap_button", "target": "label"}
            - {"tool": "type_text", "target": "text"}
            - {"tool": "press_home"}
            - {"tool": "read_screen"}
            
            If you need to perform an action, respond ONLY with the JSON. 
            If you are just talking to the user, respond with text.
        """.trimIndent()
    ): String? = withContext(Dispatchers.IO) {
        if (!NativeLib.isAvailable() || !NativeLib.isLoaded()) {
            return@withContext null
        }

        lastUseTime = System.currentTimeMillis()

        // Build prompt using Gemma 3 chat template
        val prompt = buildGemmaPrompt(messages, systemPrompt)
        Log.d(TAG, "Prompt length: ${prompt.length} chars")

        try {
            val result = NativeLib.generate(prompt, MAX_TOKENS, TEMPERATURE)
            if (result.isBlank()) null else cleanQwenOutput(result)
        } catch (e: Exception) {
            Log.e(TAG, "Generation error", e)
            null
        }
    }

    /**
     * One-shot Q&A (no history).
     */
    suspend fun ask(question: String): String? {
        val msg = listOf(ChatMessage("user", question))
        return chat(msg)
    }

    /**
     * Route to local LLM if available, otherwise fall back.
     * Returns (response, usedLocalModel).
     */
    suspend fun route(
        text: String,
        history: List<ChatMessage>,
        preferLocal: Boolean = false
    ): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        // Try local LLM first if loaded or if offline
        val isOffline = !isOnline()
        if (isModelLoaded() || (preferLocal && isNativeAvailable())) {
            if (!isModelLoaded()) {
                // Cached file only — a chat message must never start a download.
                loadModel(appContext ?: return@withContext "" to false, allowDownload = false)
            }
            val response = chat(history + ChatMessage("user", text))
            if (response != null) {
                return@withContext response to true
            }
        }

        // Fallback to pattern matching for simple local tasks
        val localResult = LocalInferenceEngine.classify(text)
        if (localResult.intent != "cloud_chat" && localResult.confidence >= 0.75f) {
            val result = LocalInferenceEngine.executeLocal(localResult.intent, localResult.target, localResult.converse, text)
            return@withContext (result ?: "Could not execute action") to true
        }

        // Fallback to cloud (Mistral)
        if (!isOffline) {
            try {
                val cloudResult = com.frit.app.core.IntegrationCoordinator.chat(text, history)
                return@withContext cloudResult to false
            } catch (e: Exception) {
                Log.e(TAG, "Cloud fallback failed", e)
            }
        }

        // Last resort: try local LLM even if not loaded
        if (isNativeAvailable() && !isModelLoaded()) {
            loadModel(appContext ?: return@withContext "I'm offline and can't load the local model." to true, allowDownload = false)
            val response = chat(history + ChatMessage("user", text))
            if (response != null) return@withContext response to true
        }

        return@withContext "I'm offline and the local model isn't available. Please connect to the internet or wait for the model to finish downloading." to true
    }

    private fun buildGemmaPrompt(messages: List<ChatMessage>, systemPrompt: String): String {
        // Preferred: the chat template baked into the GGUF itself, so any model
        // the user imports (Qwen, Gemma, Llama, Phi...) is prompted correctly.
        val roles = ArrayList<String>()
        val contents = ArrayList<String>()
        roles.add("system"); contents.add(systemPrompt)
        for (msg in messages) {
            when (msg.role) {
                "user", "system" -> { roles.add("user"); contents.add(msg.content) }
                "assistant", "model" -> { roles.add("assistant"); contents.add(msg.content) }
            }
        }
        val templated = try {
            NativeLib.formatChat(roles.toTypedArray(), contents.toTypedArray())
        } catch (e: Throwable) { "" }
        val base = if (templated.isNotBlank()) templated else buildChatMl(roles, contents)
        // Qwen3 "thinks" by default, burning most of the token budget on a hidden
        // monologue. An empty think block turns that off for fast answers.
        return if (modelPath?.contains("qwen3", ignoreCase = true) == true &&
            base.trimEnd().endsWith("assistant")
        ) base + "<think>\n\n</think>\n\n" else base
    }

    /** ChatML fallback (Qwen family) for models whose GGUF carries no usable template. */
    private fun buildChatMl(roles: List<String>, contents: List<String>): String {
        val sb = StringBuilder()
        for (i in roles.indices) {
            sb.append(BOS).append(roles[i]).append("\n").append(contents[i]).append(END).append("\n")
        }
        sb.append(BOS).append(MODEL)
        return sb.toString()
    }

    /** Qwen3 reasoning models prepend a <think>…</think> block — strip it for display. */
    private fun cleanQwenOutput(raw: String): String {
        var out = raw.trim()
        if (out.startsWith("<|im_end|>")) out = out.removePrefix("<|im_end|>").trim()
        val thinkOpen = out.indexOf("<think>")
        if (thinkOpen >= 0) {
            val thinkClose = out.indexOf("</think>", thinkOpen)
            if (thinkClose >= 0) {
                out = out.substring(thinkClose + "</think>".length).trim()
            }
        }
        if (out.endsWith("<|im_end|>")) out = out.removeSuffix("<|im_end|>").trim()
        return out
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get download progress info for UI display.
     */
    fun getModelCached(context: Context): Boolean = ModelManager.isModelCached(context)
    fun getModelSize(context: Context): String {
        val bytes = ModelManager.getCachedSize(context)
        return if (bytes > 0) "${bytes / 1_000_000}MB" else "Not downloaded"
    }

    enum class ModelStatus { NATIVE_MISSING, NOT_DOWNLOADED, DOWNLOADED, LOADED }

    /** Human-readable picture of the on-device AI state — used by Settings. */
    fun modelStatus(context: Context): ModelStatus {
        return when {
            !isNativeAvailable() -> ModelStatus.NATIVE_MISSING
            isModelLoaded() -> ModelStatus.LOADED
            ModelManager.isModelCached(context) -> ModelStatus.DOWNLOADED
            else -> ModelStatus.NOT_DOWNLOADED
        }
    }

    fun statusLabel(context: Context): String {
        val name = try { ModelManager.cachedTierName(context) ?: ModelManager.pickSpec(context).filename } catch (_: Exception) { "model" }
        return when (modelStatus(context)) {
            ModelStatus.NATIVE_MISSING -> "On-device AI engine not present in this build (native lib missing — wrong ABI?)"
            ModelStatus.NOT_DOWNLOADED -> "$name never downloaded — needs internet once, then works fully offline"
            ModelStatus.DOWNLOADED -> "Model downloaded, not loaded in memory yet"
            ModelStatus.LOADED -> "$name loaded and ready (fully offline)"
        }
    }
}
