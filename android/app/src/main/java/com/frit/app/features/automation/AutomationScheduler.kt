package com.frit.app.features.automation

import android.content.Context
import org.json.JSONArray

object AutomationScheduler {
    private var appContext: Context? = null

    fun init(context: Context) { 
        appContext = context.applicationContext 
    }

    fun scheduleTask(taskId: String, taskDescription: String, intervalSeconds: Long) {
        appContext?.let {
            FritBackgroundService.enqueue(it, taskId, taskDescription, repeatSeconds = intervalSeconds)
        }
    }

    /**
     * AutoMode per-app watch task: a recurring task BOUND to one app package
     * (e.g. every 30m check WhatsApp for unread chats needing a reply). The
     * package travels with the task (persisted + restored), and each firing
     * is prefixed with the app context so the agent operates on/inside that
     * app as the owner would. Scheduling also auto-checks the app in the
     * AutoMode watch list — a scheduled watch implies selection.
     */
    fun scheduleAppTask(packageName: String, appLabel: String, taskDescription: String, intervalSeconds: Long) {
        val id = "appwatch_${packageName}_${Math.abs(taskDescription.hashCode())}"
        appContext?.let {
            AutoModeApps.setSelected(it, packageName, true)
            FritBackgroundService.enqueue(it, id, taskDescription, repeatSeconds = intervalSeconds, pkg = packageName)
        }
    }

    fun cancelTask(taskId: String) {
        appContext?.let {
            FritBackgroundService.cancel(it, taskId)
        }
    }

    /** Force-end every background task (banner End button / voice-mode close). */
    fun cancelAll() {
        listActiveTasks().forEach { cancelTask(it) }
    }

    fun listActiveTasks(): List<String> {
        val prefs = appContext?.getSharedPreferences("frit_task_queue", Context.MODE_PRIVATE) ?: return emptyList()
        val queue = try { JSONArray(prefs.getString("queue", "[]")) } catch (e: Exception) { JSONArray() }
        val list = mutableListOf<String>()
        for (i in 0 until queue.length()) {
            list.add(queue.getJSONObject(i).optString("id"))
        }
        return list
    }
    
    fun getTaskInterval(taskId: String): Long {
        val prefs = appContext?.getSharedPreferences("frit_task_queue", Context.MODE_PRIVATE) ?: return 0L
        val queue = try { JSONArray(prefs.getString("queue", "[]")) } catch (e: Exception) { JSONArray() }
        for (i in 0 until queue.length()) {
            val obj = queue.getJSONObject(i)
            if (obj.optString("id") == taskId) return obj.optLong("repeat", 0L)
        }
        return 0L
    }

    data class ParsedTask(val task: String, val intervalSeconds: Long)

    /**
     * Flexible interval parser for the Automation UI field.
     * Accepts: "180", "180 mins", "180min", "3 hours", "1h30m", "90s", "2 days".
     * Bare numbers are treated as minutes (UI label). Never silently falls back
     * to 5 min unless input is truly empty/unparseable.
     */
    fun parseIntervalInput(raw: String, defaultMinutes: Long = 5L): Long {
        val t = raw.lowercase().trim()
        if (t.isBlank()) return defaultMinutes * 60
        // Compound like "1h30m", "2h 15min", "1d 2h"
        val compound = Regex("(\\d+(?:\\.\\d+)?)\\s*(d|day|days|h|hr|hrs|hour|hours|m|min|mins|minute|minutes|s|sec|secs|second|seconds)").findAll(t).toList()
        if (compound.isNotEmpty()) {
            var total = 0.0
            for (m in compound) {
                val v = m.groupValues[1].toDoubleOrNull() ?: continue
                total += when {
                    m.groupValues[2].startsWith("d") -> v * 86400
                    m.groupValues[2].startsWith("h") -> v * 3600
                    m.groupValues[2].startsWith("m") -> v * 60
                    m.groupValues[2].startsWith("s") -> v
                    else -> v * 60
                }
            }
            if (total > 0) return total.toLong().coerceAtLeast(30L)
        }
        // Bare number -> minutes (matches UI label)
        val bare = t.toDoubleOrNull()
        if (bare != null && bare > 0) return (bare * 60).toLong().coerceAtLeast(30L)
        // Last resort: first number found, assume minutes
        val num = Regex("\\d+(?:\\.\\d+)?").find(t)?.value?.toDoubleOrNull()
        if (num != null && num > 0) return (num * 60).toLong().coerceAtLeast(30L)
        return defaultMinutes * 60
    }

    fun formatInterval(seconds: Long): String {
        return when {
            seconds < 60 -> "$seconds sec"
            seconds % 86400 == 0L -> "${seconds / 86400} day(s)"
            seconds % 3600 == 0L -> "${seconds / 3600} hour(s)"
            seconds % 60 == 0L -> "${seconds / 60} min"
            else -> "${seconds / 60} min ${seconds % 60} sec"
        }
    }

    /**
     * Parses natural language expressions into a task string + repetition interval in seconds.
     * Supports intervals at the start (e.g. "every 15 minutes check mt5 positions") or at the end
     * (e.g. "check mt5 positions every 15 minutes", "analyze screen every 30 seconds").
     */
    fun parseRecurringTask(text: String): ParsedTask? {        val t = text.lowercase().trim()

        // 1. Match interval at start: "every [num] [unit] [task]" or "repeat every [num] [unit] [task]"
        val regexFront = Regex("(?:every|repeat|each)\\s+(\\d+)?\\s*(sec|second|min|minute|hour|hr|day|s|m|h|d)s?\\s*(?:to|and|do|check|that)?\\s+(.+)", RegexOption.IGNORE_CASE)
        var match = regexFront.find(t)
        if (match != null) {
            val numStr = match.groupValues[1]
            val value = if (numStr.isBlank()) 1L else numStr.toLongOrNull() ?: 1L
            val unit = match.groupValues[2].lowercase()
            val task = match.groupValues[3].trim()
            if (task.isNotBlank()) {
                val interval = calculateInterval(value, unit)
                return ParsedTask(task, interval)
            }
        }

        // 2. Match interval at end: "[task] every/each/repeat [num] [unit]"
        val regexBack = Regex("(.+)\\s+(?:every|repeat|each)\\s+(\\d+)?\\s*(sec|second|min|minute|hour|hr|day|s|m|h|d)s?$", RegexOption.IGNORE_CASE)
        match = regexBack.find(t)
        if (match != null) {
            val task = match.groupValues[1].trim()
            val numStr = match.groupValues[2]
            val value = if (numStr.isBlank()) 1L else numStr.toLongOrNull() ?: 1L
            val unit = match.groupValues[3].lowercase()
            if (task.isNotBlank()) {
                val interval = calculateInterval(value, unit)
                return ParsedTask(task, interval)
            }
        }

        return null
    }

    private fun calculateInterval(value: Long, unit: String): Long {
        return when {
            unit.startsWith("s") -> value
            unit.startsWith("m") -> value * 60
            unit.startsWith("h") -> value * 3600
            unit.startsWith("d") -> value * 86400
            else -> value * 60
        }
    }
}
