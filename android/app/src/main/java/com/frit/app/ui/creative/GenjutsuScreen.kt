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
import org.json.JSONArray
import org.json.JSONObject

val GENJUTSU_PRESETS = listOf("Cyberpunk City", "Anime Style", "Noir Film", "Golden Epic", "Wardrobe Swap", "Product Swap", "Live-Action")

@Composable
fun GenjutsuScreen(onClose: () -> Unit = {}, onSubscribe: () -> Unit = {}, onHandoff: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = com.frit.app.features.creative.CreativeSuiteEngine
    var mode by remember { mutableStateOf("motion_transfer") }
    var video by remember { mutableStateOf<String?>(null) }
    var refs by remember { mutableStateOf<List<String>>(emptyList()) }
    var description by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var videoFile by remember { mutableStateOf<java.io.File?>(null) }
    var gate by remember { mutableStateOf<PlanGate?>(null) }

    LaunchedEffect(Unit) {
        engine.takeHandoff()?.let { (m, k) ->
            if (k == "video" && m.isNotBlank()) video = m
        }
    }

    fun downloadResult() {
        val f = videoFile
        if (f == null) { output = "Nothing to download yet"; return }
        scope.launch {
            try {
                output = "Downloaded → " + com.frit.app.ui.creative.components.saveVideoFileToGallery(ctx, f, "frit-genjutsu")
            } catch (e: Exception) {
                output = "Download failed: ${e.message}"
            }
        }
    }

    fun saveResult() {
        val f = videoFile
        if (f == null) { output = "Nothing to save yet"; return }
        scope.launch {
            output = "Saving…"
            try {
                val bytes = f.readBytes()
                val url = "data:video/mp4;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val res = engine.saveToLibrary(url, "video", "Genjutsu $mode")
                output = res.getOrElse { "Save failed: ${it.message}" }.toString()
            } catch (e: Exception) {
                output = "Save failed: ${e.message}"
            }
        }
    }

    val vidPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { video = uriToDataUrl(ctx, uri, "video/*") }
    }
    val refPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        scope.launch(Dispatchers.IO) {
            val added = uris.take(30 - refs.size).mapNotNull { uriToDataUrl(ctx, it, "image/*") }
            refs = (refs + added).take(30)
        }
    }

    Scaffold(
        topBar = {
            CreativeTopBar(
                title = "Genjutsu", onBack = onClose,
                saveEnabled = videoFile != null, onSave = { saveResult() },
                downloadEnabled = videoFile != null, onDownload = { downloadResult() },
                exportEnabled = videoFile != null,
                onExport = {
                    if (videoFile != null) {
                        try {
                            val bytes = videoFile!!.readBytes()
                            if (bytes.size <= 8 * 1024 * 1024) {
                                engine.sendHandoff("data:video/mp4;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP), "video")
                                onHandoff("export")
                            } else {
                                output = "Video too large to hand off — download it instead"
                            }
                        } catch (e: Exception) {
                            output = "Export failed: ${e.message}"
                        }
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == "motion_transfer", onClick = { mode = "motion_transfer" }, label = { Text("Motion Transfer") })
                FilterChip(selected = mode == "object_swap", onClick = { mode = "object_swap" }, label = { Text("Object Swap") })
            }
            Text(if (mode == "motion_transfer") "Keep motion, camera & timing — rebuild the scene from references." else "Swap one element — keep the rest of the shot intact.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { vidPicker.launch("video/*") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Videocam, null); Spacer(Modifier.width(6.dp))
                Text(if (video == null) "Upload source video (4–30s)" else "Source video attached ✓")
            }
            OutlinedButton(onClick = { refPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text("Add references (${refs.size}/30)")
            }
            if (refs.isNotEmpty()) TextButton(onClick = { refs = emptyList() }) { Text("Clear references") }
            Text("Presets", style = MaterialTheme.typography.labelMedium)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GENJUTSU_PRESETS.forEach { p ->
                    FilterChip(selected = preset == p, onClick = { preset = if (preset == p) null else p }, label = { Text(p) })
                }
            }
            OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Describe the change (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        busy = true; gate = null; videoFile = null
                        try {
                            val src = video
                            if (src.isNullOrBlank()) { output = "Attach a source video first."; busy = false; return@launch }
                            val arr = JSONArray(); refs.forEach { arr.put(it) }
                            val r = JSONObject(NetworkClient.genjutsu(mode, src, arr, description.ifBlank { null }, preset))
                            val locked = parsePlanGate(r)
                            if (locked != null) { gate = locked; output = "" }
                            else if (r.optBoolean("ok", false)) {
                                val pid = providerJobId(r)
                                if (pid == null) {
                                    output = "Job accepted but no render id came back — try again."
                                } else {
                                    output = "Rendering…"
                                    val f = awaitVideoFile(ctx, pid) { output = it }
                                    if (f != null) { videoFile = f; output = "Done — transformed video ready ⬇" }
                                }
                            } else output = creativeErrorText(r)
                        } catch (e: Exception) { output = e.message ?: "Failed" }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = video != null && !busy
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp))
                else { Icon(Icons.Default.Transform, null); Spacer(Modifier.width(6.dp)); Text("Generate") }
            }
            if (output.isNotBlank()) Card(Modifier.fillMaxWidth()) { Text(output, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            videoFile?.let { vf ->
                Card(Modifier.fillMaxWidth()) { GeneratedVideo(vf, Modifier.fillMaxWidth().height(260.dp)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = { downloadResult() }) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Download MP4")
                    }
                }
            }
            gate?.let { g -> PlanLockedCard(message = g.message, outOfCredits = g.outOfCredits, onSubscribe = onSubscribe) }
        }
    }
}
