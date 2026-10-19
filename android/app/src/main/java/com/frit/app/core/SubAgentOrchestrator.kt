package com.frit.app.core

import android.content.Context
import android.util.Log
import com.frit.app.data.NetworkClient
import com.frit.app.features.market.MT5Agent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SubAgentOrchestrator — Thin facade delegating task execution to IntegrationCoordinator.chat().
 * Maintains backward compatibility with callers (MainActivity, FritBackgroundService, FritVoiceOverlayService).
 */
object SubAgentOrchestrator {
    private const val TAG = "FRIT_AGENT"
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        MT5Agent.init(context)
        
        LocalMacroEngine.registerMacro("go_home", listOf(MacroAction.Tap(540f, 2200f)))
        LocalMacroEngine.registerMacro("scroll_up", listOf(MacroAction.Swipe(540f, 1800f, 540f, 600f)))
    }

    fun setBaseUrl(url: String) {
        NetworkClient.setBaseUrl(url)
    }

    suspend fun dispatch(command: String): String = withContext(Dispatchers.IO) {
        try {
            appContext?.let { FritAgentService.start(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start agent service", e)
        }
        return@withContext try {
            IntegrationCoordinator.chat(command, emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "Agentic loop crash", e)
            "Error in agent loop: ${e.message}"
        }
    }
}
