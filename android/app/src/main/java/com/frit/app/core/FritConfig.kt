package com.frit.app.core

import com.frit.app.BuildConfig

/**
 * FritConfig — single source of truth for the production backend.
 *
 * Values are baked into the APK at build time via BuildConfig fields:
 * FRIT_BASE_URL comes from server/.env (FRIT_BASE_URL or the default), and
 * FRIT_AUTH_TOKEN comes from server/.env (AUTH_TOKEN). Nothing is hardcoded in
 * Kotlin source; both values are overridable at runtime in Settings
 * (server URL via base_url pref, token via TokenStore).
 */
object FritConfig {
    const val BASE_URL: String = BuildConfig.FRIT_BASE_URL
    // Play-ready: no secret in source. Baked at build time from server/.env
    // (AUTH_TOKEN) via BuildConfig. TokenStore prefs override this at runtime.
    val AUTH_TOKEN: String = BuildConfig.FRIT_AUTH_TOKEN
}