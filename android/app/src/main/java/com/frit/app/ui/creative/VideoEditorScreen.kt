package com.frit.app.ui.creative

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.frit.app.data.NetworkClient
import com.frit.app.ui.creative.components.CreativeTopBar

import com.frit.app.ui.creative.components.TimelineScrubber
import com.frit.app.ui.creative.components.TimelineClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private fun opJson(type: String, params: JSONObject = JSONObject()): JSONObject =
    JSONObject().put("type", type).put("params", params)

@Composable
fun VideoEditorScreen(
    assetId: String = "",
    onClose: () -> Unit = {},
    onSave: (String) -> Unit = {},
    onHandoff: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = com.frit.app.features.creative.CreativeSuiteEngine
    var video by remember { mutableStateOf<String?>(null) }
    var clips by remember { mutableStateOf<List<String>>(emptyList()) }
    var startS by remember { mutableStateOf("0") }
    var endS by remember { mutableStateOf("5") }
    var captions by remember { mutableStateOf<List<String>>(emptyList()) }
    var audio by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var resultB64 by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        engine.takeHandoff()?.let { (m, k) ->
            if (k == "video" && m.isNotBlank()) {
                video = m
                status = "Received video from another studio"
            }
        }
    }

    fun resultUrl(): String? = resultB64?.let { "data:video/mp4;base64,$it" }

    fun saveResult() {
        val u = resultUrl()
        if (u == null) { status = "Nothing to save yet"; return }
        scope.launch {
            status = "Saving…"
            val res = engine.saveToLibrary(u, "video", "Edited video")
            status = res.getOrElse { "Save failed: ${it.message}" }.toString()
        }
    }

    fun downloadResult() {
        val b64 = resultB64
        if (b64.isNullOrBlank()) { status = "Nothing to download yet"; return }
        scope.launch {
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val tmp = java.io.File(ctx.cacheDir, "frit-edit-${System.currentTimeMillis()}.mp4")
                tmp.writeBytes(bytes)
                status = "Downloaded → " + com.frit.app.ui.creative.components.saveVideoFileToGallery(ctx, tmp, "frit-edit")
            } catch (e: Exception) {
                status = "Download failed: ${e.message}"
            }
        }
    }

    val vidPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { video = uriToDataUrl(ctx, uri, "video/*") }
    }
    val audPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { audio = uriToDataUrl(ctx, uri, "audio/*") }
    }
    val clipPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        scope.launch(Dispatchers.IO) {
            val added = uris.take(6 - clips.size).mapNotNull { uriToDataUrl(ctx, it, "video/*") }
            clips = (clips + added).take(6)
        }
    }

    fun runEdit(
        v: String?, a: String?, subs: JSONArray?, c: JSONArray?, ops: JSONArray,
        done: (String) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            busy = true
            try {
                val r = JSONObject(NetworkClient.editVideoOps(v, a, subs, c, ops))
                val locked = parsePlanGate(r)
                if (locked != null) {
                    done("${locked.message} — open Wallet to subscribe.")
                } else if (r.optBoolean("ok", false)) {
                    resultB64 = r.optString("video_base64").ifBlank { null }
                    val applied = r.optJSONArray("applied")
                    val lastSize = applied?.optJSONObject(applied.length() - 1)?.optInt("size") ?: 0
                    done("Done — ${applied?.length() ?: 0} op(s) applied" + (if (lastSize > 0) " • ~${lastSize / 1024} KB" else ""))
                } else done(creativeErrorText(r))
            } catch (e: Exception) { done(e.message ?: "Failed") }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            CreativeTopBar(
                title = "Video Editor", onBack = onClose,
                saveEnabled = resultB64 != null,
                onSave = { saveResult(); resultUrl()?.let(onSave) },
                downloadEnabled = resultB64 != null, onDownload = { downloadResult() },
                exportEnabled = resultB64 != null,
                onExport = {
                    val u = resultUrl()
                    if (u != null) { engine.sendHandoff(u, "video"); onHandoff("export") }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vidPicker.launch("video/*") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Videocam, null); Spacer(Modifier.width(6.dp))
                Text(if (video == null) "Import video (≤8MB)" else "Video attached ✓ — replace")
            }
            Text("Trim", style = MaterialTheme.typography.titleSmall)
            // Visual trim scrubber: drag the playhead to set the trim start.
            TimelineScrubber(
                clips = listOf(TimelineClip("main", "Clip", duration = (endS.toFloatOrNull() ?: 5f).coerceAtLeast(1f))),
                currentPosition = startS.toFloatOrNull() ?: 0f,
                totalDuration = (endS.toFloatOrNull() ?: 5f).coerceAtLeast(1f),
                onPositionChange = { startS = String.format("%.1f", it) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = startS, onValueChange = { startS = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Start (s)") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = endS, onValueChange = { endS = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("End (s)") }, modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        runEdit(video, null, null, null, JSONArray().put(opJson("trim", JSONObject().put("start_s", startS.toDoubleOrNull() ?: 0.0).put("end_s", endS.toDoubleOrNull() ?: 5.0)))) { status = it }
                    },
                    enabled = video != null && !busy
                ) { Text("Trim") }
            }
            Text("Replace audio", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { audPicker.launch("audio/*") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Mic, null); Spacer(Modifier.width(6.dp))
                    Text(if (audio == null) "Import audio" else "Audio attached ✓")
                }
                Button(
                    onClick = {
                        val a = audio
                        runEdit(video, a, null, null, JSONArray().put(opJson("replace_audio"))) { status = "Audio replaced. $it" }
                    },
                    enabled = video != null && audio != null && !busy
                ) { Text("Replace audio") }
            }
            Text("Captions", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            busy = true
                            try {
                                val src = video
                                if (src.isNullOrBlank()) { status = "Import a video first."; busy = false; return@launch }
                                val r = JSONObject(NetworkClient.generateSubtitlesFromVideo(src, "auto"))
                                val arr = r.optJSONArray("subtitles") ?: JSONArray()
                                captions = (0 until arr.length()).mapNotNull { i ->
                                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                                    "${s.optDouble("start").toInt()}s → ${s.optString("text")}"
                                }
                                val locked = parsePlanGate(r)
                                status = if (locked != null) "${locked.message} — open Wallet to subscribe."
                                    else if (captions.isEmpty()) "No speech detected" else "${captions.size} caption lines"
                            } catch (e: Exception) { status = e.message ?: "Failed" }
                            busy = false
                        }
                    },
                    modifier = Modifier.weight(1f), enabled = video != null && !busy
                ) { Text("Auto-captions") }
                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            busy = true
                            try {
                                val src = video
                                if (src.isNullOrBlank()) { status = "Import a video first."; busy = false; return@launch }
                                val r = JSONObject(NetworkClient.generateSubtitlesFromVideo(src, "auto"))
                                val locked = parsePlanGate(r)
                                if (locked != null) { status = "${locked.message} — open Wallet to subscribe."; busy = false; return@launch }
                                val subs = r.optJSONArray("subtitles") ?: JSONArray()
                                runEdit(src, null, subs, null, JSONArray().put(opJson("subtitles_burn"))) { status = "Captions burned in. $it" }
                            } catch (e: Exception) { status = e.message ?: "Failed"; busy = false }
                        }
                    },
                    modifier = Modifier.weight(1f), enabled = video != null && !busy
                ) { Text("Burn into video") }
            }
            if (captions.isNotEmpty()) {
                // Plain Column, not a nested LazyColumn: this screen already
                // scrolls, and nested scrolling lists fight for gestures.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    captions.forEach { c -> Card(Modifier.fillMaxWidth()) { Text(c, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall) } }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val srt = StringBuilder()
                                    fun fmt(t: Double): String {
                                        val ms = (t * 1000).toInt().coerceAtLeast(0)
                                        return "%02d:%02d:%02d,%03d".format(ms / 3600000, (ms % 3600000) / 60000, (ms % 60000) / 1000, ms % 1000)
                                    }
                                    // captions render as "12s → text"; rebuild rough timings.
                                    captions.forEachIndexed { i, c ->
                                        val text = c.substringAfter("→", c).trim()
                                        srt.append("${i + 1}\n${fmt(i * 2.0)} --> ${fmt(i * 2.0 + 2.0)}\n$text\n\n")
                                    }
                                    status = "Saved → " + com.frit.app.ui.creative.components.saveTextToDownloads(ctx, "frit-captions-${System.currentTimeMillis()}.srt", srt.toString())
                                } catch (e: Exception) {
                                    status = "SRT save failed: ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ClosedCaption, null); Spacer(Modifier.width(6.dp)); Text("Download captions (.srt)")
                    }
                }
            }
            Text("Concat", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = { clipPicker.launch("video/*") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add clips (${clips.size}/6)")
            }
            if (clips.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { clips = emptyList() }) { Text("Clear") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            val arr = JSONArray(); clips.forEach { arr.put(it) }
                            runEdit(null, null, null, arr, JSONArray().put(opJson("concat"))) { status = it }
                        },
                        enabled = clips.size >= 1 && !busy
                    ) { Text("Join ${clips.size} clips") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            if (resultB64 != null) {
                val kb = try { Base64.decode(resultB64, Base64.DEFAULT).size / 1024 } catch (_: Exception) { 0 }
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Text("Rendered MP4 ready • ~${kb} KB — Save to keep it.", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                        com.frit.app.ui.creative.components.DownloadIconButton(url = resultUrl(), kind = "video", onStatus = { status = it })
                    }
                }
                com.frit.app.ui.creative.components.HandoffRow(media = resultUrl(), kind = "video", onHandoff = onHandoff)
            }
        }
    }
}
