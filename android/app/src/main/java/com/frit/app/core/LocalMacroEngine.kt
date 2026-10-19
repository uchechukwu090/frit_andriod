package com.frit.app.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.frit.app.features.market.FritAccessibilityService
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object LocalMacroEngine {
    private const val TAG = "LocalMacroEngine"

    // Thread-safe storage
    private val macros = ConcurrentHashMap<String, List<MacroAction>>()

    // Handler for main-thread gesture dispatch
    private val mainHandler = Handler(Looper.getMainLooper())

    // Set of active cancellation flags for all running macros
    private val activeCancellations = java.util.Collections.newSetFromMap(ConcurrentHashMap<AtomicBoolean, Boolean>())

    /**
     * Register a macro. Actions are validated for non-null coordinates.
     */
    fun registerMacro(name: String, actions: List<MacroAction>) {
        actions.forEach { action ->
            when (action) {
                is MacroAction.Tap -> {
                    require(action.x >= 0 && action.y >= 0) { "Tap coordinates must be non-negative" }
                }
                is MacroAction.Swipe -> {
                    require(allValid(action.startX, action.startY, action.endX, action.endY)) {
                        "Swipe coordinates must be non-negative"
                    }
                }
                is MacroAction.LongPress -> {
                    require(action.x >= 0 && action.y >= 0) { "LongPress coordinates must be non-negative" }
                }
                is MacroAction.Wait -> {
                    require(action.ms >= 0) { "Wait duration must be non-negative" }
                }
            }
        }
        macros[name] = actions
        Log.d(TAG, "Macro registered: $name with ${actions.size} actions")
    }

    /**
     * Execute a registered macro. Blocks until all actions finish or cancellation.
     * Returns true if all actions succeeded.
     */
    suspend fun execute(name: String): Boolean {
        val actions = macros[name]
        if (actions == null) {
            Log.w(TAG, "Macro '$name' not found")
            return false
        }

        val myCancel = AtomicBoolean(false)
        activeCancellations.add(myCancel)

        Log.d(TAG, "Executing macro '$name' with ${actions.size} actions")
        val service = FritAccessibilityService.instance
        if (service == null) {
            Log.e(TAG, "AccessibilityService not running – cannot execute macro")
            activeCancellations.remove(myCancel)
            return false
        }

        try {
            for (action in actions) {
                if (myCancel.get()) {
                    Log.d(TAG, "Macro execution cancelled")
                    return false
                }

                val success = when (action) {
                    is MacroAction.Wait -> {
                        delay(action.ms)
                        true
                    }
                    else -> executeActionWithCallback(action, service)
                }

                if (!success) {
                    Log.e(TAG, "Action failed: $action – aborting macro")
                    return false
                }
            }
        } finally {
            activeCancellations.remove(myCancel)
        }

        Log.d(TAG, "Macro '$name' completed successfully")
        return true
    }

    /**
     * Cancel any currently running macro.
     */
    fun cancel() {
        activeCancellations.forEach { it.set(true) }
    }

    /**
     * Professional Step: We wait for the SYSTEM to tell us the gesture is done.
     */
    private suspend fun executeActionWithCallback(
        action: MacroAction,
        service: FritAccessibilityService
    ): Boolean = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            val gesture = when (action) {
                is MacroAction.Tap -> service.createTap(action.x, action.y)
                is MacroAction.Swipe -> service.createSwipe(
                    action.startX, action.startY,
                    action.endX, action.endY,
                    action.duration
                )
                is MacroAction.LongPress -> service.createLongPress(action.x, action.y)
                else -> null
            }

            if (gesture == null) {
                if (cont.isActive) cont.resume(false)
                return@post
            }

            try {
                service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "Gesture cancelled by system")
                        if (cont.isActive) cont.resume(false)
                    }
                }, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error dispatching gesture", e)
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    private fun allValid(vararg coords: Float): Boolean = coords.all { it >= 0 && !it.isNaN() }
}