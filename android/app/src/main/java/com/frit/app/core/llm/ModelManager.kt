package com.frit.app.core.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Manages downloading and caching of GGUF model files from HuggingFace.
 *
 * RAM-tiered: the app runs the biggest Qwen3 it can hold (0.6B <3.5GB RAM,
 * 1.7B Q4_K_M at 3.5–6GB, 4B Q4_K_M above 6GB), falling back to smaller tiers
 * if a download fails. llama.cpp-compatible dense transformers; runs on the
 * 3GB-RAM phone profile at ~1.6-1.8GB peak for the 1.7B tier.
 *
 * Vosk-style bundled loading: if the GGUF is shipped inside the APK at
 * `assets/models/Qwen3-1.7B-Q4_K_M.gguf`, it is unpacked to internal storage
 * on first use (no download). Otherwise the file is downloaded from
 * HuggingFace once and cached. Either way the result is the same cached file.
 */
object ModelManager {
    private const val TAG = "ModelManager"
    private const val MODEL_DIR = "models"

    // Model tiers by DEVICE RAM — the app runs the biggest Qwen3 it can hold:
    //   <3.5GB RAM  -> Qwen3-0.6B Q4_K_M (~0.4GB, basic intents only)
    //   3.5–6GB RAM -> Qwen3-1.7B Q4_K_M (~1.1GB, the standard pick)
    //   >6GB RAM    -> Qwen3-4B Q4_K_M (~2.5GB, best on-device reasoning)
    // NOTE: this build runs llama.cpp on CPU (GGML_USE_CPU), so there is no
    // separate VRAM pool to size against — total system RAM is the budget.
    // sha256 is enforced only where filled in (1.7B below). To lock another
    // tier: download once on PC, run `certutil -hashfile file.gguf SHA256`
    // (Windows) or `sha256sum` (Linux), paste the 64 hex chars into its slot.
    // A WRONG hash bricks that tier (downloads always rejected) — blank it to
    // fall back to HTTPS + size checks.
    data class ModelSpec(
        val url: String,
        val filename: String,
        val sha256: String,
        val minRamGB: Double,
        val approxMB: Long = 0L
    )

    private val MODEL_TIERS = listOf(
        ModelSpec(
            "https://huggingface.co/lmstudio-community/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
            "Qwen3-0.6B-Q4_K_M.gguf",
            "",
            0.0,
            400L
        ),
        ModelSpec(
            "https://huggingface.co/lmstudio-community/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
            "Qwen3-1.7B-Q4_K_M.gguf",
            "e0801cbda7e2f3fd00bea4d73b53b422b14b13aa130e778f6414b6b641920b7e",
            3.5,
            1140L
        ),
        ModelSpec(
            "https://huggingface.co/lmstudio-community/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
            "Qwen3-4B-Q4_K_M.gguf",
            "",
            6.0,
            2500L
        )
    )

    /** Connection type: "wifi" (unmetered), "mobile" (metered), or "none". */
    fun connectionType(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork ?: return "none"
            val caps = cm.getNetworkCapabilities(net) ?: return "none"
            if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "none"
            if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "wifi" else "mobile"
        } catch (_: Exception) { "none" }
    }

    // Legacy single-model constants (kept for callers): the 1.7B standard tier.
    private const val MODEL_URL = "https://huggingface.co/lmstudio-community/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
    private const val MODEL_FILENAME = "Qwen3-1.7B-Q4_K_M.gguf"

    private const val MODEL_ASSET_DIR = "models"

    // sha256 of a fully-downloaded model file. If you change MODEL_URL/FILENAME
    // set this to the correct digest (sha256sum file.gguf) or the download will
    // be rejected as corrupt. Empty string = verification disabled.
    private const val MODEL_SHA256 = "e0801cbda7e2f3fd00bea4d73b53b422b14b13aa130e778f6414b6b641920b7e"

    /** Total device RAM in GB — the budget the tier table sizes against. */
    fun totalRamGB(context: Context): Double {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem / 1_073_741_824.0
        } catch (_: Exception) { 4.0 }
    }

    /** Biggest tier this phone can hold. */
    fun pickSpec(context: Context): ModelSpec {
        val ram = totalRamGB(context)
        return MODEL_TIERS.filter { ram >= it.minRamGB }.maxByOrNull { it.minRamGB } ?: MODEL_TIERS.first()
    }

    /** Find a tier by size tag ("0.6B", "1.7B", "4B") — for matching user-supplied files. */
    fun tierByName(tag: String): ModelSpec? {
        val t = tag.lowercase().replace(" ", "")
        return MODEL_TIERS.firstOrNull {
            it.filename.lowercase().contains(t) ||
                (t == "1.7b" && "1.7b" in it.filename.lowercase())
        }
    }

    /**
     * Import a user-supplied GGUF (file picker / PC copy) into the app-private
     * model folder under the tier filename, checksum it when that tier has a
     * SHA, and return the file. Throws on failure. Afterwards loadModel()
     * picks it up with no download — it auto-loads on next use.
     */
    suspend fun importGguf(context: Context, uri: android.net.Uri, spec: ModelSpec): java.io.File =
        withContext(Dispatchers.IO) {
            val modelDir = File(context.filesDir, MODEL_DIR).also { it.mkdirs() }
            val dest = File(modelDir, spec.filename)
            val tmp = File(modelDir, spec.filename + ".import-tmp")
            try {
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    java.io.FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var total = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            if (total > 6L * 1024 * 1024 * 1024) throw java.io.IOException("File over 6GB — not a Qwen3 tier GGUF")
                        }
                    }
                } ?: throw java.io.IOException("Could not open picked file")
                if (tmp.length() <= 1_000_000) throw java.io.IOException("File too small to be a model")
                if (spec.sha256.isNotBlank() && !verifyChecksum(tmp, spec.sha256)) {
                    throw java.io.IOException("Checksum mismatch — wrong file for tier ${spec.filename}")
                }
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) throw java.io.IOException("Could not store model file")
                Log.i(TAG, "Imported ${spec.filename} (${dest.length() / 1_000_000}MB)")
                dest
            } finally {
                runCatching { if (tmp.exists() && tmp.length() <= 1_000_000) tmp.delete() }
            }
        }

    data class DownloadProgress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val percent: Int,
        val speedKbps: Long = 0L
    )

    /**
     * A GGUF that is actually on disk and passes that tier's integrity check.
     * Scans EVERY tier, not just the one this phone's RAM picks — a model
     * downloaded on another device (or a smaller fallback tier) is still a
     * real, usable model and must be reported as such.
     */
    data class CachedModel(val file: File, val spec: ModelSpec) {
        val sizeMB: Long get() = file.length() / 1_000_000
        val path: String get() = file.absolutePath
    }

    fun findCachedModel(context: Context): CachedModel? {
        // A GGUF the user picked/downloaded themselves always wins.
        activeCustomModel(context)?.let { return CachedModel(it, customSpec(it)) }
        val dir = File(context.filesDir, MODEL_DIR)
        // Biggest tier first: if several are present, that is the one to run.
        return MODEL_TIERS.sortedByDescending { it.minRamGB }.firstNotNullOfOrNull { spec ->
            val f = File(dir, spec.filename)
            if (isComplete(f, spec)) CachedModel(f, spec) else null
        }
    }

    /**
     * Checksum a file ONCE and remember the result. Re-hashing a 1-2GB GGUF on
     * every load took tens of seconds on a phone, which is why chat kept falling
     * through to the cloud while the model "loaded".
     */
    private fun isVerified(file: File, spec: ModelSpec): Boolean {
        if (spec.sha256.isBlank()) return true
        val marker = File(file.parentFile, file.name + ".verified")
        val stamp = "${file.length()}:${spec.sha256}"
        if (marker.exists() && runCatching { marker.readText() }.getOrNull() == stamp) return true
        val ok = verifyChecksum(file, spec.sha256)
        if (ok) runCatching { marker.writeText(stamp) }
        return ok
    }

    /** Whole file on disk: right size for its tier (a half-finished download never counts) and verified. */
    private fun isComplete(file: File, spec: ModelSpec): Boolean {
        if (!file.exists() || file.length() <= 1_000_000) return false
        if (spec.approxMB > 0 && file.length() < spec.approxMB * 1_000_000L * 85L / 100L) return false
        return isVerified(file, spec)
    }

    /**
     * Older builds wrote straight to the final filename, so an interrupted download
     * looked like a finished model. Re-label such a file as a resumable partial.
     */
    private fun adoptLegacyPartial(file: File, spec: ModelSpec) {
        if (!file.exists() || spec.approxMB <= 0) return
        if (file.length() >= spec.approxMB * 1_000_000L * 85L / 100L) return
        val part = File(file.parentFile, file.name + ".part")
        if (part.exists()) { runCatching { file.delete() }; return }
        runCatching { file.renameTo(part) }
    }

    data class PartialInfo(val name: String, val bytes: Long, val totalBytes: Long, val url: String? = null)

    /** An interrupted download that can be resumed, if any. */
    fun partialDownload(context: Context): PartialInfo? {
        return try {
            val modelDir = File(context.filesDir, MODEL_DIR).also { it.mkdirs() }
            MODEL_TIERS.forEach { adoptLegacyPartial(File(modelDir, it.filename), it) }
            val parts = listOf(modelDir, customDir(context))
                .flatMap { d -> d.listFiles()?.filter { it.isFile && it.name.endsWith(".part") && it.length() > 0 }.orEmpty() }
            val p = parts.maxByOrNull { it.lastModified() } ?: return null
            val total = runCatching { File(p.parentFile, p.name + ".meta").readText().trim().toLong() }.getOrNull()
                ?: MODEL_TIERS.firstOrNull { p.name.startsWith(it.filename) }?.let { it.approxMB * 1_000_000L }
                ?: 0L
            // Custom-URL downloads stash their link beside the partial so
            // Resume can restart the exact same download after a reboot.
            val base = p.name.removeSuffix(".part")
            val url = runCatching { File(p.parentFile, "$base.url").readText().trim() }.getOrNull()?.ifBlank { null }
            PartialInfo(base, p.length(), total, url)
        } catch (_: Exception) { null }
    }

    /**
     * Human location label for UI/notifications. Internal storage
     * (/data/user/0/…) is invisible in file managers, so never show the raw
     * absolute path — users rightly report it as "not found".
     */
    fun displayPath(context: Context, file: File): String {
        return try {
            val root = context.filesDir.absolutePath
            val rel = if (file.absolutePath.startsWith(root))
                file.absolutePath.removePrefix(root).trimStart('/') else file.name
            "App storage › $rel"
        } catch (_: Exception) { file.name }
    }

    // ---- Custom (user-chosen) GGUF: any model, imported from a file or downloaded by URL ----
    private const val CUSTOM_DIR = "models/custom"
    private const val CUSTOM_ACTIVE = "models/active_custom.txt"

    private fun customDir(context: Context): File = File(context.filesDir, CUSTOM_DIR).also { it.mkdirs() }

    fun activeCustomModel(context: Context): File? {
        return try {
            val marker = File(context.filesDir, CUSTOM_ACTIVE)
            val name = if (marker.exists()) marker.readText().trim() else ""
            if (name.isBlank()) null
            else File(customDir(context), name).takeIf { it.exists() && it.length() > 1_000_000 }
        } catch (_: Exception) { null }
    }

    private fun setActiveCustom(context: Context, name: String?) {
        val f = File(context.filesDir, CUSTOM_ACTIVE)
        if (name == null) { f.delete(); return }
        f.parentFile?.mkdirs()
        f.writeText(name)
    }

    /** Go back to the built-in RAM-tier model (keeps the custom file on disk). */
    fun clearCustomSelection(context: Context) = setActiveCustom(context, null)

    private fun customSpec(file: File) = ModelSpec("", file.name, "", 0.0, file.length() / 1_000_000)

    private fun looksLikeGguf(file: File): Boolean = try {
        file.inputStream().use { ins ->
            val b = ByteArray(4)
            ins.read(b) == 4 && String(b, Charsets.US_ASCII) == "GGUF"
        }
    } catch (_: Exception) { false }

    private fun safeName(raw: String): String {
        var n = raw.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (n.isBlank() || n == ".gguf") n = "model"
        if (!n.lowercase(Locale.ROOT).endsWith(".gguf")) n += ".gguf"
        return n
    }

    /** Import ANY GGUF from the file picker and make it the active model. */
    suspend fun importCustomGguf(context: Context, uri: android.net.Uri, displayName: String): File =
        withContext(Dispatchers.IO) {
            val dir = customDir(context)
            val name = safeName(displayName)
            val dest = File(dir, name)
            val tmp = File(dir, "$name.import-tmp")
            try {
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                } ?: throw java.io.IOException("Could not open picked file")
                if (tmp.length() <= 1_000_000) throw java.io.IOException("File too small to be a model")
                if (!looksLikeGguf(tmp)) throw java.io.IOException("That is not a GGUF file")
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) throw java.io.IOException("Could not store model file")
                setActiveCustom(context, name)
                Log.i(TAG, "Imported custom model $name (${dest.length() / 1_000_000}MB)")
                dest
            } finally {
                runCatching { if (tmp.exists()) tmp.delete() }
            }
        }

    /** Download ANY GGUF from an https link (Hugging Face blob links are fixed up) and make it active. */
    suspend fun downloadCustomGguf(
        context: Context,
        url: String,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        val clean = url.trim().replace("/blob/", "/resolve/")
        if (!clean.startsWith("https://")) throw java.io.IOException("Use an https:// link to a .gguf file")
        val name = safeName(clean.substringBefore('?'))
        val dest = File(customDir(context), name)
        // Remember the link BEFORE downloading so a killed app can resume
        // this exact download later. downloadSpec resumes the .part itself;
        // no premature delete here (that would destroy resume).
        runCatching { File(customDir(context), "$name.url").writeText(clean) }
        try {
            downloadSpec(ModelSpec(clean, name, "", 0.0, 0L), dest, onProgress)
        } catch (e: Exception) {
            // Keep .part + .url: the UI Resume button continues from here.
            throw e
        }
        runCatching { File(customDir(context), "$name.url").delete() }
        if (!looksLikeGguf(dest)) {
            dest.delete()
            throw java.io.IOException("That link did not return a GGUF file")
        }
        setActiveCustom(context, name)
        dest
    }

    /** Result of resolving a model: where it is, which tier, and whether it was already on disk. */
    data class ModelPathResult(val path: String, val spec: ModelSpec, val fromCache: Boolean)

    /**
     * Get the model file path. Picks the biggest tier the phone's RAM can
     * hold, downloads if not cached (resumable), falls back to smaller tiers
     * if a tier's download fails.
     *
     * @param allowDownload false = cached files only, never touch the network.
     *   Startup preloading uses this so the app can never download a model
     *   without the user asking for it from Settings.
     * @param confirmMetered called once when the connection is metered
     *   (mobile data) before any byte is fetched — return false to stay paused.
     *   Null = download regardless of connection.
     */
    suspend fun getModelPath(
        context: Context,
        onProgress: ((DownloadProgress) -> Unit)? = null,
        confirmMetered: (suspend (approxMB: Long) -> Boolean)? = null,
        allowDownload: Boolean = true
    ): String = getModelPathResult(context, onProgress, confirmMetered, allowDownload).path

    suspend fun getModelPathResult(
        context: Context,
        onProgress: ((DownloadProgress) -> Unit)? = null,
        confirmMetered: (suspend (approxMB: Long) -> Boolean)? = null,
        allowDownload: Boolean = true
    ): ModelPathResult = withContext(Dispatchers.IO) {
        val modelDir = File(context.filesDir, MODEL_DIR).also { it.mkdirs() }
        // A user-chosen GGUF (imported or downloaded by link) always wins.
        activeCustomModel(context)?.let { custom ->
            return@withContext ModelPathResult(custom.absolutePath, customSpec(custom), fromCache = true)
        }
        val picked = pickSpec(context)
        Log.i(TAG, "Device RAM ${"%.1f".format(totalRamGB(context))}GB -> tier ${picked.filename}")
        // Candidate tiers: picked first, then smaller ones as fallback.
        val candidates = (listOf(picked) + MODEL_TIERS.filter { it.minRamGB < picked.minRamGB }.sortedByDescending { it.minRamGB }).distinctBy { it.filename }
        var lastError: Exception? = null
        for (spec in candidates) {
            val modelFile = File(modelDir, spec.filename)
            try {
                adoptLegacyPartial(modelFile, spec)
                if (isComplete(modelFile, spec)) {
                    Log.i(TAG, "Model already cached: ${modelFile.absolutePath} (${modelFile.length() / 1_000_000}MB)")
                    return@withContext ModelPathResult(modelFile.absolutePath, spec, fromCache = true)
                } else if (allowDownload && modelFile.exists() && spec.sha256.isNotBlank()) {
                    // Corrupt cached file: only discard it when we are allowed to
                    // re-download. A cache-only load must never delete the user's file.
                    // Cached file is corrupt — drop it so we re-download cleanly.
                    Log.w(TAG, "Cached model failed checksum; re-downloading.")
                    modelFile.delete()
                }

                if (!allowDownload) {
                    // Caller asked for cached-only (startup preload). Do NOT
                    // cascade to smaller tiers — that would just re-scan and
                    // still download nothing, while hiding the real reason.
                    lastError = java.io.IOException("Model not downloaded yet")
                    continue
                }

                // Vosk-style step 1: prefer a GGUF bundled in assets/models/ (ships in
                // the APK, zero network). Falls through to download when not bundled.
                if ((!modelFile.exists() || modelFile.length() <= 1_000_000) &&
                    tryUnpackBundledModel(context, modelFile, spec, onProgress)
                ) {
                    return@withContext ModelPathResult(modelFile.absolutePath, spec, fromCache = false)
                }

                if (connectionType(context) == "mobile" && confirmMetered != null && !confirmMetered(spec.approxMB)) {
                    throw java.io.IOException("Paused — waiting for Wi-Fi (mobile-data download not confirmed)")
                }
                downloadSpec(spec, modelFile, onProgress)
                return@withContext ModelPathResult(modelFile.absolutePath, spec, fromCache = false)
            } catch (e: Exception) {
                // User-declined mobile data pauses everything at once — do NOT
                // cascade through smaller tiers (they'd ask again each time).
                if (e.message?.contains("waiting for Wi-Fi") == true) throw e
                Log.w(TAG, "Tier ${spec.filename} failed (${e.message}); trying smaller tier.", e)
                runCatching { File(modelDir, spec.filename).takeIf { it.length() <= 1_000_000 }?.delete() }
                lastError = e
            }
        }
        throw lastError ?: java.io.IOException("No model tier could be loaded.")
    }

    /** Resumable download of one spec into dest. Throws on failure. */
    /**
     * Resumable download with automatic retry. A dropped connection (app minimised,
     * Wi-Fi blip, network switch) resumes from the last byte instead of failing.
     */
    private suspend fun downloadSpec(
        spec: ModelSpec,
        dest: File,
        onProgress: ((DownloadProgress) -> Unit)?
    ) {
        var attempt = 0
        while (true) {
            try {
                downloadSpecOnce(spec, dest, onProgress)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                attempt++
                val msg = e.message ?: ""
                val permanent = msg.contains("HTTP 4") || msg.contains("checksum") || msg.contains("finalise")
                if (attempt >= 6 || permanent) throw e
                Log.w(TAG, "Download interrupted ($msg); resuming, attempt $attempt")
                kotlinx.coroutines.delay(2000L * attempt)
            }
        }
    }

    /**
     * One pass. Bytes go to "<name>.part" and the file is only renamed to its real
     * name once it is COMPLETE, so an interrupted download can never pass for a model.
     */
    private suspend fun downloadSpecOnce(
        spec: ModelSpec,
        finalDest: File,
        onProgress: ((DownloadProgress) -> Unit)?
    ) {
        val dest = File(finalDest.parentFile, finalDest.name + ".part")
        // Download model (resumable: a dead 1.1GB download restarts from its
        // last byte instead of zero). Server must allow Range requests
        // (HuggingFace does).
        Log.i(TAG, "Downloading model from ${spec.url} ...")
        val existing = if (dest.exists()) dest.length() else 0L
        val url = URL(spec.url)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        if (existing > 1_000_000) {
            connection.setRequestProperty("Range", "bytes=$existing-")
            Log.i(TAG, "Resuming download from byte $existing")
        }
        connection.connect()

        if (connection.responseCode == 416) {
            // Our partial file no longer matches the server's file: start over cleanly.
            connection.disconnect()
            dest.delete()
            throw java.io.IOException("Partial file out of date — restarting from zero")
        }
        if (connection.responseCode !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
            connection.disconnect()
            throw java.io.IOException("Model download HTTP ${connection.responseCode}")
        }
        val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (!resumed && existing > 1_000_000) {
            // Server ignored Range — restart cleanly rather than appending
            // garbage to a partial file.
            Log.w(TAG, "Server ignored Range; restarting download from zero.")
            dest.delete()
        }
        val totalBytes = if (resumed) existing + connection.contentLengthLong else connection.contentLengthLong
        val inputStream = connection.inputStream
        runCatching { File(dest.parentFile, dest.name + ".meta").writeText(totalBytes.toString()) }
        val outputStream = FileOutputStream(dest, resumed)

        val buffer = ByteArray(8192)
        var bytesRead: Int
        var totalRead = if (resumed) existing else 0L
        val startTime = System.currentTimeMillis()
        // Last percent we told the UI about. Reporting on change (not on a
        // `percent % 10 == 0` test) matters: a read that straddles a multiple of
        // 10 makes that test false for every later bar, freezing the notification.
        var lastReportedPercent = -1

        try {
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead

                if (onProgress != null) {
                    val percent = if (totalBytes > 0) ((totalRead * 100) / totalBytes).toInt() else -1
                    if (percent != lastReportedPercent || totalRead == totalBytes) {
                        lastReportedPercent = percent
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000
                        val speed = if (elapsed > 0) totalRead / elapsed / 1024 else 0
                        Log.d(TAG, "Download: $percent% ($totalRead/$totalBytes bytes, ${speed}KB/s)")
                        withContext(Dispatchers.Main) {
                            onProgress(DownloadProgress(totalRead, totalBytes, percent, speed))
                        }
                    }
                }
            }
        } finally {
            runCatching { outputStream.close() }
            runCatching { inputStream.close() }
            connection.disconnect()
        }

        // A stream that ends early (connection dropped) must NOT count as finished.
        if (totalBytes > 0 && dest.length() < totalBytes) {
            throw java.io.IOException("Connection dropped at ${dest.length() / 1_000_000}MB of ${totalBytes / 1_000_000}MB")
        }

        // Integrity check: a corrupt/interrupted download must not be kept.
        if (spec.sha256.isNotBlank() && !verifyChecksum(dest, spec.sha256)) {
            dest.delete()
            throw java.io.IOException("Model download failed checksum verification and was discarded. If this repeats, the SHA in ModelManager is wrong — blank it to fall back to HTTPS + size checks.")
        }

        runCatching { File(dest.parentFile, dest.name + ".meta").delete() }
        if (finalDest.exists()) finalDest.delete()
        if (!dest.renameTo(finalDest)) throw java.io.IOException("Could not finalise the downloaded file")
        Log.i(TAG, "Model downloaded: ${finalDest.name} (${finalDest.length() / 1_000_000}MB)")
    }

    /**
     * Copy the APK-bundled GGUF (assets/models/…) to internal storage, same as
     * VoskSttEngine unpacks its model. Returns false when nothing is bundled
     * so the caller falls through to the HuggingFace download.
     */
    /** Resolve the bundled GGUF: this tier's filename first, then any *.gguf in assets/models/. */
    private fun resolveBundledAsset(context: Context, spec: ModelSpec): String? {
        // 1. Exact expected name (fast path).
        val exact = "$MODEL_ASSET_DIR/${spec.filename}"
        try {
            context.assets.openFd(exact).close()
            return exact
        } catch (_: Exception) {
            try {
                context.assets.open(exact).close()
                return exact
            } catch (_: Exception) { /* fall through to scan */ }
        }
        // 2. Vosk-style scan: accept any .gguf dropped in assets/models/.
        val names = try {
            context.assets.list(MODEL_ASSET_DIR)
        } catch (_: Exception) { null } ?: return null
        val gguf = names.firstOrNull { it.endsWith(".gguf", ignoreCase = true) }
            ?: return null
        return "$MODEL_ASSET_DIR/$gguf"
    }

    private suspend fun tryUnpackBundledModel(
        context: Context,
        dest: File,
        spec: ModelSpec,
        onProgress: ((DownloadProgress) -> Unit)?
    ): Boolean {
        val assetPath = resolveBundledAsset(context, spec) ?: return false // Not bundled — download instead.
        val input = try {
            context.assets.open(assetPath)
        } catch (_: Exception) {
            return false
        }
        Log.i(TAG, "Unpacking bundled model from assets/$assetPath ...")
        val startTime = System.currentTimeMillis()
        // Prefer AssetFileDescriptor length (exact for stored/noCompress
        // assets, works past 1GB). available() lies on big files, so it is
        // only a last-resort hint — copy works even when total is unknown.
        val totalFromFd: Long = try {
            context.assets.openFd(assetPath).use { it.length }
        } catch (_: Exception) { -1L }
        try {
            input.use { ins ->
                FileOutputStream(dest).use { out ->
                    var total = totalFromFd
                    if (total <= 0) {
                        total = try { ins.available().toLong() } catch (_: Exception) { -1L }
                    }
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    var lastReported = -1
                    while (true) {
                        val n = ins.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        copied += n
                        if (onProgress != null && total > 0) {
                            val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
                            if (percent >= lastReported + 5 || copied == total) {
                                lastReported = percent
                                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                                val speed = if (elapsed > 0) copied / elapsed / 1024 else 0
                                Log.d(TAG, "Unpack: $percent% ($copied/$total bytes, ${speed}KB/s)")
                                withContext(Dispatchers.Main) {
                                    onProgress(DownloadProgress(copied, total, percent))
                                }
                            }
                        }
                    }
                    out.fd.sync()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bundled model unpack failed; will try download instead.", e)
            runCatching { dest.delete() }
            return false
        }
        if (dest.length() <= 1_000_000 ||
            (spec.sha256.isNotBlank() && !verifyChecksum(dest, spec.sha256))
        ) {
            Log.w(TAG, "Bundled model invalid; discarding and downloading instead.")
            runCatching { dest.delete() }
            return false
        }
        Log.i(TAG, "Model unpacked to ${dest.absolutePath} (${dest.length() / 1_000_000}MB)")
        return true
    }

    private fun verifyChecksum(file: File, expectedSha256: String): Boolean {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            hex == expectedSha256.lowercase(Locale.ROOT)
        } catch (e: Exception) {
            Log.e(TAG, "Checksum failed", e)
            false
        }
    }

    /** Back-compat single-arg check against the legacy 1.7B constant. */
    @Suppress("unused")
    private fun verifyChecksum(file: File): Boolean = verifyChecksum(file, MODEL_SHA256)

    fun isModelCached(context: Context): Boolean = findCachedModel(context) != null

    fun getCachedSize(context: Context): Long = findCachedModel(context)?.file?.length() ?: 0L

    /** Name of the tier actually on disk, or null when nothing is downloaded. */
    fun cachedTierName(context: Context): String? = findCachedModel(context)?.spec?.filename

    /** Absolute path of the cached model file, or null when nothing is downloaded. */
    fun cachedModelPath(context: Context): String? = findCachedModel(context)?.file?.absolutePath

    fun deleteModel(context: Context): Boolean {
        // Deletes whichever tier is cached (all tiers, to be safe) plus any custom model.
        var ok = true
        runCatching {
            // Everything: finished files, resumable partials, size notes, checksum markers.
            File(context.filesDir, MODEL_DIR).listFiles()?.forEach { if (it.isFile && !it.delete()) ok = false }
            customDir(context).listFiles()?.forEach { if (!it.delete()) ok = false }
            setActiveCustom(context, null)
        }
        for (spec in MODEL_TIERS) {
            val f = File(File(context.filesDir, MODEL_DIR), spec.filename)
            if (f.exists() && !f.delete()) ok = false
        }
        return ok
    }

    fun getModelUrl(): String = MODEL_URL
    fun getModelFileName(): String = MODEL_FILENAME
}
