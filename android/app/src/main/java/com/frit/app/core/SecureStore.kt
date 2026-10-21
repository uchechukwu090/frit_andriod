package com.frit.app.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SecureStore — Keystore-backed (AES-256) storage for secrets that used to sit
 * in plaintext "frit_prefs": the App PIN vault ("app_pin") and the creator
 * ADMIN_TOKEN ("admin_token").
 *
 * NOT for the app auth token. The per-install token stays exactly where it is
 * (TokenStore / BuildConfig.FRIT_AUTH_TOKEN) — audit Constraint #1.
 *
 * Migration is lazy and one-way: the first read of a key that is missing from the
 * encrypted file but present in legacy "frit_prefs" copies it over (commit, not
 * apply) and then deletes the plaintext copy. If the Keystore is unavailable the
 * store degrades to the legacy plaintext file instead of losing the value.
 */
object SecureStore {
    private const val TAG = "SecureStore"
    private const val FILE = "frit_secure"
    private const val LEGACY = "frit_prefs"

    /**
     * Every key this store owns. The legacy (plaintext) file is cleared of
     * EXACTLY these keys on wipe.
     *
     * F-26: `clearAll` used to hard-code "app_pin" and "admin_token" while
     * the encrypted side was cleared wholesale — so the moment a third key
     * was added, DataWipe would clear it from the encrypted file but leave
     * its PLAINTEXT copy sitting in frit_prefs forever. One list, used by
     * both sides, cannot drift.
     */
    private val MANAGED_KEYS = setOf("app_pin", "admin_token")

    @Volatile private var secure: SharedPreferences? = null

    private fun legacy(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(LEGACY, Context.MODE_PRIVATE)

    private fun open(ctx: Context): SharedPreferences? {
        secure?.let { return it }
        synchronized(this) {
            secure?.let { return it }
            val app = ctx.applicationContext
            return try {
                val key = MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    app, FILE, key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { secure = it }
            } catch (e: Exception) {
                Log.e(TAG, "Encrypted prefs unavailable, using legacy store", e)
                null
            }
        }
    }

    fun getString(ctx: Context, key: String): String {
        val old = legacy(ctx)
        val s = open(ctx) ?: return try { old.getString(key, "").orEmpty() } catch (_: Exception) { "" }
        return try {
            val v = s.getString(key, null)
            if (v != null) return v
            val plain = old.getString(key, null) ?: return ""
            if (s.edit().putString(key, plain).commit()) old.edit().remove(key).apply()
            plain
        } catch (e: Exception) {
            Log.e(TAG, "read failed for $key", e)
            ""
        }
    }

    fun putString(ctx: Context, key: String, value: String) {
        val s = open(ctx)
        if (s == null) {
            legacy(ctx).edit().putString(key, value).apply()
            return
        }
        try {
            if (s.edit().putString(key, value).commit()) legacy(ctx).edit().remove(key).apply()
        } catch (e: Exception) {
            Log.e(TAG, "write failed for $key", e)
        }
    }

    fun remove(ctx: Context, key: String) {
        try { open(ctx)?.edit()?.remove(key)?.apply() } catch (_: Exception) { }
        legacy(ctx).edit().remove(key).apply()
    }

    /** Wipes the encrypted file (used by DataWipe). */
    fun clearAll(ctx: Context) {
        try { open(ctx)?.edit()?.clear()?.commit() } catch (_: Exception) { }
        // Remove every key this store owns from the legacy plaintext file —
        // not a hand-maintained subset. F-26: the old hard-coded list
        // ("app_pin", "admin_token") would leave a newly added key's PLAINTEXT
        // copy in frit_prefs after a "delete my data" wipe.
        try {
            val editor = legacy(ctx).edit()
            MANAGED_KEYS.forEach { editor.remove(it) }
            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "legacy wipe failed", e)
        }
    }

    /**
     * The plaintext keys this store still owns. Exposed for the wipe path
     * and for a device test that asserts the legacy file no longer holds
     * them.
     */
    fun managedKeys(): Set<String> = MANAGED_KEYS
}
