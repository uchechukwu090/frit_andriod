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
import coil.compose.AsyncImage
import com.frit.app.data.NetworkClient
import com.frit.app.ui.creative.components.CreativeTopBar
import com.frit.app.ui.creative.components.PromptSuggestion
import com.frit.app.ui.creative.components.PromptSuggestionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenerateScreen(onClose: () -> Unit = {}, onSubscribe: () -> Unit = {}, onHandoff: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = com.frit.app.features.creative.CreativeSuiteEngine
    var prompt by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("image") }
    var aspect by remember { mutableStateOf("1:1") }
    // Knobs come from the ACTIVE model, not from a hardcoded list: the image
    // model may reject 4:5 and the video model may only render 4s/720p.
    var caps by remember { mutableStateOf(com.frit.app.features.creative.CreativeSuiteEngine.cachedCapabilities() ?: com.frit.app.features.creative.CreativeCapabilities.FALLBACK) }
    var duration by remember { mutableStateOf(4) }
    var resolution by remember { mutableStateOf("720p") }
    var importUrl by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var imageOut by remember { mutableStateOf<String?>(null) }
    var videoFile by remember { mutableStateOf<java.io.File?>(null) }
    var status by remember { mutableStateOf("") }
    var gate by remember { mutableStateOf<PlanGate?>(null) }
    var ideas by remember { mutableStateOf<List<PromptSuggestion>>(emptyList()) }
    var ideasBusy by remember { mutableStateOf(false) }

    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val data = uriToDataUrl(ctx, uri, "image/*")
            // Compose state must be written on the main thread.
            launch(Dispatchers.Main) { importUrl = data }
        }
    }

    // Load what the active models accept, then snap the current choices into
    // that set (a 4:5 pick on a model that rejects it becomes the closest
    // ratio the model DOES render, instead of being silently ignored server-side).
    LaunchedEffect(Unit) { caps = engine.capabilities() }
    val role = if (kind == "image") caps.image else caps.video
    LaunchedEffect(role) {
        role.pickAspect(aspect)?.let { if (it != aspect) aspect = it }
        caps.video.pickDuration(duration)?.let { if (it != duration) duration = it }
        caps.video.pickResolution(resolution)?.let { if (it != resolution) resolution = it }
    }

    fun saveCurrent() {
        val url = imageOut
        if (!url.isNullOrBlank()) {
            scope.launch {
                status = "Saving…"
                val res = engine.saveToLibrary(url, "image", prompt.take(60).ifBlank { "Generated image" })
                status = res.getOrElse { "Save failed: ${it.message}" }.toString()
            }
            return
        }
        val vf = videoFile
        if (vf != null) {
            if (vf.length() > 8_000_000) { status = "Video too large to store in the library — use Download."; return }
            scope.launch(Dispatchers.IO) {
                status = "Saving…"
                try {
                    val dataUrl = "data:video/mp4;base64," + android.util.Base64.encodeToString(vf.readBytes(), android.util.Base64.NO_WRAP)
                    val res = engine.saveToLibrary(dataUrl, "video", prompt.take(60).ifBlank { "Generated video" })
                    status = res.getOrElse { "Save failed: ${it.message}" }.toString()
                } catch (e: Exception) { status = "Save failed: ${e.message}" }
            }
            return
        }
        status = "Nothing to save yet"
    }
    fun downloadCurrent() {
        val url = imageOut
        if (!url.isNullOrBlank()) {
            scope.launch {
                status = "Downloading…"
                try {
                    status = "Downloaded → " + com.frit.app.ui.creative.components.saveMediaToGallery(ctx, url, "image")
                } catch (e: Exception) {
                    status = "Download failed: ${e.message}"
                }
            }
            return
        }
        val vf = videoFile
        if (vf != null) {
            scope.launch {
                try {
                    status = "Downloaded → " + com.frit.app.ui.creative.components.saveVideoFileToGallery(ctx, vf, "frit-video")
                } catch (e: Exception) { status = "Download failed: ${e.message}" }
            }
            return
        }
        status = "Nothing to download yet"
    }
    Scaffold(
        topBar = {
            CreativeTopBar(
                title = "Generate", onBack = onClose,
                saveEnabled = imageOut != null || videoFile != null, onSave = { saveCurrent() },
                downloadEnabled = imageOut != null || videoFile != null, onDownload = { downloadCurrent() },
                exportEnabled = imageOut != null || videoFile != null,
                onExport = {
                    val img = imageOut
                    val vid = videoFile
                    if (img != null) {
                        engine.sendHandoff(img, "image")
                        onHandoff("export")
                    } else if (vid != null) {
                        // Same 8MB ceiling as the other studios: bigger clips are
                        // downloaded, not base64'd through the handoff bus.
                        if (vid.length() > 8_000_000) status = "Video too large to hand off — download it instead"
                        else {
                            try {
                                val dataUrl = "data:video/mp4;base64," + android.util.Base64
                                    .encodeToString(vid.readBytes(), android.util.Base64.NO_WRAP)
                                engine.sendHandoff(dataUrl, "video")
                                onHandoff("export")
                            } catch (e: Exception) { status = "Export failed: ${e.message}" }
                        }
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Prompt — describe what to create…") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = kind == "image", onClick = {
                    kind = "image"
                    aspect = caps.image.pickAspect("1:1") ?: "1:1"
                }, label = { Text("Image") })
                FilterChip(selected = kind == "video", onClick = {
                    kind = "video"
                    aspect = caps.video.pickAspect("16:9") ?: "16:9"
                }, label = { Text("Video") })
            }
            // Aspect chips: ONLY what the model in charge accepts. FlowRow because
            // providers list up to 18 ratios — a plain Row would clip them.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val aspects = role.aspects.ifEmpty { listOf(aspect) }
                aspects.forEach { a ->
                    FilterChip(selected = aspect == a, onClick = { aspect = a }, label = { Text(a) })
                }
            }
            if (kind == "video") {
                if (caps.video.durations.size > 1) {
                    Text("Duration", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        caps.video.durations.forEach { d ->
                            FilterChip(selected = duration == d, onClick = { duration = d }, label = { Text("${d}s") })
                        }
                    }
                }
                if (caps.video.resolutions.size > 1) {
                    Text("Resolution", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        caps.video.resolutions.forEach { r ->
                            FilterChip(selected = resolution == r, onClick = { resolution = r }, label = { Text(r) })
                        }
                    }
                }
            }
            Text(
                "Model: ${role.label.ifBlank { role.model }}" +
                    (if (role.summary.isNotBlank()) " — ${role.summary}" else ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = { imgPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Upload, null); Spacer(Modifier.width(6.dp))
                Text(if (importUrl == null) "Import image (optional reference)" else "Reference attached ✓")
            }
            if (importUrl != null) TextButton(onClick = { importUrl = null }) { Text("Remove reference") }
            OutlinedButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        ideasBusy = true
                        try {
                            val r = JSONObject(NetworkClient.suggestPrompts(prompt, null))
                            val arr = r.optJSONArray("suggestions")
                            ideas = if (arr != null) (0 until arr.length()).mapNotNull { i ->
                                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                                val p = o.optString("prompt").ifBlank { null } ?: return@mapNotNull null
                                PromptSuggestion(p, o.optString("description", p), o.optString("style_preset").ifBlank { null })
                            } else emptyList()
                            if (ideas.isEmpty()) status = r.optString("error", "No ideas — try a different seed")
                        } catch (e: Exception) { status = e.message ?: "Failed" }
                        ideasBusy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = prompt.isNotBlank() && !ideasBusy && !busy
            ) {
                if (ideasBusy) CircularProgressIndicator(Modifier.size(18.dp))
                else { Icon(Icons.Default.Lightbulb, null); Spacer(Modifier.width(6.dp)); Text("Prompt ideas") }
            }
            ideas.forEach { idea ->
                PromptSuggestionCard(suggestion = idea, onUse = { prompt = it.prompt; ideas = emptyList() })
            }
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        busy = true; imageOut = null; videoFile = null; gate = null; status = "Generating…"
                        try {
                            if (kind == "image") {
                                val r = JSONObject(NetworkClient.generateCreativeImage(prompt, null, aspect, 1, importUrl))
                                val locked = parsePlanGate(r)
                                if (locked != null) {
                                    gate = locked; status = ""
                                } else if (r.optBoolean("ok", false)) {
                                    val url = extractImageUrl(r)
                                    if (url != null) { imageOut = url; status = "Done" }
                                    else status = creativeErrorText(r, "Server returned no image — try again.")
                                } else {
                                    status = creativeErrorText(r)
                                }
                            } else {
                                // Send exactly what the chips show — the model's
                                // supported duration/resolution, not a hardcoded 4/720p.
                                val r = JSONObject(NetworkClient.generateVideo(
                                    prompt,
                                    caps.video.pickDuration(duration) ?: 4,
                                    caps.video.pickResolution(resolution) ?: "720p",
                                    aspect
                                ))
                                val locked = parsePlanGate(r)
                                if (locked != null) {
                                    gate = locked; status = ""
                                } else if (r.optBoolean("ok", false)) {
                                    val pid = providerJobId(r)
                                    if (pid == null) status = "Video was accepted but the server returned no job id — try again."
                                    else {
                                        status = "Video submitted — rendering…"
                                        val f = awaitVideoFile(ctx, pid) { status = it }
                                        if (f != null) { videoFile = f; status = "Done" }
                                    }
                                } else {
                                    status = creativeErrorText(r)
                                }
                            }
                        } catch (e: Exception) { status = e.message ?: "Failed" }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = prompt.isNotBlank() && !busy
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp))
                else { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Generate") }
            }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            gate?.let { g ->
                PlanLockedCard(message = g.message, outOfCredits = g.outOfCredits, onSubscribe = onSubscribe)
            }
            if (imageOut != null) Card(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    GeneratedImage(imageOut!!, Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp))
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                    com.frit.app.ui.creative.components.DownloadIconButton(url = imageOut, kind = "image", onStatus = { status = it })
                }
                com.frit.app.ui.creative.components.HandoffRow(media = imageOut, kind = "image", onHandoff = onHandoff)
            }
            videoFile?.let { vf ->
                Card(Modifier.fillMaxWidth()) { GeneratedVideo(vf, Modifier.fillMaxWidth().height(260.dp)) }
            }
        }
    }
}
