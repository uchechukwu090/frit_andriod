package com.frit.app.core

import android.content.Context
import android.content.SharedPreferences

/**
 * PrivacyGuard — on-device redaction layer that scrubs personally-identifiable
 * information before any screen text, memory, or assistant context leaves the
 * device for the cloud brain.
 *
 * It runs as a single choke point in DeviceStateCollector so /agent/start AND
 * /agent/resume are both covered. Users can toggle it in Settings; when enabled
 * (default) sensitive tokens are replaced with placeholders like [phone] that
 * keep the task readable to the AI without leaking the actual value.
 */
object PrivacyGuard {
    private const val PREFS_KEY = "privacy_filter_enabled"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("frit_prefs", Context.MODE_PRIVATE)
    }

    fun isEnabled(): Boolean = ::prefs.isInitialized && prefs.getBoolean(PREFS_KEY, true)

    fun setEnabled(enabled: Boolean) {
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(PREFS_KEY, enabled).apply()
        }
    }

    /** Redact PII from a block of text. No-op when the filter is disabled. */
    fun redact(input: String): String {
        if (!isEnabled() || input.isBlank()) return input
        return PrivacyScrubber.scrub(input)
    }

    /** Compact one-liner for status displays / logs. */
    fun redactShort(input: String): String {
        if (!isEnabled() || input.isBlank()) return input
        return PrivacyScrubber.scrubCompact(input)
    }
}

/**
 * Pure, Android-free redaction core — directly unit-testable on the JVM.
 * PrivacyGuard wraps this with the SharedPreferences toggle.
 */
object PrivacyScrubber {
    fun scrub(input: String): String {
        if (input.isBlank()) return input
        var out = input
        // Specific, structured patterns first so a generic run of digits isn't
        // misread as the wrong thing (e.g. an IBAN caught as a phone/card).
        // CARD (exactly 16 digits) runs before PHONE so a 16-digit card is never
        // down-graded to a phone.
        out = out.replace(Patterns.IBAN_REGEX) { "[iban]" }
        out = out.replace(Patterns.CARD_REGEX) { "[card]" }
        out = out.replace(Patterns.OTP_REGEX) { "[otp]" }
        out = out.replace(Patterns.PHONE_REGEX) { m ->
            val digits = m.value.count { it.isDigit() }
            if (digits in 8..15) "[phone]" else m.value
        }
        out = out.replace(Patterns.EMAIL_REGEX) { "[email]" }
        out = out.replace(Patterns.LONG_DIGITS_REGEX) { "[redacted-number]" }
        return out
    }

    fun scrubCompact(input: String): String {
        if (input.isBlank()) return input
        var out = input
        out = out.replace(Patterns.PHONE_REGEX) { m ->
            if (m.value.count { it.isDigit() } in 8..15) "[phone]" else m.value
        }
        out = out.replace(Patterns.EMAIL_REGEX) { "[email]" }
        return out
    }

    private object Patterns {
        val EMAIL_REGEX = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        val OTP_REGEX = Regex("\\b(?:otp|code|pin|verification\\s*code|verification|verify|auth|token|password)\\b[^0-9\\n]{0,12}(\\d{3,12})\\b", RegexOption.IGNORE_CASE)
        // Exactly 16 digits (optionally grouped) — the near-universal card length.
        val CARD_REGEX = Regex("(?:\\d{4}[ -]?){3}\\d{4}")
        val IBAN_REGEX = Regex("\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b")
        val PHONE_REGEX = Regex("(?:\\+|00)?\\(?\\d\\)?[\\d\\s.-]{7,17}")
        val LONG_DIGITS_REGEX = Regex("(?<!\\d)\\d{16,}(?!\\d)")
    }
}