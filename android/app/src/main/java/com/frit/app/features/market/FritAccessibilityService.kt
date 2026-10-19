package com.frit.app.features.market

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.frit.app.core.Diag
import com.frit.app.features.market.MT5Agent.TradePosition
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

class FritAccessibilityService : AccessibilityService() {

    interface OnScreenChangeListener {
        fun onScreenChanged()
    }

    companion object {
        private const val TAG = "FritA11y"
        private val mainHandler = Handler(Looper.getMainLooper())
        
        var instance: FritAccessibilityService? = null
            private set

        private val screenChangeListeners = java.util.concurrent.CopyOnWriteArrayList<OnScreenChangeListener>()

        fun addOnScreenChangeListener(listener: OnScreenChangeListener) {
            screenChangeListeners.add(listener)
        }

        fun removeOnScreenChangeListener(listener: OnScreenChangeListener) {
            screenChangeListeners.remove(listener)
        }

        // --- Patch 1: The "Fresh Root" Logic ---
        // Never cache AccessibilityNodeInfo. Always fetch fresh.
        private val rootNode: AccessibilityNodeInfo?
            get() = instance?.rootInActiveWindow

        fun getScreenText(): String {
            return try {
                val root = rootNode ?: return ""
                val sb = StringBuilder()
                collectTextsBFS(root, sb)
                sb.toString().trim()
            } catch (e: Exception) {
                Log.e(TAG, "getScreenText error", e)
                ""
            }
        }

        fun getCurrentApp(): String = try { rootNode?.packageName?.toString().orEmpty() } catch(e: Exception) { "" }
        fun getCurrentActivity(): String = instance?.lastWindowClassName.orEmpty()

        fun getStructuredScreenDump(maxDepth: Int = 12): String {
            return try {
                val root = rootNode ?: return """{"elements":[]}"""
                val arr = JSONArray()
                collectStructuredBFS(root, arr, maxDepth)
                JSONObject().put("elements", arr).toString()
            } catch (e: Exception) {
                """{"elements":[], "error": "${e.message}"}"""
            }
        }

        // --- Patch 2: BFS Search for Reliability ---
        private fun collectTextsBFS(root: AccessibilityNodeInfo, sb: StringBuilder) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var count = 0
            while (queue.isNotEmpty() && count < 2000) {
                val node = queue.removeFirst()
                count++
                try {
                    val t = node.text?.toString() ?: node.contentDescription?.toString()
                    if (!t.isNullOrBlank()) sb.appendLine(t.trim())
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { queue.add(it) }
                    }
                } catch (_: Exception) {
                }
            }
        }

        private fun collectStructuredBFS(root: AccessibilityNodeInfo, arr: JSONArray, maxDepth: Int) {
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.add(root to 0)
            var count = 0
            while (queue.isNotEmpty() && count < 1500) {
                val (node, depth) = queue.removeFirst()
                count++
                try {
                    if (depth <= maxDepth) {
                        arr.put(nodeToJson(node))
                        for (i in 0 until node.childCount) {
                            node.getChild(i)?.let { queue.add(it to depth + 1) }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        private fun nodeToJson(node: AccessibilityNodeInfo): JSONObject {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val json = JSONObject()
            try {
                json.put("text", node.text?.toString() ?: "")
                json.put("content_desc", node.contentDescription?.toString() ?: "")
                json.put("resource_id", try { node.viewIdResourceName ?: "" } catch(_: Exception) { "" })
                json.put("class_name", node.className?.toString() ?: "")
                json.put("clickable", node.isClickable)
                json.put("bounds", JSONObject().put("l", rect.left).put("t", rect.top).put("r", rect.right).put("b", rect.bottom))
            } catch (_: Exception) {}
            return json
        }

        fun getMT5Positions(): List<TradePosition> {
            val text = getScreenText()
            val positions = mutableListOf<TradePosition>()
            val lines = text.lines()
            
            // Recommendation: Robust parser for custom MT5 canvas text layouts
            for (line in lines) {
                val t = line.trim().uppercase()
                if (t.contains("BUY") || t.contains("SELL")) {
                    val parts = t.split(Regex("[^A-Z0-9.]+")).filter { it.isNotBlank() }
                    val symbolMatch = parts.firstOrNull { it.length >= 3 && it.any { c -> c.isLetter() } }
                    val typeMatch = parts.firstOrNull { it == "BUY" || it == "SELL" }
                    
                    if (symbolMatch != null && typeMatch != null) {
                        val numbers = parts.filter { it.toDoubleOrNull() != null }
                        if (numbers.size >= 3) {
                            positions.add(TradePosition(
                                ticket = "0",
                                symbol = symbolMatch,
                                type = typeMatch,
                                volume = numbers[0].toDoubleOrNull() ?: 0.0,
                                openPrice = numbers[1].toDoubleOrNull() ?: 0.0,
                                sl = 0.0,
                                tp = 0.0,
                                profit = numbers.last().toDoubleOrNull() ?: 0.0
                            ))
                        }
                    }
                }
            }
            return positions
        }

        fun clickButtonWithText(text: String): Boolean {
            val root = rootNode ?: return false
            try {
                // 1. Try exact match
                var nodes = root.findAccessibilityNodeInfosByText(text)
                val target = if (!nodes.isNullOrEmpty()) {
                    nodes.firstOrNull()
                } else {
                    // 2. Try fuzzy match (BFS search)
                    findNodeFuzzy(root, text)
                }

                if (target != null) {
                    val clickable = findClickableParent(target)
                    if (clickable != null) {
                        mainHandler.post { try { clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch(_: Exception) {} }
                        return true
                    }
                    
                    // Artemis Multimodal Targeting Strategy: if the node exposes text but lacks a clickable parent tree container,
                    // calculate its center screen absolute bounding rect coordinates and issue an active physical tap gesture.
                    val rect = Rect()
                    target.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0) {
                        val cx = rect.centerX().toFloat()
                        val cy = rect.centerY().toFloat()
                        mainHandler.post {
                            try {
                                instance?.performTap(cx, cy)
                            } catch (e: Exception) { Diag.warn("FritAccessibility", "performTap", e) }
                        }
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "clickButtonWithText error", e)
            }
            return false
        }

        private fun findNodeFuzzy(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            val search = text.lowercase()
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                val content = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").lowercase()
                if (content.contains(search)) return node
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            return null
        }

        private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var cur: AccessibilityNodeInfo? = node
            repeat(8) {
                try {
                    if (cur?.isClickable == true) return cur
                    cur = cur?.parent
                } catch (_: Exception) { return null }
            }
            return null
        }

        suspend fun executeStep(action: String, label: String = "", x: Int? = null, y: Int? = null, value: String = "", field: String = ""): String = withContext(Dispatchers.Main) {
            Log.d(TAG, "executeStep: $action, label=$label, x=$x, y=$y")
            try {
                val root = instance?.rootInActiveWindow ?: return@withContext "Error: No active window"
                when (action) {
                    "read_screen" -> return@withContext getScreenText()
                    "tap_button" -> {
                        val nodes = root.findAccessibilityNodeInfosByText(label)
                        val target = nodes?.firstOrNull()?.let { findClickableParent(it) } ?: findNodeFuzzy(root, label)?.let { findClickableParent(it) }
                        if (target != null) {
                            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            delay(500)
                            return@withContext "Tapped $label"
                        } else return@withContext "Could not find $label"
                    }
                    "tap_coordinates" -> {
                        if (x != null && y != null) {
                            instance?.performTap(x.toFloat(), y.toFloat())
                            delay(500)
                            return@withContext "Tapped coordinates ($x, $y)"
                        }
                    }
                    "type_text" -> {
                        val nodes = if (field.isNotBlank()) root.findAccessibilityNodeInfosByText(field) else null
                        val target = nodes?.firstOrNull() ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        if (target != null) {
                            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
                            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                            delay(300)
                            return@withContext "Typed '$value'"
                        } else return@withContext "No text field found to type into"
                    }
                    "scroll" -> {
                        val direction = label.lowercase()
                        if (direction == "up") {
                            root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                        } else if (direction == "left" || direction == "right") {
                            // High precision coordinate swipe fallback for horizontal menus
                            val displayMetrics = instance?.resources?.displayMetrics
                            val width = displayMetrics?.widthPixels ?: 1080
                            val height = displayMetrics?.heightPixels ?: 2160
                            val startX = if (direction == "left") width * 0.8f else width * 0.2f
                            val endX = if (direction == "left") width * 0.2f else width * 0.8f
                            val centerY = height * 0.5f
                            val path = Path().apply { moveTo(startX, centerY); lineTo(endX, centerY) }
                            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build()
                            instance?.dispatchGesture(gesture, null, null)
                        } else {
                            root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        }
                        delay(600)
                        return@withContext "Scrolled $direction"
                    }
                    "swipe_coordinates" -> {
                        if (x != null && y != null) {
                            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x.toFloat(), (y - 400).toFloat()) }
                            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build()
                            instance?.dispatchGesture(gesture, null, null)
                            delay(500)
                            return@withContext "Swiped starting from ($x, $y)"
                        }
                        return@withContext "Missing coordinate arguments for swipe"
                    }
                    "long_press_element" -> {
                        val nodes = root.findAccessibilityNodeInfosByText(label)
                        val target = nodes?.firstOrNull() ?: findNodeFuzzy(root, label)
                        if (target != null) {
                            val rect = Rect()
                            target.getBoundsInScreen(rect)
                            val cx = rect.centerX().toFloat()
                            val cy = rect.centerY().toFloat()
                            val path = Path().apply { moveTo(cx, cy) }
                            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 700)).build()
                            instance?.dispatchGesture(gesture, null, null)
                            delay(800)
                            return@withContext "Long pressed $label"
                        } else return@withContext "Could not find $label to long press"
                    }
                    "press_back" -> {
                        instance?.performGlobalAction(GLOBAL_ACTION_BACK)
                        delay(300)
                        return@withContext "Pressed back"
                    }
                    "press_home" -> {
                        instance?.performGlobalAction(GLOBAL_ACTION_HOME)
                        delay(500)
                        return@withContext "Pressed home"
                    }
                    "toggle_quick_settings" -> {
                        instance?.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                        delay(500)
                        return@withContext "Opened Quick Settings"
                    }
                    "toggle_notifications" -> {
                        instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                        delay(500)
                        return@withContext "Opened Notifications"
                    }
                    "recent_apps", "recents" -> {
                        instance?.performGlobalAction(GLOBAL_ACTION_RECENTS)
                        delay(500)
                        return@withContext "Opened Recent Apps"
                    }
                    "lock_screen" -> {
                        instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                        delay(300)
                        return@withContext "Locked Screen"
                    }
                    // Full-height swipe-up for NON-SECURE keyguards (Swipe/None).
                    // A PIN/password lock cannot be dismissed this way (Android
                    // seals credential windows) — callers must check lockState
                    // first and only use this for "locked_swipe".
                    "dismiss_keyguard" -> {
                        val dm = android.content.res.Resources.getSystem().displayMetrics
                        val x = (dm.widthPixels / 2).toFloat()
                        val y0 = (dm.heightPixels * 0.85).toFloat()
                        val y1 = (dm.heightPixels * 0.20).toFloat()
                        val path = Path().apply { moveTo(x, y0); lineTo(x, y1) }
                        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 500)).build()
                        instance?.dispatchGesture(gesture, null, null)
                        delay(800)
                        return@withContext "Swiped up to dismiss keyguard"
                    }
                    "take_native_screenshot" -> {
                        instance?.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                        delay(500)
                        return@withContext "Took Native Screenshot"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "executeStep internal error", e)
                return@withContext "Error: ${e.message}"
            }
            return@withContext "Unknown action: $action"
        }
    }

    private var lastWindowClassName: String = ""

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Accessibility connected")
        
        // Ensure we keep running by requesting to be a persistent notification if we were a foreground service
        // (Note: AccessibilityService is already treated as important by the system, 
        // but we want to make sure the user knows we are active)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            if (event.packageName != null) {
                // Ignore events from our own app to avoid loops
                if (event.packageName == packageName) return
            }

            event.className?.toString()?.let { if (it.isNotBlank()) lastWindowClassName = it }
            
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    screenChangeListeners.forEach { 
                        try { it.onScreenChanged() } catch(e: Exception) { Log.e(TAG, "Listener error", e) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onAccessibilityEvent error", e)
        }
    }

    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 10)).build()
        dispatchGesture(gesture, null, null)
    }

    fun createTap(x: Float, y: Float): GestureDescription {
        val path = Path().apply { moveTo(x, y) }
        return GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 10)).build()
    }
    fun createSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Int): GestureDescription {
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        return GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration.toLong())).build()
    }
    fun createLongPress(x: Float, y: Float): GestureDescription {
        val path = Path().apply { moveTo(x, y) }
        return GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 600)).build()
    }
}