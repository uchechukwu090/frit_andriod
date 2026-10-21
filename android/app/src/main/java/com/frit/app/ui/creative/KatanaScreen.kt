package com.frit.app.ui.creative

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun KatanaScreen(onClose: () -> Unit = {}, onSubscribe: () -> Unit = {}, onHandoff: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = com.frit.app.features.creative.CreativeSuiteEngine
    var command by remember { mutableStateOf("") }
    var video by remember { mutableStateOf<String?>(null) }
    var audio by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var resultVideo by remember { mutableStateOf<String?>(null) }
    var gate by remember { mutableStateOf<PlanGate?>(null) }
    var captionLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var captionSubs by remember { mutableStateOf<org.json.JSONArray?>(null) }

    fun captionsSrt(): String {
        val arr = captionSubs ?: return ""
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

    LaunchedEffect(Unit) {
        engine.takeHandoff()?.let { (m, k) ->
            if (k == "video" && m.isNotBlank()) {
                video = m
                output = "Received video from another studio"
            }
        }
    }

    val vidPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { video = uriToDataUrl(ctx, uri, "video/*") }
    }
    val audPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { audio = uriToDataUrl(ctx, uri, "audio/*") }
    }

    Scaffold(
        topBar = {
            CreativeTopBar(
                title = "Katana — One-Command Edits", onBack = onClose,
                saveEnabled = resultVideo != null,
                onSave = {
                    val u = resultVideo
                    if (u != null) scope.launch {
                        output = engine.saveToLibrary(u, "video", command.take(60).ifBlank { "Katana cut" })
                            .getOrElse { "Save failed: ${it.message}" }.toString()
                    }
                },
                downloadEnabled = resultVideo != null,
                onDownload = {
                    val u = resultVideo
                    if (u != null) scope.launch {
                        try {
                            output = "Downloaded → " + com.frit.app.ui.creative.components.saveMediaToGallery(ctx, u, "video")
                        } catch (e: Exception) {
                            output = "Download failed: ${e.message}"
                        }
                    }
                },
                exportEnabled = resultVideo != null,
                onExport = {
                    val u = resultVideo
                    if (u != null) { engine.sendHandoff(u, "video"); onHandoff("export") }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("e.g. add English captions and export for TikTok") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vidPicker.launch("video/*") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Videocam, null); Spacer(Modifier.width(6.dp))
                    Text(if (video == null) "Attach video" else "Video attached ✓")
                }
                OutlinedButton(onClick = { audPicker.launch("audio/*") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Mic, null); Spacer(Modifier.width(6.dp))
                    Text(if (audio == null) "Attach audio" else "Audio attached ✓")
                }
            }
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        busy = true; gate = null; resultVideo = null
                        try {
                            val r = JSONObject(NetworkClient.katana(command, video, audio, "en"))
                            val locked = parsePlanGate(r)
                            if (locked != null) { gate = locked; output = "" }
                            else {
                                val res = r.optJSONArray("results")
                                val sb = StringBuilder()
                                val capLines = mutableListOf<String>()
                                var capArr: org.json.JSONArray? = null
                                if (res != null) for (i in 0 until res.length()) {
                                    val o = res.optJSONObject(i) ?: continue
                                    val vb = o.strOrNull("video_base64")
                                    val subs = o.optJSONArray("subtitles")
                                    if (vb != null) {
                                        resultVideo = "data:video/mp4;base64,$vb"
                                        sb.appendLine("• ${o.optString("type")}: rendered video ready ⬇")
                                    } else if (subs != null) {
                                        capArr = subs
                                        sb.appendLine("• ${o.optString("type")}: ${subs.length()} caption line(s) — see below")
                                        for (j in 0 until subs.length()) {
                                            val s = subs.optJSONObject(j) ?: continue
                                            capLines.add("${s.optDouble("start").toInt()}s → ${s.optString("text")}")
                                        }
                                    } else {
                                        sb.appendLine("• ${o.optString("type")}: ${o.optString("note").ifBlank { o.optString("error", "done") }}")
                                    }
                                }
                                captionSubs = capArr
                                captionLines = capLines
                                // The chained result (each op's output fed the next).
                                val finalVb = r.strOrNull("video_base64")
                                if (finalVb != null) resultVideo = "data:video/mp4;base64,$finalVb"
                                if (sb.isEmpty()) {
                                    val ops = r.optJSONArray("ops")
                                    if (ops != null) for (i in 0 until ops.length()) {
                                        val o = ops.optJSONObject(i) ?: continue
                                        sb.appendLine("• ${o.optString("type")}: ${o.optJSONObject("params")}")
                                    }
                                }
                                output = (if (r.optString("reply").isNotBlank()) r.optString("reply") + "\n" else "") + sb.toString().ifBlank {
                                    if (r.optBoolean("ok", false)) "Command accepted — no ops returned." else creativeErrorText(r)
                                }
                            }
                        } catch (e: Exception) { output = e.message ?: "Failed" }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = command.isNotBlank() && !busy
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp))
                else { Icon(Icons.Default.Bolt, null); Spacer(Modifier.width(6.dp)); Text("Run Command") }
            }
            if (output.isNotBlank()) Card(Modifier.fillMaxWidth()) { Text(output, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            if (captionLines.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Captions (${captionLines.size})", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        captionLines.take(50).forEach { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            try {
                                output = "Saved → " + com.frit.app.ui.creative.components.saveTextToDownloads(ctx, "frit-katana-${System.currentTimeMillis()}.srt", captionsSrt())
                            } catch (e: Exception) { output = "SRT save failed: ${e.message}" }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ClosedCaption, null); Spacer(Modifier.width(6.dp)); Text("Download captions (.srt)")
                }
            }
            if (resultVideo != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    com.frit.app.ui.creative.components.DownloadIconButton(url = resultVideo, kind = "video", onStatus = { output = it })
                }
                com.frit.app.ui.creative.components.HandoffRow(media = resultVideo, kind = "video", onHandoff = onHandoff)
            }
            gate?.let { g -> PlanLockedCard(message = g.message, outOfCredits = g.outOfCredits, onSubscribe = onSubscribe) }
        }
    }
}
