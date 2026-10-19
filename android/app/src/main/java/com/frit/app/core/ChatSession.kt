package com.frit.app.core

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import com.frit.app.data.Artifact
import com.frit.app.data.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

import kotlinx.coroutines.*

/**
 * ChatSession — a single, process-wide chat state holder shared by the overlay
 * service AND the full MainActivity. Because both UI surfaces read/write the
 * exact same instance, expanding the overlay into the full app (or closing the
 * overlay service) never loses the conversation. Messages are also persisted to
 * SharedPreferences so they survive a service restart / process death.
 */
object ChatSession {
    private const val PREFS_NAME = "frit_chat_session"
    private const val KEY_MESSAGES = "messages"

    private lateinit var prefs: SharedPreferences
    private var loaded = false
    private val sessionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var typingJob: Job? = null

    val messages = mutableStateListOf<ChatMessage>()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!loaded) {
            restore()
            loaded = true
        }
    }

    fun clear() {
        typingJob?.cancel()
        messages.clear()
        persist()
    }

    fun addUser(text: String, imageBase64: String? = null, replyQuotes: List<com.frit.app.data.ReplyQuote> = emptyList()) {
        typingJob?.cancel()
        messages.add(ChatMessage("user", text, imageBase64, replyQuotes = replyQuotes))
        persist()
    }

    fun addAssistant(text: String, artifacts: List<Artifact> = emptyList(), animated: Boolean = true, onComplete: (() -> Unit)? = null) {
        if (!animated || text.length < 5) {
            typingJob?.cancel()
            messages.add(ChatMessage("assistant", text, artifacts = artifacts, isStreaming = false))
            persist()
            onComplete?.invoke()
            return
        }

        typingJob?.cancel()
        val msgIndex = messages.size
        messages.add(ChatMessage("assistant", "", artifacts = artifacts, isStreaming = true))

        typingJob = sessionScope.launch {
            val stepSize = 3
            var currentLength = 0
            while (isActive && currentLength < text.length) {
                currentLength = (currentLength + stepSize).coerceAtMost(text.length)
                val chunk = text.substring(0, currentLength)
                val isDone = currentLength >= text.length
                if (msgIndex in messages.indices) {
                    messages[msgIndex] = ChatMessage("assistant", chunk, artifacts = artifacts, isStreaming = !isDone)
                }
                delay(16) // ~60fps typewriter preview
            }
            if (msgIndex in messages.indices) {
                messages[msgIndex] = ChatMessage("assistant", text, artifacts = artifacts, isStreaming = false)
            }
            persist()
            onComplete?.invoke()
        }
    }

    fun replaceUser(index: Int, text: String, imageBase64: String? = null) {
        if (index in messages.indices) {
            messages[index] = ChatMessage("user", text, imageBase64)
            persist()
        }
    }

    fun removeAt(index: Int) {
        if (index in messages.indices) {
            messages.removeAt(index)
            persist()
        }
    }

    fun snapshot(): List<ChatMessage> = messages.toList()

    /**
     * Context-window saver: the 131K Groq window is shared with screen text +
     * tools, so long chats must NOT go in full. Last 6 messages verbatim,
     * everything older condensed to one-line "role: snippet" records. This is
     * what actually fixes "the AI forgets" — before, takeLast(10) full
     * messages either overflowed (truncated server-side, losing the MIDDLE)
     * or starved the screen/tools budget.
     */
    fun compactHistory(full: Int = 6, snippetChars: Int = 160): List<ChatMessage> {
        val all = messages.toList()
        if (all.size <= full + 2) return all
        val older = all.dropLast(full).map { m ->
            val clean = m.content.replace(Regex("\\s+"), " ").trim().take(snippetChars)
            ChatMessage(m.role, "[earlier] $clean")
        }
        return older + all.takeLast(full)
    }

    private fun persist() {
        if (!::prefs.isInitialized) return
        try {
            val arr = JSONArray()
            messages.forEach { arr.put(toJson(it)) }
            prefs.edit().putString(KEY_MESSAGES, arr.toString()).apply()
        } catch (e: Exception) {
            // Never let a persistence error break the chat
        }
    }

    private fun restore() {
        if (!::prefs.isInitialized) return
        try {
            val raw = prefs.getString(KEY_MESSAGES, null) ?: return
            val arr = JSONArray(raw)
            messages.clear()
            for (i in 0 until arr.length()) {
                fromJson(arr.getJSONObject(i))?.let { messages.add(it) }
            }
        } catch (e: Exception) {
            messages.clear()
        }
    }

    private fun toJson(msg: ChatMessage): JSONObject {
        val o = JSONObject().apply {
            put("role", msg.role)
            put("content", msg.content)
            msg.imageBase64?.let { put("image", it) }
        }
        if (msg.replyQuotes.isNotEmpty()) {
            val qs = JSONArray()
            msg.replyQuotes.forEach { q ->
                qs.put(JSONObject().apply {
                    put("role", q.role)
                    put("text", q.text)
                })
            }
            o.put("quotes", qs)
        }
        if (msg.artifacts.isNotEmpty()) {
            val arts = JSONArray()
            msg.artifacts.forEach { a ->
                arts.put(JSONObject().apply {
                    put("name", a.name)
                    put("mime", a.mime)
                    put("size", a.size)
                    a.base64?.let { put("base64", it) }
                    a.content?.let { put("content", it) }
                })
            }
            o.put("artifacts", arts)
        }
        return o
    }

    private fun fromJson(o: JSONObject): ChatMessage? {
        return try {
            val artifacts = mutableListOf<Artifact>()
            val arts = o.optJSONArray("artifacts")
            if (arts != null) {
                for (i in 0 until arts.length()) {
                    val a = arts.getJSONObject(i)
                    artifacts.add(Artifact(
                        name = a.optString("name", "file"),
                        mime = a.optString("mime", "application/octet-stream"),
                        size = a.optLong("size", 0),
                        base64 = if (a.has("base64") && !a.isNull("base64")) a.getString("base64") else null,
                        content = if (a.has("content") && !a.isNull("content")) a.getString("content") else null
                    ))
                }
            }
            ChatMessage(
                role = o.optString("role", "assistant"),
                content = o.optString("content", ""),
                imageBase64 = if (o.has("image") && !o.isNull("image")) o.getString("image") else null,
                artifacts = artifacts,
                replyQuotes = o.optJSONArray("quotes")?.let { qs ->
                    buildList {
                        for (i in 0 until qs.length()) {
                            val q = qs.optJSONObject(i) ?: continue
                            add(com.frit.app.data.ReplyQuote(
                                role = q.optString("role", "assistant"),
                                text = q.optString("text", "")
                            ))
                        }
                    }
                } ?: emptyList()
            )
        } catch (e: Exception) { null }
    }
}