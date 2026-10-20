package com.frit.app.ui.model

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frit.app.ui.theme.Montserrat
import kotlinx.coroutines.launch

/**
 * On-device model management section, split out of MainActivity.kt
 * (audit F-14). Rendered by the Settings screen.
 */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    } catch (_: Exception) { null }
}

private fun matchTierForFile(
    context: android.content.Context,
    filename: String
): com.frit.app.core.llm.ModelManager.ModelSpec? {
    return try {
        val lower = filename.lowercase()
        // Exact tier filename wins; otherwise size hints (0.6b/1.7b/4b, q4/q8).
        when {
            "1.7b" in lower -> com.frit.app.core.llm.ModelManager.tierByName("1.7B")
            "0.6b" in lower || "0_6b" in lower -> com.frit.app.core.llm.ModelManager.tierByName("0.6B")
            "4b" in lower -> com.frit.app.core.llm.ModelManager.tierByName("4B")
            else -> null // fall through to the RAM-picked tier
        }
    } catch (_: Exception) { null }
}

// On-Device Model — the screen the offline error messages point to. Shows which
// RAM tier this phone gets, connection type, what is actually on disk, and the
// download/load controls. Rule: download ONCE (Wi-Fi or mobile data, your
// choice), then runs fully offline forever. The download runs in
// ModelDownloadService so its notification shows real bytes/speed/path and
// survives leaving this screen.
@Composable
fun OnDeviceModelSection(fg: Color, stroke: Color, bg: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Checking…") }
    var connLine by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var showMeteredDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingApproxMB by remember { mutableStateOf(0L) }
    var customUrl by remember { mutableStateOf("") }
    var partialInfo by remember { mutableStateOf<com.frit.app.core.llm.ModelManager.PartialInfo?>(null) }
    var hasCached by remember { mutableStateOf(false) }

    fun refresh() {
        try {
            val ram = com.frit.app.core.llm.ModelManager.totalRamGB(context)
            val picked = com.frit.app.core.llm.ModelManager.pickSpec(context)
            // Report the tier actually on disk, which may differ from the tier
            // this phone's RAM picks (imported file, or a smaller fallback).
            val cached = com.frit.app.core.llm.ModelManager.findCachedModel(context)
            val partial = try {
                com.frit.app.core.llm.ModelManager.partialDownload(context)
            } catch (_: Exception) { null }
            partialInfo = partial
            hasCached = cached != null
            val state = com.frit.app.core.llm.LocalLLMEngine.statusLabel(context)
            status = buildString {
                append("This phone (${"%.1f".format(ram)}GB RAM) → ${picked.filename} (~${picked.approxMB}MB)")
                if (cached != null) {
                    append("\nOn disk: ${cached.spec.filename} (${cached.sizeMB}MB)")
                    append("\nWhere: ${com.frit.app.core.llm.ModelManager.displayPath(context, cached.file)}")
                } else {
                    append("\nNothing downloaded yet")
                }
                if (partial != null && (cached == null || cached.spec.filename != partial.name)) {
                    val have = partial.bytes / 1_000_000
                    val total = if (partial.totalBytes > 0) " of ${partial.totalBytes / 1_000_000}MB" else ""
                    append("\nInterrupted: ${partial.name} — $have MB$total (resume below)")
                }
                append("\n$state")
            }
            connLine = when (com.frit.app.core.llm.ModelManager.connectionType(context)) {
                "wifi" -> "Connection: Wi-Fi — downloads free to start"
                "mobile" -> "Connection: mobile data — downloads ask first"
                else -> "Connection: offline — only an already-downloaded model can load"
            }
        } catch (e: Exception) { status = "Status unavailable." }
    }

    // Import a GGUF you already downloaded (browser/PC copy): picker → copy
    // into the app's model folder under the matching tier filename → verify
    // (when that tier has a SHA) → auto-load. No second download needed.
    // Stored in app-private storage (shown in Settings as "App storage › …").
    val ggufPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            progress = "Importing…"
            try {
                val pickedName = queryDisplayName(context, uri) ?: "model.gguf"
                // ANY GGUF works: it becomes the active model and is loaded with
                // its own embedded chat template (no Qwen-only restriction).
                val dest = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.frit.app.core.llm.ModelManager.importCustomGguf(context, uri, pickedName)
                }
                progress = "Imported ${dest.name} — loading…"
                // The file is already on disk, so this must not fetch anything.
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.frit.app.core.llm.LocalLLMEngine.unloadModel()
                    com.frit.app.core.llm.LocalLLMEngine.loadModel(context, allowDownload = false)
                }
                progress = if (result.success) "Imported + loaded — fully offline."
                else "Imported, but it would not load: ${result.error}"
            } catch (e: Exception) {
                progress = "Import failed: ${e.message}"
            }
            busy = false
            refresh()
        }
    }

    // The download itself is owned by ModelDownloadService (foreground): it
    // keeps running when the user leaves this screen and reports real bytes,
    // speed and the destination path in its notification. This screen only
    // starts it and mirrors its state.
    fun startDownload() {
        if (com.frit.app.core.llm.ModelManager.connectionType(context) == "none") {
            progress = "No internet connection — cannot download now."
            return
        }
        progress = "Starting download…"
        com.frit.app.core.llm.ModelDownloadService.start(context)
    }

    fun stopDownload() {
        com.frit.app.core.llm.ModelDownloadService.stop(context)
        progress = "Stopping…"
    }

    fun resumeDownload() {
        val p = partialInfo ?: return
        if (com.frit.app.core.llm.ModelManager.connectionType(context) == "none") {
            progress = "No internet connection — cannot resume now."
            return
        }
        progress = "Resuming ${p.name}…"
        // Null URL = built-in tier: the service re-enters the normal path and
        // Range-resumes the .part. A stored URL resumes a custom link download.
        com.frit.app.core.llm.ModelDownloadService.start(context, p.url)
    }

    fun deleteDownloads() {
        scope.launch {
            busy = true
            progress = "Stopping download…"
            com.frit.app.core.llm.ModelDownloadService.stop(context)
            com.frit.app.core.llm.LocalLLMEngine.unloadModel()
            kotlinx.coroutines.delay(800)
            val ok = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.frit.app.core.llm.ModelManager.deleteModel(context)
                }
            } catch (_: Exception) { false }
            progress = if (ok) "Deleted all downloaded model files."
            else "Delete incomplete — some files remained."
            busy = false
            refresh()
        }
    }

    // Mirror the service so the button and status reflect reality.
    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            val running = com.frit.app.core.llm.ModelDownloadService.isRunning
            if (running != downloading) {
                downloading = running
                progress = if (running) "Downloading… see the notification for bytes, speed and path."
                else "Download finished."
                refresh()
            }
            // F-29: poll fast only while a download is live; idle checks are
            // 4x cheaper (isRunning is a plain flag, so it can't be observed).
            kotlinx.coroutines.delay(if (downloading) 500L else 2000L)
        }
    }

    if (showMeteredDialog) {
        AlertDialog(
            onDismissRequest = { showMeteredDialog = false },
            title = { Text("Use mobile data?", fontFamily = Montserrat) },
            text = { Text("This download is ~${pendingApproxMB}MB on mobile data. Continue?", fontFamily = Montserrat) },
            confirmButton = {
                TextButton(onClick = { showMeteredDialog = false; startDownload() }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { showMeteredDialog = false }) { Text("Wait for Wi-Fi") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete downloaded models?", fontFamily = Montserrat) },
            text = { Text("Removes every downloaded GGUF, interrupted partials and custom imports from app storage. This cannot be undone.", fontFamily = Montserrat) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; deleteDownloads() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Keep") }
            }
        )
    }

    Column(Modifier.fillMaxWidth()) {
        Text("On-Device Model", color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
        Spacer(Modifier.height(4.dp))
        Text(status, color = fg.copy(alpha = 0.8f), fontSize = 13.sp, fontFamily = Montserrat)
        Spacer(Modifier.height(2.dp))
        Text(connLine, color = fg.copy(alpha = 0.7f), fontSize = 12.sp, fontFamily = Montserrat)
        if (progress.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(progress, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (downloading) return@Button
                    val conn = com.frit.app.core.llm.ModelManager.connectionType(context)
                    if (conn == "mobile") {
                        pendingApproxMB = try {
                            com.frit.app.core.llm.ModelManager.pickSpec(context).approxMB
                        } catch (_: Exception) { 1100L }
                        showMeteredDialog = true
                    } else startDownload()
                },
                enabled = !busy && !downloading, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = fg)
            ) { Text(if (downloading) "Downloading…" else "Download / Load", color = bg, fontWeight = FontWeight.Bold) }
            if (downloading) {
                OutlinedButton(
                    onClick = { stopDownload() },
                    modifier = Modifier.weight(1f)
                ) { Text("Stop", color = fg) }
            }
            OutlinedButton(
                onClick = {
                    com.frit.app.core.llm.LocalLLMEngine.unloadModel()
                    progress = "Unloaded from memory (file kept)."
                    refresh()
                },
                enabled = !busy && !downloading, modifier = Modifier.weight(1f)
            ) { Text("Unload", color = fg) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (partialInfo != null && !downloading) {
                Button(
                    onClick = { resumeDownload() },
                    enabled = !busy, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = fg)
                ) { Text("Resume download", color = bg, fontWeight = FontWeight.Bold) }
            }
            if (hasCached || partialInfo != null) {
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    enabled = !busy && !downloading, modifier = Modifier.weight(1f)
                ) { Text("Delete files", color = fg) }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { ggufPicker.launch("*/*") },
            enabled = !busy, modifier = Modifier.fillMaxWidth()
        ) { Text("Import GGUF file I already have", color = fg) }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = customUrl,
            onValueChange = { customUrl = it },
            label = { Text("Or paste a .gguf link (Hugging Face etc.)", fontFamily = Montserrat) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = fg, unfocusedTextColor = fg, focusedBorderColor = fg, unfocusedBorderColor = stroke)
        )
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = {
                val link = customUrl.trim()
                when {
                    downloading -> progress = "A download is already running."
                    com.frit.app.core.llm.ModelManager.connectionType(context) == "none" ->
                        progress = "No internet connection — cannot download now."
                    !link.startsWith("https://") -> progress = "Paste a full https:// link to a .gguf file."
                    else -> {
                        progress = "Starting download…"
                        com.frit.app.core.llm.ModelDownloadService.start(context, link)
                    }
                }
            },
            enabled = !busy && !downloading && customUrl.isNotBlank(), modifier = Modifier.fillMaxWidth()
        ) { Text("Download this GGUF and load it", color = fg) }
        if (com.frit.app.core.llm.ModelManager.activeCustomModel(context) != null) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    com.frit.app.core.llm.ModelManager.clearCustomSelection(context)
                    com.frit.app.core.llm.LocalLLMEngine.unloadModel()
                    progress = "Back to the built-in model (your imported file is kept on disk)."
                    refresh()
                },
                enabled = !busy && !downloading, modifier = Modifier.fillMaxWidth()
            ) { Text("Use built-in model instead", color = fg) }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Download once (Wi-Fi or mobile data — your choice). After that, Local Only works with zero internet. Keep this screen open or watch the notification — and on Tecno/Infinix/Xiaomi, set FRIT to \"Unrestricted\" battery use so the system doesn't kill long downloads.",
            color = fg.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = Montserrat
        )
    }
}
