package com.frit.app.core

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * NetworkSwitch — best-effort, permission-free connectivity helper.
 *
 * User decision: automations run on the local model (Gemma 3) FIRST. Only if the
 * local brain cannot comprehend the task do we bring the network up to reach the
 * cloud. Toggle order (best-effort, never asks for special privileges):
 *   1. Mobile data via ConnectivityManager.setMobileDataEnabled() reflection
 *      (hidden API; works on many OEM builds, silently no-ops otherwise).
 *   2. Wi-Fi enabled through WifiManager (deprecated API — still functional on
 *      most devices; requires CHANGE_WIFI_STATE).
 *   3. Last resort: open the network panel so the user can tap once.
 * restore() undoes whatever we did (mobile-data reflection toggle, Wi-Fi) so the
 * user's radio state is left the way we found it after a cloud round trip.
 */
object NetworkSwitch {
    private var appContext: Context? = null

    @Volatile private var mobileDataTurnedOn = false
    @Volatile private var wifiTurnedOn = false

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isOnline(): Boolean {
        return try {
            val ctx = appContext ?: LocalInferenceEngine.appContextSafe ?: return false
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    data class ConnectivityResult(
        val online: Boolean,
        val toggledMobileData: Boolean,
        val toggledWifi: Boolean,
        val openedPanel: Boolean,
        val message: String
    )

    /**
     * Try to bring the device online without asking the user. Suspends up to
     * [maxWaitMs] while the radio attaches after each attempt.
     */
    suspend fun ensureBestEffortConnectivity(maxWaitMs: Long = 7_000L): ConnectivityResult = withContext(Dispatchers.IO) {
        if (isOnline()) return@withContext ConnectivityResult(true, false, false, false, "Already online.")
        val actions = mutableListOf<String>()

        // 1. Mobile data via hidden-API reflection.
        if (setMobileDataReflection(true)) {
            mobileDataTurnedOn = true
            actions += "mobile data (reflection)"
            if (waitUntilOnline(maxWaitMs)) {
                return@withContext ConnectivityResult(true, mobileDataTurnedOn, false, false, "Online after enabling ${actions.joinToString(", ")}.")
            }
        }

        // 2. Wi-Fi fallback.
        val wifiStartedOff = !wifiEnabled()
        if (wifiStartedOff && setWifiEnabled(true)) {
            wifiTurnedOn = true
            actions += "Wi-Fi"
            if (waitUntilOnline(maxWaitMs)) {
                return@withContext ConnectivityResult(true, mobileDataTurnedOn, wifiTurnedOn, false, "Online after enabling ${actions.joinToString(", ")}.")
            }
        }

        // 3. Last resort: hand the user a one-tap network panel.
        openNetworkPanel()
        ConnectivityResult(isOnline(), mobileDataTurnedOn, wifiTurnedOn, true, "Could not auto-enable data — opened the network panel.")
    }

    /** Undo whatever ensureBestEffortConnectivity changed. Wi-Fi is left off again only if we turned it on. */
    suspend fun restore() = withContext(Dispatchers.IO) {
        if (mobileDataTurnedOn) {
            setMobileDataReflection(false)
            mobileDataTurnedOn = false
        }
        if (wifiTurnedOn) {
            setWifiEnabled(false)
            wifiTurnedOn = false
        }
    }

    private suspend fun waitUntilOnline(maxWaitMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            if (isOnline()) return true
            delay(600)
        }
        return isOnline()
    }

    /** Hidden ConnectivityManager.setMobileDataEnabled — reflection, best-effort. */
    private fun setMobileDataReflection(enabled: Boolean): Boolean {
        return try {
            val ctx = appContext ?: LocalInferenceEngine.appContextSafe ?: return false
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) ?: return false
            val method = cm.javaClass.getMethod("setMobileDataEnabled", Boolean::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(cm, enabled)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun wifiEnabled(): Boolean {
        return try {
            val ctx = appContext ?: LocalInferenceEngine.appContextSafe ?: return false
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
            wm.isWifiEnabled
        } catch (e: Exception) {
            false
        }
    }

    private fun setWifiEnabled(enabled: Boolean): Boolean {
        return try {
            val ctx = appContext ?: LocalInferenceEngine.appContextSafe ?: return false
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
            wm.setWifiEnabled(enabled)
        } catch (e: Exception) {
            false
        }
    }

    private fun openNetworkPanel() {
        try {
            val ctx = appContext ?: LocalInferenceEngine.appContextSafe ?: return
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            } else {
                Intent(Settings.ACTION_WIRELESS_SETTINGS)
            }
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try {
                val ctx = appContext ?: LocalInferenceEngine.appContextSafe ?: return
                ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                // No activity available — nothing more we can do.
            }
        }
    }
}