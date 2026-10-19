package com.frit.app.core

import android.content.Context
import android.content.SharedPreferences

/**
 * AdminStore — holds the creator's ADMIN_TOKEN outside of source code.
 *
 * This is separate from TokenStore (the app/user token) on purpose: the
 * admin token can approve payments and credit wallets, so it must never
 * ship baked into the APK. The creator pastes it once in Creator console.
 *
 * F-26: the token itself now lives in SecureStore (Keystore-encrypted). The
 * plaintext copy in "frit_prefs" is migrated and deleted on first read.
 */
object AdminStore {
    private const val KEY = "admin_token"

    private lateinit var prefs: SharedPreferences
    private lateinit var appCtx: Context
    private var initialized = false

    fun init(context: Context) {
        appCtx = context.applicationContext
        prefs = appCtx.getSharedPreferences("frit_prefs", Context.MODE_PRIVATE)
        initialized = true
    }

    fun get(): String {
        if (!initialized) return ""
        return try { SecureStore.getString(appCtx, KEY) } catch (_: Exception) { "" }
    }

    fun set(token: String) {
        if (!initialized) return
        SecureStore.putString(appCtx, KEY, token.trim())
    }

    fun isConfigured(): Boolean = get().isNotBlank()

    fun creatorMode(): Boolean {
        if (!initialized) return false
        return try { prefs.getBoolean("creator_watch", false) && isConfigured() } catch (_: Exception) { false }
    }

    fun setCreatorWatch(on: Boolean) {
        if (!initialized) return
        prefs.edit().putBoolean("creator_watch", on).apply()
    }
}
