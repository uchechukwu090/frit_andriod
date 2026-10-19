package com.frit.app.core

import android.content.Context
import android.content.SharedPreferences
import com.frit.app.data.Artifact
import com.frit.app.data.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * ChatHistory — multi-conversation archive shared across the overlay and the
 * full app. Each saved chat has an id, a title (first user message or timestamp),
 * a starred flag, and the full list of ChatMessage entries.
 *
 * Lifecycle:
 *  - On app launch, if ChatSession already has messages, they are archived
 *    here and ChatSession is cleared so the user starts a fresh chat.
 *  - "New chat" archives the current session (if non-empty) and clears it.
 *  - Opening a saved chat archives the current session, then loads the
 *    selected one into ChatSession.
 *  - Star/favorite and delete operate on saved entries.
 */
object ChatHistory {
    private const val PREFS_NAME = "frit_chat_history"
    private const val KEY_LIST = "list"

    data class SavedChat(
        val id: String,
        val title: String,
        var starred: Boolean,
        val messages: List<ChatMessage>,
        val savedAt: Long
    )

    private lateinit var prefs: SharedPreferences
    private var loaded = false

    val chats = mutableListOf<SavedChat>()

    /** Id of the entry currently loaded in ChatSession (null = unsaved session). */
    var openId: String? = null
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!loaded) {
            restore()
            loaded = true
        }
    }

    fun archiveCurrent(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        val title = messages.firstOrNull { it.role == "user" }?.content?.take(60)
            ?: messages.firstOrNull()?.content?.take(60)
            ?: "Chat"
        // The open entry is the SAME conversation continued (or untouched):
        // update it in place instead of cloning a duplicate every open.
        val cur = openId?.let { id -> chats.firstOrNull { it.id == id } }
        if (cur != null) {
            val updated = cur.copy(title = title, messages = messages.toList(), savedAt = System.currentTimeMillis())
            chats.remove(cur)
            chats.add(0, updated)
            persist()
            return
        }
        val saved = SavedChat(
            id = UUID.randomUUID().toString(),
            title = title,
            starred = false,
            messages = messages.toList(),
            savedAt = System.currentTimeMillis()
        )
        // Newest first
        chats.add(0, saved)
        persist()
    }

    /** Replaces ChatSession.messages with the saved chat's history. */
    fun open(id: String): Boolean {
        val saved = chats.firstOrNull { it.id == id } ?: return false
        ChatSession.clear()
        ChatSession.messages.addAll(saved.messages)
        ChatSession.addUser("") // trigger persist or call ChatSession's internal persist
        ChatSession.removeAt(ChatSession.messages.size - 1)
        openId = id
        return true
    }

    /** Call after clearing to a blank chat — the next archive is a new entry. */
    fun detached() {
        openId = null
    }

    fun delete(id: String) {
        chats.removeAll { it.id == id }
        persist()
    }

    fun toggleStar(id: String) {
        val idx = chats.indexOfFirst { it.id == id }
        if (idx >= 0) {
            chats[idx] = chats[idx].copy(starred = !chats[idx].starred)
            persist()
        }
    }

    fun snapshot(): List<SavedChat> = chats.toList()

    private fun persist() {
        if (!::prefs.isInitialized) return
        try {
            val arr = JSONArray()
            chats.forEach { c ->
                val ms = JSONArray()
                c.messages.forEach { m -> ms.put(serialize(m)) }
                arr.put(JSONObject().apply {
                    put("id", c.id)
                    put("title", c.title)
                    put("starred", if (c.starred) 1 else 0)
                    put("savedAt", c.savedAt)
                    put("messages", ms)
                })
            }
            prefs.edit().putString(KEY_LIST, arr.toString()).apply()
        } catch (e: Exception) {
            // never let a persistence error break history
        }
    }

    private fun restore() {
        if (!::prefs.isInitialized) return
        try {
            val raw = prefs.getString(KEY_LIST, null) ?: return
            val arr = JSONArray(raw)
            chats.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ms = o.optJSONArray("messages")
                val list = mutableListOf<ChatMessage>()
                if (ms != null) {
                    for (j in 0 until ms.length()) list.add(deserialize(ms.getJSONObject(j)))
                }
                chats.add(SavedChat(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    title = o.optString("title", "Chat"),
                    starred = o.optInt("starred", 0) == 1,
                    messages = list,
                    savedAt = o.optLong("savedAt", 0)
                ))
            }
            // Newest first
            chats.sortByDescending { it.savedAt }
        } catch (e: Exception) {
            chats.clear()
        }
    }

    private fun serialize(msg: ChatMessage): JSONObject {
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

    private fun deserialize(o: JSONObject): ChatMessage {
        val arts = o.optJSONArray("artifacts")
        val list = mutableListOf<Artifact>()
        if (arts != null) {
            for (i in 0 until arts.length()) {
                val a = arts.getJSONObject(i)
                list.add(Artifact(
                    name = a.optString("name", "file"),
                    mime = a.optString("mime", "application/octet-stream"),
                    size = a.optLong("size", 0),
                    base64 = if (a.has("base64") && !a.isNull("base64")) a.getString("base64") else null,
                    content = if (a.has("content") && !a.isNull("content")) a.getString("content") else null
                ))
            }
        }
        return ChatMessage(
            role = o.optString("role", "assistant"),
            content = o.optString("content", ""),
            imageBase64 = if (o.has("image") && !o.isNull("image")) o.getString("image") else null,
            artifacts = list,
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
    }
}