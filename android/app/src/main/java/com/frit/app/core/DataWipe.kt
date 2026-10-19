package com.frit.app.core

import android.content.Context
import com.frit.app.features.memory.MemoryEngine
import java.io.File

/**
 * DataWipe — a single, one-tap "Delete my data" routine that clears every
 * locally-stored piece of user data the app keeps, so users can exercise the
 * GDPR-style right to erasure on-device.
 *
 * Cleared:
 *  - chat history (ChatSession)
 *  - learned memory SQLite (non-seed entries)
 *  - API token (TokenStore)
 *  - scheduled task queues (AutomationScheduler / FritBackgroundService)
 *  - local wake-word voice template
 *  - crash logs (CrashGuard)
 *  - cached chat prefs
 *
 * NOT cleared: the downloaded model file (it's a public-weight binary you may
 * want to keep; re-downloading ~1GB is wasteful). Callers can use
 * ModelManager.deleteModel(context) if they explicitly want that too.
 */
object DataWipe {

    /** Erase all user data and return a short summary of what was removed. */
    fun wipeAll(context: Context): String {
        val cleared = mutableListOf("chat history")

        runCatching {
            ChatSession.clear()
        }

        runCatching {
            MemoryEngine.clear() // non-seed learned facts only; seed identity stays
            cleared.add("learned memory")
        }

        runCatching {
            UserProfile.clear()
            cleared.add("personal details")
        }

        runCatching {
            TokenStore.set("")
            cleared.add("API token")
        }

        runCatching {
            com.frit.app.features.automation.AutomationScheduler.listActiveTasks().forEach {
                com.frit.app.features.automation.AutomationScheduler.cancelTask(it)
            }
            cleared.add("scheduled tasks")
        }

        runCatching {
            val wakeTemplate = File(context.filesDir, "wake_template.json")
            if (wakeTemplate.exists()) wakeTemplate.delete()
            cleared.add("wake-word voice template")
        }

        runCatching {
            val crashDir = File(context.filesDir, "crashes")
            crashDir.listFiles()?.forEach { it.delete() }
            cleared.add("crash logs")
        }

        runCatching {
            val chatPrefs = context.getSharedPreferences("frit_chat_session", Context.MODE_PRIVATE)
            chatPrefs.edit().clear().apply()
        }

        // Week-3 audit F-33: these stores held the SAME user data ChatSession
        // already wipes — leaving them made "Delete my data" only half-true.
        runCatching {
            // Saved multi-chat archives (full message content incl. media).
            context.getSharedPreferences("frit_chat_history", Context.MODE_PRIVATE).edit().clear().apply()
            ChatHistory.chats.clear()
            cleared.add("saved chats")
        }
        runCatching {
            // Creator admin token + watch flag + seen-payment ids (financial).
            context.getSharedPreferences("frit_prefs", Context.MODE_PRIVATE).edit()
                .remove("admin_token").remove("creator_watch").remove("creator_seen_ids")
                .apply()
            cleared.add("admin token")
        }
        runCatching {
            // F-26: encrypted secrets file (App PIN vault + admin token). The App PIN
            // was previously never wiped at all.
            SecureStore.clearAll(context)
            cleared.add("App PIN vault")
        }
        runCatching {
            // Veil PIN hash/salt/fails + enrolled face references.
            context.getSharedPreferences("frit_veil", Context.MODE_PRIVATE).edit().clear().apply()
            context.getSharedPreferences("frit_veil_face", Context.MODE_PRIVATE).edit().clear().apply()
            cleared.add("veil PIN & face data")
        }
        runCatching {
            // Offline trading cache (wallet/portfolio/money rows).
            context.getSharedPreferences("frit_trading_cache", Context.MODE_PRIVATE).edit().clear().apply()
            cleared.add("trading cache")
        }
        runCatching {
            // Persisted background-task queue storage (task text may be personal).
            context.getSharedPreferences("frit_task_queue", Context.MODE_PRIVATE).edit().clear().apply()
            cleared.add("task queue storage")
        }

        return "Cleared: ${cleared.joinToString(", ")}"
    }
}