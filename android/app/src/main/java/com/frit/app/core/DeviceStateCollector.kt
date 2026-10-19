package com.frit.app.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.frit.app.core.Diag
import org.json.JSONArray
import org.json.JSONObject

/**
 * DeviceStateCollector — builds a consistent `device_state` for every
 * /agent/start and /agent/resume round trip. Includes the installed-apps list
 * (labels) so the server can ground `open_app` calls against the real device.
 *
 * The list is cached briefly (10s) — getInstalledApplications() is expensive and
 * the agent loop can resume several times per turn.
 */
object DeviceStateCollector {
    private const val TAG = "DeviceState"
    private const val CACHE_TTL_MS = 10_000L

    @Volatile private var cachedApps: List<String>? = null
    @Volatile private var cacheTime = 0L

    /** Fetch the app context from whichever init site is alive. */
    private fun appContext(): Context? {
        com.frit.app.core.IntegrationCoordinator.getAppContext()?.let { return it }
        com.frit.app.features.automation.AutomationEngine.getAppContext()?.let { return it }
        LocalInferenceEngine.appContextSafe?.let { return it }
        return com.frit.app.features.market.FritAccessibilityService.instance
    }

    /**
     * Collect installed application labels (e.g. "WhatsApp", "Chrome") via
     * PackageManager.
     */
    fun installedAppNames(): List<String> {
        val cached = cachedApps
        val now = System.currentTimeMillis()
        if (cached != null && now - cacheTime < CACHE_TTL_MS) return cached

        val ctx = appContext() ?: return emptyList()

        return try {
            val pm = ctx.packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            val names = resolveInfos.mapNotNull { ri ->
                runCatching {
                    val label = ri.loadLabel(pm).toString().trim()
                    if (label.isNotBlank()) label else ri.activityInfo.packageName
                }.getOrNull()
            }.filter { it.isNotBlank() }.distinct()

            cachedApps = names
            cacheTime = now
            names
        } catch (e: Exception) {
            Log.e(TAG, "installedAppNames failed", e)
            emptyList()
        }
    }

    /**
     * Returns a JSON array of { "label": "MetaTrader 5", "package": "net.metaquotes.metatrader5" }
     * objects so the cloud brain has both the human-readable name AND the definitive package name.
     * This eliminates false "not installed" responses for apps like MT5 or WhatsApp Business.
     */
    fun installedAppsJson(): JSONArray {
        val ctx = appContext() ?: return JSONArray()
        val arr = JSONArray()
        return try {
            val pm = ctx.packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            val addedPackages = mutableSetOf<String>()

            for (ri in resolveInfos) {
                val pkg = ri.activityInfo.packageName
                if (addedPackages.add(pkg)) {
                    val label = runCatching { ri.loadLabel(pm).toString().trim() }.getOrDefault(pkg)
                    arr.put(JSONObject().apply {
                        put("label", if (label.isNotBlank()) label else pkg)
                        put("package", pkg)
                    })
                }
            }

            if (arr.length() < 3) {
                val apps = pm.getInstalledApplications(0)
                apps.forEach { ai ->
                    val pkg = ai.packageName
                    if (addedPackages.add(pkg)) {
                        val launchIntent = pm.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null) {
                            val label = runCatching { pm.getApplicationLabel(ai).toString().trim() }.getOrDefault(pkg)
                            arr.put(JSONObject().apply {
                                put("label", if (label.isNotBlank()) label else pkg)
                                put("package", pkg)
                            })
                        }
                    }
                }
            }
            arr
        } catch (e: Exception) {
            Log.e(TAG, "installedAppsJson failed", e)
            arr
        }
    }

    /** Build the full device_state object for agent round trips. */
    fun build(screenText: String? = null, currentApp: String? = null, memory: JSONArray? = null, imageBase64: String? = null, includeInstalledApps: Boolean = true): JSONObject {
        val rawScreen = (screenText ?: com.frit.app.features.market.FritAccessibilityService.getScreenText()).take(1500)
        val app = currentApp ?: com.frit.app.features.market.FritAccessibilityService.getCurrentApp()
        val activity = com.frit.app.features.market.FritAccessibilityService.getCurrentActivity()
        val hasScreenCapture = com.frit.app.features.vision.ScreenCaptureService.lastScreenshotBase64 != null

        return JSONObject().apply {
            // PrivacyGuard strips PII (phones, emails, cards, OTP) before the
            // screen content reaches the cloud. Applies to every round trip.
            put("screen_text", PrivacyGuard.redact(rawScreen))
            put("screen_available", rawScreen.isNotBlank())
            put("current_app", app)
            put("current_activity", activity)
            put("has_screen_capture", hasScreenCapture)
            put("network_status", if (isOnlineCtx()) "online" else "offline")
            put("battery_level", batteryPercent())
            if (rawScreen.isBlank() && hasScreenCapture) {
                put("screen_note", "No accessibility text on this screen. A screen capture is available — use the analyze_screenshot tool to inspect the UI visually.")
            }
            memory?.let { mem ->
                // Redact any memory string entries that carry sensitive numbers.
                val scrubbed = JSONArray()
                for (i in 0 until mem.length()) {
                    val item = mem.opt(i)
                    scrubbed.put(if (item is String) PrivacyGuard.redact(item) else item)
                }
                put("memory", scrubbed)
            }
            imageBase64?.let { put("user_image", it) }
            if (includeInstalledApps) put("installed_apps", installedAppsJson())
            // Personal details (Settings → Personal Details, first-launch
            // onboarding). Sent so the brain can personalise + mirror tone.
            try { put("user_profile", UserProfile.snapshotJson()) } catch (e: Exception) { Diag.warn("DeviceState", "user_profile", e) }
        }
    }

    private fun isOnlineCtx(): Boolean {
        return try {
            val ctx = appContext() ?: return false
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) { false }
    }

    private fun batteryPercent(): Int {
        return try {
            val ctx = appContext() ?: return -1
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager ?: return -1
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) { -1 }
    }
}