package com.frit.app.ui.creative

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.frit.app.data.NetworkClient
import com.frit.app.features.creative.CreativeSuiteEngine
import com.frit.app.ui.creative.components.CreativeTopBar
import com.frit.app.ui.creative.components.DownloadIconButton
import com.frit.app.ui.creative.components.HandoffRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class DirectorShot(
    val index: Int,
    val description: String,
    val prompt: String,
    val kind: String,
    val ok: Boolean,
    val url: String?,
    val error: String?
)

@Composable
fun DirectorScreen(
    onClose: () -> Unit = {},
    onHandoff: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = CreativeSuiteEngine
    var brief by remember { mutableStateOf("") }
    var referenceVideo by remember { mutableStateOf<String?>(null) }
    var numShots by remember { mutableStateOf("4") }
    var autoCorrect by remember { mutableStateOf(true) }
    var aspect by remember { mutableStateOf("16:9") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var shots by remember { mutableStateOf<List<DirectorShot>>(emptyList()) }
    var filmB64 by remember { mutableStateOf<String?>(null) }
    var filmFile by remember { mutableStateOf<java.io.File?>(null) }

    val vidPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { referenceVideo = uriToDataUrl(ctx, uri, "video/*") }
    }

    fun downloadFilm() {
        val b64 = filmB64
        if (b64.isNullOrBlank()) { status = "Nothing to download yet"; return }
        scope.launch {
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val tmp = java.io.File(ctx.cacheDir, "frit-director-${System.currentTimeMillis()}.mp4")
                tmp.writeBytes(bytes)
                filmFile = tmp
                status = "Downloaded → " + com.frit.app.ui.creative.components.saveVideoFileToGallery(ctx, tmp, "frit-director")
            } catch (e: Exception) {
                status = "Download failed: ${e.message}"
            }
        }
    }

    fun saveFilm() {
        val b64 = filmB64
        if (b64.isNullOrBlank()) { status = "Nothing to save yet"; return }
        scope.launch {
            status = "Saving…"
            val res = engine.saveToLibrary("data:video/mp4;base64,$b64", "video", "AI Director film")
            status = res.getOrElse { "Save failed: ${it.message}" }.toString()
        }
    }

    Scaffold(
        topBar = {
            CreativeTopBar(
                title = "AI Director",
                onBack = onClose,
                saveEnabled = filmB64 != null, onSave = { saveFilm() },
                downloadEnabled = filmB64 != null, onDownload = { downloadFilm() },
                exportEnabled = filmB64 != null,
                onExport = {
                    val b64 = filmB64
                    if (b64 != null) {
                        engine.sendHandoff("data:video/mp4;base64,$b64", "video")
                        onHandoff("export")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Describe your film — the AI plans, shoots, and edits it.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = brief, onValueChange = { brief = it },
                label = { Text("e.g. A cinematic ad for a new energy drink") },
                modifier = Modifier.fillMaxWidth(), minLines = 3
            )
            OutlinedButton(onClick = { vidPicker.launch("video/*") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Videocam, null); Spacer(Modifier.width(6.dp))
                Text(if (referenceVideo == null) "Import reference video (optional)" else "Reference video attached ✓")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = numShots, onValueChange = { numShots = it.filter(Char::isDigit).take(1) },
                    label = { Text("Shots") }, modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = aspect, onValueChange = { aspect = it },
                    label = { Text("Aspect") }, modifier = Modifier.weight(1f)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autoCorrect, onCheckedChange = { autoCorrect = it })
                Text("Auto-correct: AI observes each shot and re-renders if needed", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        busy = true; filmB64 = null; filmFile = null; shots = emptyList()
                        status = "Planning the film…"
                        try {
                            val r = JSONObject(NetworkClient.director(brief, referenceVideo, numShots.toIntOrNull() ?: 4, autoCorrect, aspect))
                            if (!r.optBoolean("ok", false)) {
                                status = r.optString("error", "Director failed")
                                busy = false
                                return@launch
                            }
                            val arr = r.optJSONArray("shots") ?: JSONArray()
                            shots = (0 until arr.length()).map { i ->
                                val s = arr.getJSONObject(i)
                                DirectorShot(
                                    s.optInt("index", i + 1),
                                    s.optString("description", ""),
                                    s.optString("prompt", ""),
                                    s.optString("kind", "image"),
                                    s.optBoolean("ok", false),
                                    s.optString("image_url").ifBlank { null } ?: s.optString("url").ifBlank { null },
                                    s.optString("error").ifBlank { null }
                                )
                            }
                            status = "Generating ${shots.size} shots…"
                            val film = r.optJSONObject("film")
                            val b64 = film?.optString("base64").orEmpty()
                            if (b64.isNotBlank()) {
                                filmB64 = b64
                                val bytes = Base64.decode(b64, Base64.DEFAULT)
                                val tmp = java.io.File(ctx.cacheDir, "frit-director-${System.currentTimeMillis()}.mp4")
                                tmp.writeBytes(bytes)
                                filmFile = tmp
                                status = "Film ready ✓ — ${shots.count { it.ok }}/${shots.size} shots succeeded"
                            } else {
                                status = "Film assembled but no file came back — try again"
                            }
                        } catch (e: Exception) {
                            status = e.message ?: "Failed"
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = brief.isNotBlank() && !busy
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp))
                else { Icon(Icons.Default.Movie, null); Spacer(Modifier.width(6.dp)); Text("Direct My Film") }
            }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            if (shots.isNotEmpty()) {
                Text("Shot list", style = MaterialTheme.typography.titleSmall)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(shots) { _, shot ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (shot.ok) Icons.Default.CheckCircle else Icons.Default.Error,
                                    contentDescription = null,
                                    tint = if (shot.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Shot ${shot.index} • ${shot.kind}", style = MaterialTheme.typography.labelMedium)
                                    Text(shot.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                }
                            }
                        }
                    }
                }
            }
            filmFile?.let { f ->
                Card(Modifier.fillMaxWidth()) { GeneratedVideo(f, Modifier.fillMaxWidth().height(260.dp)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    DownloadIconButton(url = filmB64, kind = "video", onStatus = { status = it })
                }
                HandoffRow(media = filmB64, kind = "video", onHandoff = onHandoff)
                OutlinedButton(
                    onClick = {
                        if (filmB64 != null) {
                            engine.sendHandoff("data:video/mp4;base64,$filmB64", "video")
                            onHandoff("video")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Re-edit in Video Editor")
                }
            }
        }
    }
}
