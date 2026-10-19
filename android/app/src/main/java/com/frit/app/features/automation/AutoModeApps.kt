package com.frit.app.features.automation

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * AutoMode per-app selection: which installed apps the AI watches and may
 * operate on the owner's behalf. Inventory comes from PackageManager
 * (launchable apps only — that covers third-party + system apps with a UI);
 * selection persists as a package-name set in frit_prefs.
 */
object AutoModeApps {
    private const val TAG = "AutoModeApps"
    private const val PREFS = "frit_prefs"
    private const val KEY = "automode_pkgs"

    data class AppEntry(val label: String, val packageName: String, val isSystem: Boolean)

    fun listInstalled(context: Context): List<AppEntry> {
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                    val label = ri.loadLabel(pm)?.toString()?.ifBlank { pkg } ?: pkg
                    val isSystem = (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    AppEntry(label, pkg, isSystem)
                }
                .distinctBy { it.packageName }
                .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
        } catch (e: Exception) {
            Log.w(TAG, "inventory failed", e)
            emptyList()
        }
    }

    fun selected(context: Context): MutableSet<String> {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        } catch (_: Exception) { mutableSetOf() }
    }

    fun setSelected(context: Context, packageName: String, on: Boolean) {
        try {
            val s = selected(context)
            if (on) s.add(packageName) else s.remove(packageName)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY, s).apply()
        } catch (e: Exception) {
            Log.w(TAG, "persist selection failed", e)
        }
    }

    fun isSelected(context: Context, packageName: String): Boolean =
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY, emptySet())?.contains(packageName) == true
        } catch (_: Exception) { false }

    /** Bulk replace (Check all / Uncheck all) — single prefs write. */
    fun replaceAll(context: Context, packages: Set<String>) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY, packages.toMutableSet()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "bulk replace failed", e)
        }
    }
}
