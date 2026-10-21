package com.frit.app.ui.creative

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.frit.app.core.Diag
import com.frit.app.data.NetworkClient
import com.frit.app.ui.creative.components.CreativeTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun LipSyncScreen(onClose: () -> Unit = {}, onSubscribe: () -> Unit = {}, onHandoff: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = com.frit.app.features.creative.CreativeSuiteEngine
    var lipMode by remember { mutableStateOf("dub") }
    var rawSubs by remember { mutableStateOf<org.json.JSONArray?>(null) }
    var video by remember { mutableStateOf<String?>(null) }
    var photo by remember { mutableStateOf<String?>(null) }
    var audio by remember { mutableStateOf<String?>(null) }
    var language by remember { mutableStateOf("en") }
    var busy by remember { mutableStateOf(false) }
    var captions by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var engineName by remember { mutableStateOf("") }
    var resultUrl by remember { mutableStateOf<String?>(null) }
    var gate by remember { mutableStateOf<PlanGate?>(null) }

    val vidPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { video = uriToDataUrl(ctx, uri, "video/*") }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { photo = uriToDataUrl(ctx, uri, "image/*") }
    }
    val audPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { audio = uriToDataUrl(ctx, uri, "audio/*") }
    }

    LaunchedEffect(Unit) {
        engine.takeHandoff()?.let { (m, k) ->
            if (m.isBlank()) return@let
            if (k == "video") { lipMode = "dub"; video = m }
            else if (k == "image") { lipMode = "avatar"; photo = m }
            status = "Received media from another studio"
        }
    }

    fun srtText(): String {
        val arr = rawSubs ?: return ""
        val sb = StringBuilder()
        fun fmt(t: Double): String {
            val ms = (t * 1000).toInt().coerceAtLeast(0)
            return "%02d:%02d:%02d,%03d".format(ms / 3600000, (ms % 3600000) / 60000, (ms % 60000) / 1000, ms % 1000)
        }
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            sb.append("${i + 1}\n${fmt(s.optDouble("start"))} --> ${fmt(s.optDouble("end"))}\n${s.optString("text")}\n\n")
        }
        return sb.toString()
    }
    Scaffold(
        topBar = {
            CreativeTopBar(
                title = "Lip-Sync & Dub", onBack = onClose,
                saveEnabled = resultUrl != null,
                onSave = {
                    val u = resultUrl
                    if (u != null) scope.launch {
                        status = "Saving…"
                        val res = engine.saveToLibrary(u, "video", "Lip-sync")
                        status = res.getOrElse { "Save failed: ${it.message}" }.toString()
                    }
                },
                downloadEnabled = resultUrl != null,
                onDownload = {
                    val u = resultUrl
                    if (u != null) scope.launch {
                        try {
                            status = "Downloaded → " + com.frit.app.ui.creative.components.saveMediaToGallery(ctx, u, "video", "frit-lipsync")
                        } catch (e: Exception) {
                            status = "Download failed: ${e.message}"
                        }
                    }
                },
                exportEnabled = resultUrl != null,
                onExport = {
                    val u = resultUrl
                    if (u != null) { engine.sendHandoff(u, "video"); onHandoff("export") }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = lipMode == "dub", onClick = { lipMode = "dub" }, label = { Text("Dub video") })
                FilterChip(selected = lipMode == "avatar", onClick = { lipMode = "avatar" }, label = { Text("Avatar from photo") })
            }
            if (lipMode == "dub") {
                OutlinedButton(onClick = { vidPicker.launch("video/*") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Videocam, null); Spacer(Modifier.width(6.dp))
                    Text(if (video == null) "Import video" else "Video attached ✓")
                }
            } else {
                OutlinedButton(onClick = { photoPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp))
                    Text(if (photo == null) "Import portrait photo" else "Photo attached ✓")
                }
                Text("Hedra Character 3 animates one portrait — blinks, head tilts, phoneme-matched mouth.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { audPicker.launch("audio/*") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Mic, null); Spacer(Modifier.width(6.dp))
                Text(if (audio == null) "Import voiceover audio" else "Audio attached ✓")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("en", "es", "fr", "yo", "ig", "ha").forEach { l ->
                    FilterChip(selected = language == l, onClick = { language = l }, label = { Text(l.uppercase()) })
                }
            }
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        busy = true
                        resultUrl = null
                        gate = null
                        try {
                            val aud = audio
                            if (aud.isNullOrBlank()) { status = "Import voiceover audio first."; busy = false; return@launch }
                            val src = if (lipMode == "avatar") photo else video
                            if (src.isNullOrBlank()) { status = if (lipMode == "avatar") "Import a portrait photo first." else "Import a video first."; busy = false; return@launch }
                            val r = if (lipMode == "avatar") {
                                JSONObject(NetworkClient.lipsyncAvatar(src, aud, language, "9:16"))
                            } else {
                                JSONObject(NetworkClient.lipsync(src, aud, language))
                            }
                            val locked = parsePlanGate(r)
                            if (locked != null) { gate = locked; status = ""; busy = false; return@launch }
                            engineName = r.optString("engine", "local")
                            if ((engineName == "synclabs" || engineName == "hedra" || engineName == "heygen") && r.optBoolean("ok", false)) {
                                val jid = r.optString("job_id")
                                status = "Neural render queued — polling for the finished video…"
                                for (i in 0 until 24) {
                                    kotlinx.coroutines.delay(5000)
                                    try {
                                        val st = JSONObject(NetworkClient.getLipSyncStatus(jid))
                                        val stUrl = st.strOrNull("result_url")
                                        if (st.optString("status") == "done" && !stUrl.isNullOrBlank()) {
                                            resultUrl = stUrl
                                            status = "Done — neural lip-sync ready."
                                            break
                                        }
                                        if (st.optString("status") == "failed") { status = "Provider render failed — try the local dub below."; break }
                                    } catch (e: Exception) { Diag.warn("Creative", "lipsync_poll", e) }
                                }
                                if (resultUrl == null && status.startsWith("Neural render queued")) status = "Still rendering — check back shortly."
                            } else {
                                val arr = r.optJSONArray("subtitles") ?: org.json.JSONArray()
                                rawSubs = arr
                                captions = (0 until arr.length()).mapNotNull { i ->
                                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                                    "${s.optDouble("start").toInt()}s → ${s.optString("text")}"
                                }
                                status = if (r.optBoolean("ok", false)) "Job ${r.optString("job_id")} — captions aligned (local dub). ${r.optString("upgrade")}" else creativeErrorText(r)
                            }
                        } catch (e: Exception) { status = e.message ?: "Failed" }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = audio != null && (if (lipMode == "avatar") photo != null else video != null) && !busy
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp))
                else { Icon(Icons.Default.RecordVoiceOver, null); Spacer(Modifier.width(6.dp)); Text(if (lipMode == "avatar") "Animate Avatar" else "Sync Lips") }
            }
            if (engineName.isNotBlank()) Text("Engine: " + (if (engineName == "heygen") "Avatar (HeyGen)" else if (engineName == "hedra") "Avatar (Hedra)" else if (engineName == "synclabs") "Neural (Sync Labs)" else "Local dub"), style = MaterialTheme.typography.labelMedium)
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            gate?.let { g -> PlanLockedCard(message = g.message, outOfCredits = g.outOfCredits, onSubscribe = onSubscribe) }
            if (resultUrl != null) Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text("Synced video ready ✓", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                    com.frit.app.ui.creative.components.DownloadIconButton(url = resultUrl, kind = "video", onStatus = { status = it })
                }
                com.frit.app.ui.creative.components.HandoffRow(media = resultUrl, kind = "video", onHandoff = onHandoff)
            }
            if (rawSubs != null && rawSubs!!.length() > 0) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            try {
                                status = "Saved → " + com.frit.app.ui.creative.components.saveTextToDownloads(ctx, "frit-captions-${System.currentTimeMillis()}.srt", srtText())
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
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(captions) { c -> Card(Modifier.fillMaxWidth()) { Text(c, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall) } }
            }
        }
    }
}
