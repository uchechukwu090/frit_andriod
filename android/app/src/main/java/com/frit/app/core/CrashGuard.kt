package com.frit.app.core

import android.content.Context
import android.util.Log
import com.frit.app.data.NetworkClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * CrashGuard — lightweight, dependency-free crash logger.
 *
 * Installs a default uncaught-exception handler that writes a timestamped stack
 * trace to app-private storage (filesDir/crashes/). The dump stays on-device
 * (no network SDK).
 *
 * FIX (F-15): dumps are now ALSO forwarded to our own server (POST /crash,
 * rate-limited, unauthenticated, PII scrubbed via PrivacyScrubber before
 * leaving the device). Files stay on disk until the server acks — install()
 * retries the backlog, so a crash that dies mid-upload re-fires on next boot.
 * No Firebase/Sentry dependency; failure never affects crash capture.
 */
object CrashGuard {
    private const val TAG = "CrashGuard"
    private const val MAX_FILES = 20 // ring on disk: never grow unbounded
    private const val MAX_UPLOAD_PER_SWEEP = 10

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true

        val dir = File(context.filesDir, "crashes").apply { mkdirs() }
        val default = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(dir, "crash_$ts.txt")
                val sb = StringBuilder()
                sb.appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                sb.appendLine("Thread: ${thread.name}")
                sb.appendLine(Log.getStackTraceString(throwable))
                file.writeText(sb.toString())
                // FIX (F-15): best-effort immediate upload. The process may die
                // first — install()'s retry sweep on next boot covers that.
                try {
                    Thread { uploadPending(context) }.apply {
                        isDaemon = true
                        name = "CrashGuard-upload"
                        start()
                    }
                } catch (e: Exception) { Diag.warn("CrashGuard", "immediate_upload", e) }
                Log.e(TAG, "Crash captured -> ${file.absolutePath}")
            } catch (_: Exception) {
                // Never let crash logging crash.
            } finally {
                default?.uncaughtException(thread, throwable)
            }
        }

        // FIX (F-15): retry the backlog from boots where the crash died before
        // its immediate upload could finish.
        Thread { uploadPending(context) }.apply {
            isDaemon = true
            name = "CrashGuard-backlog"
            start()
        }

        pruneCrashFiles(dir)
    }

    /**
     * Push pending crash dumps to the server, oldest first. A file is deleted
     * ONLY after a successful server ack, so transient failures retry next
     * boot / next crash. Network/auth absent => leave files in place.
     */
    private fun uploadPending(context: Context) {
        try {
            if (!NetworkClient.isAuthorized()) return
            val dir = File(context.filesDir, "crashes")
            val files = dir.listFiles()?.filter { it.isFile && it.name.startsWith("crash_") }
                ?.sortedBy { it.name } ?: return
            var sent = 0
            for (file in files) {
                if (sent >= MAX_UPLOAD_PER_SWEEP) return
                val payload = crashPayload(context, file) ?: continue
                val okTxt = kotlinx.coroutines.runBlocking {
                    try { NetworkClient.sendCrash(payload) } catch (_: Exception) { "" }
                }
                val ok = runCatching {
                    JSONObject(okTxt).optBoolean("ok", false)
                }.getOrDefault(false)
                if (ok) {
                    file.delete()
                    sent++
                } else {
                    // First failure opens the sweep (likely the backend is
                    // unreachable) — stop rather than hammering every file.
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "crash upload sweep skipped: ${e.message}")
        }
    }

    /** Scrubbed payload — no screen text ever leaves with a crash report. */
    private fun crashPayload(context: Context, file: File): JSONObject? {
        return try {
            val raw = file.readText()
            val scrubbed = PrivacyScrubber.scrub(raw) // pure redaction core (PrivacyGuard.kt)
            JSONObject()
                .put("type", "uncaught")
                .put("message", scrubbed.lineSequence().firstOrNull { !it.startsWith("Time:") && !it.startsWith("Thread:") }?.take(400) ?: "")
                .put("stack", if (scrubbed.length > 4000) scrubbed.take(4000) else scrubbed)
                .put("version", try { com.frit.app.BuildConfig.VERSION_NAME } catch (_: Exception) { "" })
                .put("model", try { android.os.Build.MODEL } catch (_: Exception) { "" })
                .put("user_id", try { TokenStore.installId() } catch (_: Exception) { "" })
        } catch (_: Exception) {
            null // unreadable dump — keep it for a support pull, never crash here
        }
    }

    /** Keep the newest MAX_FILES dumps; delete older ones (disk is finite). */
    private fun pruneCrashFiles(dir: File) {
        try {
            val files = dir.listFiles()?.filter { it.isFile && it.name.startsWith("crash_") }
                ?.sortedBy { it.name } ?: return
            files.dropLast(MAX_FILES).forEach { it.delete() }
        } catch (_: Exception) { /* pruning is best-effort */ }
    }
}
