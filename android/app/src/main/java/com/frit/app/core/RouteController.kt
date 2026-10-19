package com.frit.app.core

import android.content.Context
import android.content.SharedPreferences

/**
 * RouteController — keeps the conversation on ONE brain once a session begins.
 *
 * The reported bug: mid-chat, the local model / pattern matcher keeps hijacking
 * the conversation (e.g. the word "play" or "open" in a normal sentence triggers
 * a local action, or the local LLM answers because it happens to be loaded),
 * even though the chat was running on the cloud brain. This controller locks the
 * route for the active conversation window and makes the next brain explicit.
 */
object RouteController {
    enum class Brain(val label: String) {
        AUTO("Auto"),
        LOCAL("Local Only"),
        CLOUD("Cloud Only")
    }

    const val PREFS_KEY = "brain_mode"

    private lateinit var prefs: SharedPreferences
    private var preferred: Brain = Brain.AUTO

    /** Locked brain for the current conversation window. */
    @Volatile private var activeBrain: Brain? = null
    @Volatile private var lastActivityMs = 0L
    private val CONVERSATION_TIMEOUT_MS = 10 * 60 * 1000L

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("frit_prefs", Context.MODE_PRIVATE)
        preferred = runCatching {
            Brain.valueOf(prefs.getString(PREFS_KEY, Brain.AUTO.name) ?: Brain.AUTO.name)
        }.getOrDefault(Brain.AUTO)
    }

    fun currentPreference(): Brain = preferred

    fun setPreference(brain: Brain) {
        preferred = brain
        if (::prefs.isInitialized) {
            prefs.edit().putString(PREFS_KEY, brain.name).apply()
        }
        resetActive() // new preference always re-decides on the next turn
    }

    /** Forget the locked brain (new conversation window). */
    fun resetActive() {
        activeBrain = null
        lastActivityMs = 0L
    }

    private fun isWithinConversation(): Boolean {
        if (activeBrain == null) return false
        if (lastActivityMs == 0L) return false
        return System.currentTimeMillis() - lastActivityMs < CONVERSATION_TIMEOUT_MS
    }

    /**
     * Decide which brain should answer THIS turn.
     *
     * @param isOffline whether network is currently unavailable
     * @param hasImage  whether the turn carries an image the local model can't read
     */
    fun decide(isOffline: Boolean, hasImage: Boolean): Brain {
        val now = System.currentTimeMillis()
        val isSticky = isWithinConversation()
        lastActivityMs = now

        // Hard rules that override stickiness:
        if (hasImage) {
            activeBrain = Brain.CLOUD
            return Brain.CLOUD
        }
        if (isOffline) {
            activeBrain = Brain.LOCAL
            return Brain.LOCAL
        }

        // Honor explicit user preference first (LOCAL or CLOUD Only).
        // If preferred is AUTO, we follow stickiness or default to CLOUD.
        when (preferred) {
            Brain.LOCAL -> {
                activeBrain = Brain.LOCAL
                return Brain.LOCAL
            }
            Brain.CLOUD -> {
                activeBrain = Brain.CLOUD
                return Brain.CLOUD
            }
            Brain.AUTO -> {
                // If we're mid-conversation, stay on the brain that started it.
                if (isSticky && activeBrain != null) {
                    return activeBrain!!
                }
                // Start of new session/window while online: prefer cloud.
                activeBrain = Brain.CLOUD
                return Brain.CLOUD
            }
        }
    }
}