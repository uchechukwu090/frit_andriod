package com.frit.app.core

import android.util.Log
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Diag — one-liner for catches that used to swallow errors silently (audit F-16).
 *
 *   try { ... } catch (e: Exception) { Diag.warn("Wallet", "refresh", e) }
 *
 * Logs to Logcat and keeps the last [MAX] entries in memory so a bug report /
 * debug screen can show what has been failing quietly. Never throws, never
 * touches the network, and never records message bodies — only the call site,
 * exception class and message.
 */
object Diag {
    private const val MAX = 60
    private val ring = ConcurrentLinkedDeque<String>()

    fun warn(tag: String, where: String, e: Throwable? = null) {
        val line = "${System.currentTimeMillis()} $tag/$where: " +
            (e?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "failed")
        try {
            Log.w(tag, "$where failed", e)
            ring.addLast(line)
            while (ring.size > MAX) ring.pollFirst()
        } catch (_: Throwable) { /* diagnostics must never crash the app */ }
    }

    fun recent(): List<String> = ring.toList()
}
