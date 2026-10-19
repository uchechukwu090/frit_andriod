package com.frit.app.ui.creative

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.frit.app.core.Diag
import com.frit.app.ui.creative.components.CreativeTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

data class StoryboardFrame(
    val index: Int,
    val description: String,
    val prompt: String,
    val imageUrl: String?,
    val error: String? = null
)

@Composable
fun StoryboardScreen(
    onClose: () -> Unit = {},
    onExport: () -> Unit = {},
    onHandoff: (String) -> Unit = {},
) {
    var script by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("") }
    var numFrames by remember { mutableIntStateOf(6) }
    var aspect by remember { mutableStateOf("16:9") }
    var frames by remember { mutableStateOf<List<StoryboardFrame>>(emptyList()) }
    var isGenerating by remember { mutableStateOf(false) }
    var filmStatus by remember { mutableStateOf("") }
    var filmB64 by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val engine = com.frit.app.features.creative.CreativeSuiteEngine

    Scaffold(
        topBar = {
            CreativeTopBar(
                title = "AI Storyboard",
                onBack = onClose,
                saveEnabled = filmB64 != null,
                onSave = {
                    val f = filmB64
                    if (f != null) scope.launch {
                        filmStatus = "Saving…"
                        val res = engine.saveToLibrary("data:video/mp4;base64,$f", "video", "Auto-Pilot film")
                        filmStatus = res.getOrElse { "Save failed: ${it.message}" }.toString()
                    }
                },
                downloadEnabled = filmB64 != null,
                onDownload = {
                    val f = filmB64
                    if (f != null) scope.launch {
                        try {
                            val bytes = android.util.Base64.decode(f, android.util.Base64.DEFAULT)
                            val tmp = java.io.File(ctx.cacheDir, "frit-film-${System.currentTimeMillis()}.mp4")
                            tmp.writeBytes(bytes)
                            filmStatus = "Downloaded → " + com.frit.app.ui.creative.components.saveVideoFileToGallery(ctx, tmp, "frit-film")
                        } catch (e: Exception) {
                            filmStatus = "Download failed: ${e.message}"
                        }
                    }
                },
                exportEnabled = filmB64 != null,
                onExport = {
                    val f = filmB64
                    if (f != null) { engine.sendHandoff("data:video/mp4;base64,$f", "video"); onHandoff("export") }
                    else onExport()
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = script,
                        onValueChange = { script = it },
                        label = { Text("Script / Scene Description") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = style,
                            onValueChange = { style = it },
                            label = { Text("Style (optional)") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = numFrames.toString(),
                            onValueChange = { numFrames = it.toIntOrNull() ?: 6 },
                            label = { Text("Frames") },
                            modifier = Modifier.width(80.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("16:9", "9:16", "1:1").forEach { a ->
                            FilterChip(selected = aspect == a, onClick = { aspect = a }, label = { Text(a) })
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    var autoBusy by remember { mutableStateOf(false) }
                    var autoStatus by remember { mutableStateOf("") }
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                autoBusy = true; filmB64 = null
                                try {
                                    val r = org.json.JSONObject(com.frit.app.data.NetworkClient.autopilot(script, 2, aspect))
                                    if (r.optBoolean("ok")) {
                                        val b64 = r.optJSONObject("film")?.optString("base64").orEmpty()
                                        if (b64.isNotBlank()) {
                                            filmB64 = b64
                                            autoStatus = "Film ${r.optString("job_id")} rendered ✓ — save, download, or export it from the top bar."
                                        } else {
                                            autoStatus = "Film job ${r.optString("job_id")} accepted but no file came back — try again."
                                        }
                                    } else {
                                        autoStatus = r.optString("error", "Failed")
                                    }
                                } catch (e: Exception) { autoStatus = e.message ?: "Failed" }
                                autoBusy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = script.isNotBlank() && !autoBusy && !isGenerating,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                    ) {
                        if (autoBusy) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        else { Icon(Icons.Default.Movie, contentDescription = null); Spacer(modifier = Modifier.width(8.dp)); Text("Auto-Pilot Film 🎬") }
                    }
                    if (autoStatus.isNotBlank()) Text(autoStatus, style = MaterialTheme.typography.bodySmall)
                    if (filmStatus.isNotBlank()) Text(filmStatus, style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                isGenerating = true
                                try {
                                    val response = com.frit.app.data.NetworkClient.storyboard(script, style.ifBlank { null }, numFrames)
                                    val json = org.json.JSONObject(response)
                                    val arr = json.optJSONArray("frames")
                                    if (arr != null) {
                                        frames = (0 until arr.length()).map { i ->
                                            val f = arr.getJSONObject(i)
                                            StoryboardFrame(
                                                index = f.optInt("index", i),
                                                description = f.optString("description", ""),
                                                prompt = f.optString("prompt", ""),
                                                imageUrl = f.optString("image_url").ifBlank { null },
                                                error = f.optString("error").ifBlank { null }
                                            )
                                        }
                                    }
                                } catch (e: Exception) { Diag.warn("Creative", "storyboard_generate", e) }
                                isGenerating = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = script.isNotBlank() && !isGenerating
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Generate Storyboard")
                    }
                }
            }

            if (frames.isEmpty() && !isGenerating) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.MovieCreation, contentDescription = null, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Enter a script to generate storyboard", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(frames) { index, frame ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (frame.imageUrl != null) {
                                        GeneratedImage(
                                            src = frame.imageUrl,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else if (frame.error != null) {
                                        Text("Frame failed: ${frame.error}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(4.dp))
                                    } else {
                                        CircularProgressIndicator()
                                    }
                                }
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    tonalElevation = 2.dp
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text("Frame ${index + 1}", style = MaterialTheme.typography.labelSmall)
                                                Text(frame.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                            }
                                            if (frame.imageUrl != null) {
                                                com.frit.app.ui.creative.components.DownloadIconButton(
                                                    url = frame.imageUrl, kind = "image",
                                                    onStatus = { filmStatus = it }
                                                )
                                            }
                                        }
                                        if (frame.imageUrl != null) {
                                            TextButton(
                                                onClick = {
                                                    engine.sendHandoff(frame.imageUrl!!, "image")
                                                    onHandoff("cinema")
                                                }
                                            ) { Text("Animate this frame → Cinema") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
