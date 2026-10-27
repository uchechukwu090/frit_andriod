package com.frit.app.ui.creative

import android.net.Uri
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.frit.app.core.Diag
import com.frit.app.data.NetworkClient
import com.frit.app.ui.creative.components.CreativeTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read a picked image as a data URL, DOWNSAMPLED.
 *
 * F-13 follow-up: this used to read the whole file and base64 it. A 12 MP
 * camera photo is ~4 MB of bytes and ~16 MB of base64 — which then sat in
 * Compose state, was the body of the next request, and was pushed through
 * the hand-off bus. The editor OOM'd on the very first import. Decoding the
 * bounds first and sampling down to a sane edge keeps every downstream step
 * (upload, preview, save) small enough to actually work.
 */
private const val IMPORT_MAX_EDGE = 2048
private const val IMPORT_JPEG_QUALITY = 88

fun uriToDataUrl(ctx: android.content.Context, uri: Uri, mimeHint: String): String? {
    return try {
        val mime = ctx.contentResolver.getType(uri) ?: mimeHint
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty()) return null
        // Non-images (video references etc.) are passed through untouched.
        if (!mime.startsWith("image/")) {
            return "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
        var sample = 1
        while (bounds.outWidth / sample > IMPORT_MAX_EDGE || bounds.outHeight / sample > IMPORT_MAX_EDGE) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: return "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, IMPORT_JPEG_QUALITY, out)
        bmp.recycle()
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) { null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectorDropdown(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = !open }) {
        OutlinedTextField(
            value = selected, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(o); open = false })
            }
        }
    }
}

@Composable
fun CinemaStudioScreen(onClose: () -> Unit = {}, onSubscribe: () -> Unit = {}, onHandoff: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("image") }
    var imports by remember { mutableStateOf<List<String>>(emptyList()) }
    var opts by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var genre by remember { mutableStateOf("General") }
    var era by remember { mutableStateOf("Timeless") }
    var tempo by remember { mutableStateOf("Single Shot") }
    var camera by remember { mutableStateOf("35mm Film") }
    var lens by remember { mutableStateOf("50mm Prime") }
    var aperture by remember { mutableStateOf("f/2.8") }
    var move by remember { mutableStateOf("Static") }
    var angle by remember { mutableStateOf("Eye Level") }
    var lighting by remember { mutableStateOf("Natural Daylight") }
    var palette by remember { mutableStateOf("Film Stock 35mm") }
    var emotion by remember { mutableStateOf("Neutral") }
    var aspect by remember { mutableStateOf("16:9") }
    // The render knobs follow the ACTIVE model (see /creative-suite/capabilities).
    var caps by remember { mutableStateOf(com.frit.app.features.creative.CreativeSuiteEngine.cachedCapabilities() ?: com.frit.app.features.creative.CreativeCapabilities.FALLBACK) }
    var duration by remember { mutableStateOf(4) }
    var resolution by remember { mutableStateOf("720p") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var videoFile by remember { mutableStateOf<java.io.File?>(null) }
    var status by remember { mutableStateOf("") }
    var gate by remember { mutableStateOf<PlanGate?>(null) }
    var recipes by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var recipeName by remember { mutableStateOf("Custom") }
    // F-13: the brand kit id travels with the request so the SERVER applies
    // the brand lock. Held as state (not a local) because the generate button
    // is a separate callback.
    var brandKitId by remember { mutableStateOf<String?>(null) }

    fun applyRecipe(r: JSONObject) {
        val p = r.optJSONObject("params") ?: JSONObject()
        p.optString("genre").ifBlank { null }?.let { genre = it }
        p.optString("era").ifBlank { null }?.let { era = it }
        p.optString("tempo").ifBlank { null }?.let { tempo = it }
        p.optString("camera").ifBlank { null }?.let { camera = it }
        p.optString("lens").ifBlank { null }?.let { lens = it }
        p.optString("aperture").ifBlank { null }?.let { aperture = it }
        p.optString("move").ifBlank { null }?.let { move = it }
        p.optString("angle").ifBlank { null }?.let { angle = it }
        p.optString("lighting").ifBlank { null }?.let { lighting = it }
        p.optString("palette").ifBlank { null }?.let { palette = it }
        p.optString("emotion").ifBlank { null }?.let { emotion = it }
        recipeName = r.optString("name", "Custom")
    }

    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        scope.launch(Dispatchers.IO) {
            val added = uris.take(8 - imports.size).mapNotNull { uriToDataUrl(ctx, it, "image/*") }
            // Compose state belongs to the main thread — writing it from IO can
            // drop the update or throw under strict snapshot checking.
            launch(Dispatchers.Main) { imports = (imports + added).take(8) }
        }
    }
    val vidPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val data = uriToDataUrl(ctx, uri, "video/*")
            launch(Dispatchers.Main) { if (data != null) imports = (imports + data).take(8) }
        }
    }

    val engine = com.frit.app.features.creative.CreativeSuiteEngine
    LaunchedEffect(Unit) {
        caps = engine.capabilities()
        engine.takeHandoff()?.let { (m, k) ->
            if (k == "image" && m.isNotBlank()) imports = (listOf(m) + imports).take(8)
        }
        val pending = engine.pendingCinemaRefs
        if (pending.isNotEmpty()) {
            imports = (pending + imports).take(8)
            engine.pendingCinemaRefs = emptyList()
        }
        // F-13: the brand lock is applied SERVER-SIDE now, so the kit id is
        // what travels with the request — no client-built brand prompt.
        val pendingKit = com.frit.app.features.creative.CreativeSuiteEngine.pendingBrandKitId
        if (!pendingKit.isNullOrBlank()) {
            brandKitId = pendingKit
            com.frit.app.features.creative.CreativeSuiteEngine.pendingBrandKitId = null
        }
        try {
            val rr = JSONObject(NetworkClient.getCinemaRecipes())
            val arr = rr.optJSONArray("recipes") ?: JSONArray()
            recipes = (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (_: Exception) { Diag.warn("Cinema", "recipes", null) }
        try {
            val r = JSONObject(NetworkClient.getCinemaOptions())
            val o = r.optJSONObject("options") ?: JSONObject()
            val m = mutableMapOf<String, List<String>>()
            o.keys().forEach { k ->
                val a = o.optJSONArray(k) ?: JSONArray()
                m[k] = (0 until a.length()).map { a.getString(it) }
            }
            opts = m
        } catch (_: Exception) { Diag.warn("Cinema", "options", null) }
    }
    fun opt(key: String, fallback: List<String>) = opts[key] ?: fallback

    fun saveCurrent() {
        val url = result.ifBlank { null }
        if (url != null) {
            scope.launch {
                status = "Saving…"
                val res = engine.saveToLibrary(url, "image", prompt.take(60).ifBlank { "Cinema still" })
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
                    val res = engine.saveToLibrary(dataUrl, "video", prompt.take(60).ifBlank { "Cinema clip" })
                    status = res.getOrElse { "Save failed: ${it.message}" }.toString()
                } catch (e: Exception) { status = "Save failed: ${e.message}" }
            }
            return
        }
        status = "Nothing to save yet"
    }
    fun downloadCurrent() {
        val url = result.ifBlank { null } ?: videoFile?.let { "file:${it.absolutePath}" }
        if (url == null) { status = "Nothing to download yet"; return }
        scope.launch {
            status = "Downloading…"
            try {
                status = if (url.startsWith("file:")) {
                    "Downloaded → " + com.frit.app.ui.creative.components.saveVideoFileToGallery(ctx, java.io.File(url.removePrefix("file:")), "frit-cinema")
                } else {
                    "Downloaded → " + com.frit.app.ui.creative.components.saveMediaToGallery(ctx, url, "image")
                }
            } catch (e: Exception) {
                status = "Download failed: ${e.message}"
            }
        }
    }
    Scaffold(
        topBar = {
            CreativeTopBar(
                title = "Cinema Studio", onBack = onClose,
                saveEnabled = result.isNotBlank() || videoFile != null, onSave = { saveCurrent() },
                downloadEnabled = result.isNotBlank() || videoFile != null, onDownload = { downloadCurrent() },
                exportEnabled = result.isNotBlank() || videoFile != null,
                onExport = {
                    if (result.isNotBlank()) engine.sendHandoff(result, "image")
                    onHandoff("export")
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Describe your shot…") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = kind == "image", onClick = { kind = "image"; aspect = "1:1" }, label = { Text("Image") })
                FilterChip(selected = kind == "video", onClick = { kind = "video"; aspect = "16:9" }, label = { Text("Video") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { imgPicker.launch("image/*") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Upload, null); Spacer(Modifier.width(6.dp)); Text("Import images (${imports.size})")
                }
                OutlinedButton(onClick = { vidPicker.launch("video/*") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Videocam, null); Spacer(Modifier.width(6.dp)); Text("Import video")
                }
            }
            if (imports.isNotEmpty()) TextButton(onClick = { imports = emptyList() }) { Text("Clear imports") }
            Text("Director's Panel", style = MaterialTheme.typography.titleSmall)
            if (recipes.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                @OptIn(ExperimentalMaterial3Api::class)
                ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = !open }) {
                    OutlinedTextField(
                        value = "Recipe: $recipeName", onValueChange = {}, readOnly = true, label = { Text("One-tap look") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        recipes.forEach { r ->
                            DropdownMenuItem(
                                text = { Text("${r.optString("name")} — ${r.optString("description")}") },
                                onClick = { applyRecipe(r); open = false }
                            )
                        }
                    }
                }
            }
            DirectorDropdown("Genre", opt("genres", listOf("General")), genre, { genre = it })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { DirectorDropdown("Era", opt("eras", listOf("Timeless")), era, { era = it }) }
                Box(Modifier.weight(1f)) { DirectorDropdown("Tempo", opt("tempos", listOf("Single Shot")), tempo, { tempo = it }) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { DirectorDropdown("Camera", opt("cameras", listOf("35mm Film")), camera, { camera = it }) }
                Box(Modifier.weight(1f)) { DirectorDropdown("Lens", opt("lenses", listOf("50mm Prime")), lens, { lens = it }) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { DirectorDropdown("Aperture", opt("apertures", listOf("f/2.8")), aperture, { aperture = it }) }
                Box(Modifier.weight(1f)) { DirectorDropdown("Move", opt("moves", listOf("Static")), move, { move = it }) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { DirectorDropdown("Angle", opt("angles", listOf("Eye Level")), angle, { angle = it }) }
                Box(Modifier.weight(1f)) { DirectorDropdown("Lighting", opt("lighting", listOf("Natural Daylight")), lighting, { lighting = it }) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { DirectorDropdown("Color grade", opt("palettes", listOf("Film Stock 35mm")), palette, { palette = it }) }
                Box(Modifier.weight(1f)) { DirectorDropdown("Emotion", opt("emotions", listOf("Neutral")), emotion, { emotion = it }) }
            }
            // Aspect ratio: the director's list, filtered to what the model for
            // THIS kind can render. A ratio the model rejects would otherwise be
            // snapped away silently on the server.
            val modelCaps = if (kind == "video") caps.video else caps.image
            val aspectOptions = run {
                val base = opt("aspect_ratios", listOf("16:9", "9:16", "1:1"))
                val allowed = modelCaps.aspects
                if (allowed.isEmpty()) base
                else base.filter { allowed.contains(it) }.ifEmpty { allowed }
            }
            LaunchedEffect(aspectOptions) {
                if (!aspectOptions.contains(aspect)) modelCaps.pickAspect(aspect)?.let { aspect = it }
            }
            DirectorDropdown("Aspect ratio", aspectOptions, aspect, { aspect = it })
            if (kind == "video") {
                if (caps.video.durations.size > 1) {
                    DirectorDropdown(
                        "Duration",
                        caps.video.durations.map { "${it}s" },
                        "${caps.video.pickDuration(duration) ?: 4}s",
                        { v -> v.trimEnd('s').toIntOrNull()?.let { duration = it } },
                    )
                }
                if (caps.video.resolutions.size > 1) {
                    DirectorDropdown(
                        "Resolution", caps.video.resolutions,
                        caps.video.pickResolution(resolution) ?: resolution,
                        { resolution = it },
                    )
                }
            }
            Text(
                "Model: ${modelCaps.label.ifBlank { modelCaps.model }}" +
                    (if (modelCaps.summary.isNotBlank()) " — ${modelCaps.summary}" else ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        busy = true; status = "Directing…"; gate = null; result = ""; videoFile = null
                        try {
                            val director = JSONObject()
                                .put("genre", genre).put("era", era).put("tempo", tempo)
                                .put("camera", camera).put("lens", lens).put("aperture", aperture)
                                .put("move", move).put("angle", angle).put("lighting", lighting)
                                .put("palette", palette).put("emotion", emotion)
                            val arr = JSONArray(); imports.forEach { arr.put(it) }
                            val r = JSONObject(NetworkClient.cinemaGenerate(
                                prompt, kind, director, arr, aspect,
                                if (kind == "video") (caps.video.pickDuration(duration) ?: 4) else null,
                                if (kind == "video") (caps.video.pickResolution(resolution) ?: "720p") else null,
                                brandKitId
                            ))
                            val locked = parsePlanGate(r)
                            if (locked != null) { gate = locked; result = ""; status = "" }
                            else if (r.optBoolean("ok", false)) {
                                if (kind == "video") {
                                    val pid = providerJobId(r)
                                    if (pid == null) {
                                        result = ""; status = "Video was accepted but the server returned no job id — try again."
                                    } else {
                                        status = "Video submitted — rendering…"
                                        val f = awaitVideoFile(ctx, pid) { status = it }
                                        if (f != null) { videoFile = f; status = "Done" }
                                    }
                                } else {
                                    val url = extractImageUrl(r)
                                    if (url != null) { result = url; status = "Done" }
                                    else { result = ""; status = creativeErrorText(r, "Server returned no image — try again.") }
                                }
                            } else {
                                result = ""; status = creativeErrorText(r)
                            }
                        } catch (e: Exception) { status = e.message ?: "Failed" }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = prompt.isNotBlank() && !busy
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp))
                else { Icon(Icons.Default.Movie, null); Spacer(Modifier.width(6.dp)); Text("Generate ${kind.replaceFirstChar { it.uppercase() }}") }
            }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            gate?.let { g -> PlanLockedCard(message = g.message, outOfCredits = g.outOfCredits, onSubscribe = onSubscribe) }
            if (result.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        GeneratedImage(result, Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                        com.frit.app.ui.creative.components.DownloadIconButton(url = result, kind = "image", onStatus = { status = it })
                    }
                }
                com.frit.app.ui.creative.components.HandoffRow(media = result, kind = "image", onHandoff = onHandoff)
            }
            videoFile?.let { vf ->
                Card(Modifier.fillMaxWidth()) { GeneratedVideo(vf, Modifier.fillMaxWidth().height(260.dp)) }
            }
            if (!busy) Box(Modifier.height(8.dp)) else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}
